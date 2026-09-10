/**
 * Persistent off-context result storage for large tool outputs (ResultStore).
 *
 * Saves raw outputs into <workspaceRoot>/.dataagent/results/${result_ref}.json
 * and provides paginated/projected retrieval for fetch_tool_result.
 */

import crypto from "node:crypto";
import { createReadStream } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";
import readline from "node:readline";

export interface SaveResultOutcome {
  result_ref: string;
  file_path: string;
  relative_path: string;
  byte_size: number;
}

export interface FetchSliceOptions {
  offset?: number;
  limit?: number;
  columns?: string[];
}

export interface TabularSliceResult {
  result_ref: string;
  is_tabular: true;
  total_rows: number;
  offset: number;
  limit: number;
  has_more: boolean;
  columns?: string[];
  rows: Record<string, unknown>[];
}

export interface TextSliceResult {
  result_ref: string;
  is_tabular: false;
  total_chars: number;
  offset: number;
  limit: number;
  has_more: boolean;
  content: string;
}

export type FetchSliceResult = TabularSliceResult | TextSliceResult;

const RESULTS_DIR = path.join(".dataagent", "results");
const SAFE_REF_PATTERN = /^[a-zA-Z0-9_-]+$/;
const DEFAULT_LIMIT = 20;
const MAX_LIMIT = 100;

/**
 * Marker for the line-oriented tabular layout.
 *
 * Tabular results are stored as a header line followed by one JSON row per
 * line, so a slice costs offset+limit lines instead of parsing the whole file.
 * The header carries total_rows, so pagination metadata needs no extra scan.
 * Files without this header (plain text, non-row JSON) keep the whole-file
 * path, which also makes results written before this format still readable.
 */
const JSONL_FORMAT = "dataagent_jsonl_v1";

interface JsonlHeader {
  _format: typeof JSONL_FORMAT;
  total_rows: number;
}

/** Rows detected from a tool payload, or null when it is not row-shaped. */
export function detectRows(parsed: unknown): Record<string, unknown>[] | null {
  if (Array.isArray(parsed)) {
    if (parsed.length === 0) return [];
    return typeof parsed[0] === "object" && parsed[0] !== null
      ? (parsed as Record<string, unknown>[])
      : null;
  }
  if (parsed && typeof parsed === "object") {
    const obj = parsed as Record<string, unknown>;
    if (Array.isArray(obj.rows)) return obj.rows as Record<string, unknown>[];
    if (Array.isArray(obj.data)) return obj.data as Record<string, unknown>[];
  }
  return null;
}

export function generateResultRef(): string {
  // randomUUID over Math.random: refs name immutable evidence, and a collision
  // would silently replace an earlier result rather than fail.
  return `res_${crypto.randomUUID().replace(/-/g, "")}`;
}

/**
 * Resolve a ref to a real path that is provably inside the results directory.
 *
 * The SAFE_REF_PATTERN check only rejects lexical traversal; a symlink planted
 * inside the results directory still resolves outward, so a valid-looking ref
 * could read or overwrite any file the process can reach. Compare real paths
 * (symlinks already followed) and require the results directory itself to be a
 * real directory, not a link to somewhere else.
 */
async function resolveContainedPath(workspaceRoot: string, resultRef: string): Promise<string> {
  const dirPath = path.resolve(workspaceRoot, RESULTS_DIR);
  const realDir = await fs.realpath(dirPath);
  const realWorkspace = await fs.realpath(path.resolve(workspaceRoot));
  if (realDir !== path.join(realWorkspace, RESULTS_DIR)) {
    throw new Error("Result storage directory escapes the workspace");
  }

  const candidate = path.join(realDir, `${resultRef}.json`);
  let realFile: string;
  try {
    realFile = await fs.realpath(candidate);
  } catch {
    // Missing file is not an escape; callers report it as "not found".
    return candidate;
  }
  if (realFile !== candidate) {
    throw new Error(`Result '${resultRef}' resolves outside result storage`);
  }
  return realFile;
}

export async function saveToolResult(
  workspaceRoot: string,
  rawContent: unknown,
  customRef?: string
): Promise<SaveResultOutcome> {
  const resultRef = customRef && SAFE_REF_PATTERN.test(customRef) ? customRef : generateResultRef();
  const dirPath = path.resolve(workspaceRoot, RESULTS_DIR);
  await fs.mkdir(dirPath, { recursive: true });

  const fileName = `${resultRef}.json`;
  const absolutePath = path.join(dirPath, fileName);
  const relativePath = path.join(RESULTS_DIR, fileName);

  const serialized = typeof rawContent === "string" ? rawContent : JSON.stringify(rawContent, null, 2);
  const buffer = Buffer.from(toStorageFormat(serialized), "utf8");
  // "wx" refuses to follow a planted symlink and refuses to overwrite an
  // existing ref, so stored results stay immutable evidence.
  await fs.writeFile(absolutePath, buffer, { flag: "wx" });

  return {
    result_ref: resultRef,
    file_path: absolutePath,
    relative_path: relativePath,
    byte_size: buffer.length,
  };
}

/**
 * Convert row-shaped payloads to the line-oriented layout; pass anything else
 * through untouched. Serialization happens once at save time when the content
 * is already in memory, so only the read path benefits — which is the one that
 * runs repeatedly.
 */
