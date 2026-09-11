/**
 * Deterministic structured feature extractor for SQL tables and tool outputs.
 *
 * Implements "Deterministic Extraction > Fuzzy LLM Summary":
 * Safely extracts Schema, row count, sample head/tail slices, and numerical
 * column statistics into a compact, syntactically valid JSON representation.
 */

export const DEFAULT_FOLD_THRESHOLD_BYTES = 16 * 1024; // 16 KB

export interface ColumnMetadata {
  name: string;
  type: string;
}

export interface ColumnStat {
  null_count: number;
  min?: number | string;
  max?: number | string;
}

export interface CompactTabularDigest {
  _type: "dataagent_folded_result";
  result_ref: string;
  tool_name: string;
  is_tabular: true;
  total_rows: number;
  columns: ColumnMetadata[];
  preview_head: Record<string, unknown>[];
  preview_tail?: Record<string, unknown>[];
  column_stats?: Record<string, ColumnStat>;
  notice: string;
}

export interface CompactTextDigest {
  _type: "dataagent_folded_result";
  result_ref: string;
  tool_name: string;
  is_tabular: false;
  total_chars: number;
  preview_head: string;
  preview_tail?: string;
  notice: string;
}

export type CompactResultDigest = CompactTabularDigest | CompactTextDigest;

export function shouldFold(content: unknown, thresholdBytes = DEFAULT_FOLD_THRESHOLD_BYTES): boolean {
  if (content === null || content === undefined) {
    return false;
  }
  let size = 0;
  if (typeof content === "string") {
    size = Buffer.byteLength(content, "utf8");
  } else {
    try {
      size = Buffer.byteLength(JSON.stringify(content), "utf8");
    } catch {
      size = 0;
    }
  }
  return size >= thresholdBytes;
}

function inferType(val: unknown): string {
  if (val === null || val === undefined) return "unknown";
  if (typeof val === "number") return Number.isInteger(val) ? "INTEGER" : "DECIMAL";
  if (typeof val === "boolean") return "BOOLEAN";
  if (typeof val === "string") {
    if (/^\d{4}-\d{2}-\d{2}(T|\s)\d{2}:\d{2}:\d{2}/.test(val)) return "DATETIME";
    if (/^\d{4}-\d{2}-\d{2}$/.test(val)) return "DATE";
    return "VARCHAR";
  }
  if (Array.isArray(val)) return "ARRAY";
  if (typeof val === "object") return "OBJECT";
  return typeof val;
}