function toStorageFormat(serialized: string): string {
  let parsed: unknown;
  try {
    parsed = JSON.parse(serialized);
  } catch {
    return serialized;
  }
  const rows = detectRows(parsed);
  if (rows === null) {
    return serialized;
  }
  const header: JsonlHeader = { _format: JSONL_FORMAT, total_rows: rows.length };
  const lines = [JSON.stringify(header)];
  for (const row of rows) {
    lines.push(JSON.stringify(row));
  }
  return lines.join("\n");
}

/** Read the first line only, to learn the layout without loading the file. */
async function readHeader(absolutePath: string): Promise<JsonlHeader | null> {
  const stream = createReadStream(absolutePath, { encoding: "utf8" });
  const rl = readline.createInterface({ input: stream, crlfDelay: Infinity });
  try {
    for await (const line of rl) {
      try {
        const parsed = JSON.parse(line) as JsonlHeader;
        return parsed?._format === JSONL_FORMAT ? parsed : null;
      } catch {
        return null;
      }
    }
    return null;
  } finally {
    rl.close();
    stream.destroy();
  }
}

/** Collect rows [offset, offset+limit) without parsing the rest of the file. */
async function readRowSlice(
  absolutePath: string,
  offset: number,
  limit: number
): Promise<Record<string, unknown>[]> {
  const stream = createReadStream(absolutePath, { encoding: "utf8" });
  const rl = readline.createInterface({ input: stream, crlfDelay: Infinity });
  const collected: Record<string, unknown>[] = [];
  let rowIndex = -1; // header line is index -1
  try {
    for await (const line of rl) {
      if (rowIndex === -1) {
        rowIndex = 0;
        continue;
      }
      if (rowIndex >= offset && collected.length < limit) {
        try {
          collected.push(JSON.parse(line) as Record<string, unknown>);
        } catch {
          // A corrupt line should not abort the whole page.
        }
      }
      rowIndex += 1;
      if (collected.length >= limit) {
        break;
      }
    }
  } finally {
    rl.close();
    stream.destroy();
  }
  return collected;
}

export async function fetchToolResultSlice(
  workspaceRoot: string,
  resultRef: string,
  options?: FetchSliceOptions
): Promise<FetchSliceResult> {
  if (!SAFE_REF_PATTERN.test(resultRef)) {
    throw new Error(`Invalid result_ref: '${resultRef}'`);
  }

  const absolutePath = await resolveContainedPath(workspaceRoot, resultRef);

  const offset = Math.max(0, options?.offset ?? 0);
  const limit = Math.min(MAX_LIMIT, Math.max(1, options?.limit ?? DEFAULT_LIMIT));
  const columns = options?.columns && options.columns.length > 0 ? options.columns : undefined;

  let header: JsonlHeader | null;
  try {
    header = await readHeader(absolutePath);
  } catch {
    throw new Error(`Result '${resultRef}' not found in storage (path: ${RESULTS_DIR}/${resultRef}.json)`);
  }

  // Line-oriented tabular result: read only the requested window.
  if (header) {
    const sliced = await readRowSlice(absolutePath, offset, limit);
    return {
      result_ref: resultRef,
      is_tabular: true,
      total_rows: header.total_rows,
      offset,
      limit: sliced.length,
      has_more: offset + sliced.length < header.total_rows,
      columns,
      rows: columns ? sliced.map((row) => projectColumns(row, columns)) : sliced,
    };
  }

  let contentStr: string;
  try {
    contentStr = await fs.readFile(absolutePath, "utf8");
  } catch (err: unknown) {
    throw new Error(`Result '${resultRef}' not found in storage (path: ${RESULTS_DIR}/${resultRef}.json)`);
  }

  let parsed: unknown = null;
  try {
    parsed = JSON.parse(contentStr);
  } catch {
    // If not JSON, treat as raw text
  }

  // Legacy whole-file path: results written before the line-oriented layout,
  // and row-shaped payloads that failed to serialize back at save time.
  const rows = detectRows(parsed);

  if (rows !== null) {
    const totalRows = rows.length;
    const sliceEnd = Math.min(totalRows, offset + limit);
    let sliced = rows.slice(offset, sliceEnd);

    if (columns) {
      sliced = sliced.map((row) => projectColumns(row, columns));
    }

    return {
      result_ref: resultRef,
      is_tabular: true,
      total_rows: totalRows,
      offset,
      limit: sliced.length,
      has_more: sliceEnd < totalRows,
      columns,
      rows: sliced,
    };
  }

  // Non-tabular text slice
  const text = typeof parsed === "string" ? parsed : contentStr;
  const totalChars = text.length;
  const sliceEnd = Math.min(totalChars, offset + limit * 100);
  const slicedText = text.slice(offset, sliceEnd);

  return {
    result_ref: resultRef,
    is_tabular: false,
    total_chars: totalChars,
    offset,
    limit: slicedText.length,
    has_more: sliceEnd < totalChars,
    content: slicedText,
  };
}

/** Keep only the requested columns, skipping ones the row does not have. */
function projectColumns(
  row: Record<string, unknown>,
  columns: string[]
): Record<string, unknown> {
  // Null-prototype: the read side already guards against inherited names, but
  // writing `__proto__` to an ordinary object sets its prototype rather than
  // the column, so a requested column vanished from the projection.
  const filtered: Record<string, unknown> = Object.create(null);
  for (const col of columns) {
    if (Object.prototype.hasOwnProperty.call(row, col)) {
      filtered[col] = row[col];
    }
  }
  return { ...filtered };
}