export function extractDigest(
  rawContent: unknown,
  options: { resultRef: string; toolName: string }
): CompactResultDigest {
  const { resultRef, toolName } = options;

  let parsed: unknown = rawContent;
  if (typeof rawContent === "string") {
    try {
      parsed = JSON.parse(rawContent);
    } catch {
      // not json, keep as string
    }
  }

  // Detect tabular rows
  let rows: Record<string, unknown>[] | null = null;
  if (Array.isArray(parsed) && (parsed.length === 0 || (typeof parsed[0] === "object" && parsed[0] !== null))) {
    rows = parsed as Record<string, unknown>[];
  } else if (parsed && typeof parsed === "object") {
    const obj = parsed as Record<string, unknown>;
    if (Array.isArray(obj.rows)) {
      rows = obj.rows as Record<string, unknown>[];
    } else if (Array.isArray(obj.data)) {
      rows = obj.data as Record<string, unknown>[];
    }
  }

  if (rows !== null) {
    const totalRows = rows.length;
    const colMap = new Map<string, string>();

    // Discover column names across every row. A 10-row sample silently dropped
    // any column that first appears later (sparse rows, LEFT JOIN results), and
    // a column missing from the schema is worse than a slow scan: the model
    // never learns it can query it. Type stays the first non-null value seen.
    for (let i = 0; i < totalRows; i += 1) {
      const row = rows[i];
      if (row && typeof row === "object") {
        for (const [key, val] of Object.entries(row)) {
          const known = colMap.get(key);
          if (known === undefined || known === "unknown") {
            colMap.set(key, inferType(val));
          }
        }
      }
    }

    const columns: ColumnMetadata[] = Array.from(colMap.entries()).map(([name, type]) => ({
      name,
      type,
    }));

    // Head 5 rows / tail 5 rows, with each cell capped. Preview rows used to
    // keep whole cells, so one 20k-character cell made the "compact" digest
    // larger than the output it was folding.
    const previewHead = rows.slice(0, 5).map(capRowCells);
    const previewTail = totalRows > 5 ? rows.slice(Math.max(5, totalRows - 5)).map(capRowCells) : undefined;

    // Statistics run over every row, not a 100-row prefix. The rows are already
    // fully in memory, so a prefix bought nothing while reporting a max of 99
    // for a dataset whose real max sat in row 101 — a wrong number is worse
    // than a slow one when the model reasons numerically on it.
    // Null-prototype: column names come from query results, and assigning
    // `__proto__` to an ordinary object sets its prototype instead of storing a
    // stat — the digest then advertised a column it had silently dropped from
    // both stats and preview.
    const columnStats: Record<string, ColumnStat> = Object.create(null);

    for (const col of columns) {
      const isNum = col.type === "INTEGER" || col.type === "DECIMAL";
      let nullCount = 0;
      let minVal: number | undefined = undefined;
      let maxVal: number | undefined = undefined;

      for (let i = 0; i < totalRows; i += 1) {
        const val = rows[i]?.[col.name];
        if (val === null || val === undefined) {
          nullCount += 1;
        } else if (isNum && typeof val === "number" && !Number.isNaN(val)) {
          if (minVal === undefined || val < minVal) minVal = val;
          if (maxVal === undefined || val > maxVal) maxVal = val;
        }
      }

      columnStats[col.name] = {
        null_count: nullCount,
        ...(minVal !== undefined ? { min: minVal } : {}),
        ...(maxVal !== undefined ? { max: maxVal } : {}),
      };
    }

    const sampleCount = previewHead.length + (previewTail?.length ?? 0);
    const notice =
      `[SYSTEM NOTICE: This query returned ${totalRows} rows in total. ` +
      `To protect the context window from token overflow, only a sample of ${sampleCount} rows (head 5 + tail 5) ` +
      `is displayed. The actual dataset contains ${totalRows} rows in storage (ref: '${resultRef}'). ` +
      `Do NOT assume the total count is ${sampleCount}. ` +
      `If you need specific rows or column slices, call 'fetch_tool_result(result_ref="${resultRef}", offset=..., limit=...)'; ` +
      `to find rows without knowing where they are, call 'search_result(result_ref="${resultRef}", query="...")'. ` +
      `Prefer either over re-running the query that produced this — the full result is already here. ` +
      `This sampling is internal plumbing: never mention it, the ref, or the context window in your answer — ` +
      `the reader asked about their data, not about how it reached you.]`;

    return {
      _type: "dataagent_folded_result",
      result_ref: resultRef,
      tool_name: toolName,
      is_tabular: true,
      total_rows: totalRows,
      columns,
      preview_head: previewHead,
      preview_tail: previewTail,
      column_stats: { ...columnStats },
      notice,
    };
  }

  // Non-tabular text / logs
  const textStr = typeof rawContent === "string" ? rawContent : JSON.stringify(rawContent, null, 2);
  const totalChars = textStr.length;
  const headChars = Math.min(1200, totalChars);
  const previewHead = textStr.slice(0, headChars);
  const previewTail = totalChars > 2000 ? textStr.slice(totalChars - 600) : undefined;

  const notice =
    `[SYSTEM NOTICE: Output exceeded size limit (${totalChars} characters). ` +
    `Full content has been preserved in ResultStore (ref: '${resultRef}'). ` +
    `Showing truncated preview. Call 'fetch_tool_result(result_ref="${resultRef}", offset=..., limit=...)' to read more, ` +
    `or 'search_result(result_ref="${resultRef}", query="...")' to locate part of it. ` +
    `Prefer either over re-running the tool that produced this — the full output is already here. ` +
    `This truncation is internal plumbing: never mention it, the ref, or the context window in your answer — ` +
    `the reader asked about their data, not about how it reached you.]`;

  return {
    _type: "dataagent_folded_result",
    result_ref: resultRef,
    tool_name: toolName,
    is_tabular: false,
    total_chars: totalChars,
    preview_head: previewHead,
    preview_tail: previewTail,
    notice,
  };
}

/**
 * Render a digest as the block the model actually reads.
 *
 * This used to be JSON.stringify, which left the model to dig the instructions
 * out of a `notice` field buried in an object — and in practice it re-ran the
 * query instead of following the ref. State the three things plainly: why the
 * output is not here, where it is, and how to get the part it wants.
 */
export function formatDigestText(digest: CompactResultDigest): string {
  // Derived rather than carried: saveToolResult writes every result to exactly
  // this path, so threading it through the digest would only create a second
  // place for it to be wrong.
  const storedPath = (ref: string) => `.dataagent/results/${ref}.json`;
  const lines: string[] = ["<persisted-output>"];

  if (digest.is_tabular) {
    lines.push(
      `Output too large to inline: ${digest.total_rows} rows.`,
      `Full result saved to: ${storedPath(digest.result_ref)}`
    );
  } else {
    lines.push(
      `Output too large to inline: ${digest.total_chars} characters.`,
      `Full output saved to: ${storedPath(digest.result_ref)}`
    );
  }

  lines.push(
    "",
    `Read a slice:  fetch_tool_result(result_ref="${digest.result_ref}", offset=0, limit=20)`,
    `Find rows:     search_result(result_ref="${digest.result_ref}", query="...")`,
    "",
    "Prefer either over re-running the tool that produced this — the full result is already saved.",
    "This block is internal plumbing: never mention it, the ref, or the size limit in your answer.",
    ""
  );

  if (digest.is_tabular) {
    const columns = digest.columns.map((c) => `${c.name} (${c.type})`).join(", ");
    lines.push(`Columns: ${columns}`, "");
    lines.push(`Preview (first ${digest.preview_head.length} of ${digest.total_rows} rows):`);
    for (const row of digest.preview_head) {
      lines.push(JSON.stringify(row));
    }
    if (digest.preview_tail?.length) {
      lines.push(`Preview (last ${digest.preview_tail.length} rows):`);
      for (const row of digest.preview_tail) {
        lines.push(JSON.stringify(row));
      }
    }
    if (digest.column_stats && Object.keys(digest.column_stats).length > 0) {
      lines.push("", `Column stats: ${JSON.stringify(digest.column_stats)}`);
    }
  } else {
    lines.push("Preview (start):", digest.preview_head);
    if (digest.preview_tail) {
      lines.push("Preview (end):", digest.preview_tail);
    }
  }

  lines.push("</persisted-output>");
  return lines.join("\n");
}

const MAX_CELL_CHARS = 200;

/** Cap every cell of a preview row so one wide value cannot blow up the digest. */
function capRowCells(row: Record<string, unknown>): Record<string, unknown> {
  if (!row || typeof row !== "object") {
    return row;
  }
  const capped: Record<string, unknown> = Object.create(null);
  for (const [key, value] of Object.entries(row)) {
    if (typeof value === "string" && value.length > MAX_CELL_CHARS) {
      capped[key] = `${value.slice(0, MAX_CELL_CHARS)}…[+${value.length - MAX_CELL_CHARS} chars, use fetch_tool_result]`;
    } else {
      capped[key] = value;
    }
  }
  return { ...capped };
}


/** Persistence ceiling for a structured payload, matching the query result guard. */
export const STRUCTURED_OUTPUT_MAX_BYTES = 512 * 1024;

/**
 * Whether this text is a platform structured output the UI renders directly.
 *
 * These carry a top-level `kind` (chart_spec, sql_execution, sql_export) and the
 * renderer keys off it. Folding one replaces it with a digest, and the chart or
 * table is gone from the transcript for good — no amount of unwrapping
 * downstream can recover it. So they are exempt from folding up to the
 * persistence ceiling, at the cost of the tokens they occupy.
 */
export function isRenderableStructuredOutput(text: string): boolean {
  const trimmed = text.trim();
  if (!trimmed.startsWith("{")) {
    return false;
  }
  try {
    const parsed = JSON.parse(trimmed);
    return Boolean(parsed && typeof parsed === "object" && typeof parsed.kind === "string");
  } catch {
    return false;
  }
}

/** Opening line of a rendered digest; also how other passes recognise one. */
export const PERSISTED_OUTPUT_OPEN = "<persisted-output>";

/**
 * Drop the preview rows from an already-rendered digest, keeping the part that
 * tells the model where the result is and how to read it.
 *
 * Used when a folded result scrolls into history: the samples earned their
 * tokens on the turn they arrived, not several turns later. Lives here so the
 * shape is known in one place — the pruner used to match on the old JSON layout
 * itself, which is what made changing that layout break it silently.
 */
export function stripRenderedPreview(text: string): string | null {
  if (!text.startsWith(PERSISTED_OUTPUT_OPEN)) {
    return null;
  }
  const lines = text.split("\n");
  const firstPreview = lines.findIndex((line) => line.startsWith("Preview ("));
  if (firstPreview < 0) {
    return null;
  }
  return [
    ...lines.slice(0, firstPreview),
    "Preview dropped: this result arrived in an earlier turn. Read it back with fetch_tool_result or search_result.",
    "</persisted-output>",
  ].join("\n");
}
