import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { saveToolResult, fetchToolResultSlice, searchToolResult } from "../src/context/result-store.js";

function tempWorkspace(): string {
  return path.join(os.tmpdir(), `pi-result-store-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`);
}

test("saveToolResult writes large tabular output to disk and returns valid metadata", async () => {
  const ws = tempWorkspace();
  await fs.mkdir(ws, { recursive: true });

  const rows = Array.from({ length: 50 }, (_, i) => ({
    order_id: i + 1,
    product: `item_${i + 1}`,
    price: (i + 1) * 10.5,
    status: i % 2 === 0 ? "PAID" : "PENDING",
  }));

  const outcome = await saveToolResult(ws, rows);
  assert.ok(outcome.result_ref.startsWith("res_"));
  assert.ok(outcome.byte_size > 500);

  const fileExists = await fs.stat(outcome.file_path).then(() => true).catch(() => false);
  assert.equal(fileExists, true);

  await fs.rm(ws, { recursive: true, force: true });
});

test("fetchToolResultSlice supports pagination and column projection", async () => {
  const ws = tempWorkspace();
  await fs.mkdir(ws, { recursive: true });

  const rows = Array.from({ length: 50 }, (_, i) => ({
    order_id: i + 1,
    product: `item_${i + 1}`,
    price: (i + 1) * 10.5,
    status: i % 2 === 0 ? "PAID" : "PENDING",
  }));

  const { result_ref } = await saveToolResult(ws, rows);

  // Page 1: default limit 20
  const page1 = await fetchToolResultSlice(ws, result_ref, { offset: 0, limit: 20 });
  assert.equal(page1.is_tabular, true);
  if (page1.is_tabular) {
    assert.equal(page1.total_rows, 50);
    assert.equal(page1.rows.length, 20);
    assert.equal(page1.rows[0].order_id, 1);
    assert.equal(page1.has_more, true);
  }

  // Page 2: offset 20, limit 20
  const page2 = await fetchToolResultSlice(ws, result_ref, { offset: 20, limit: 20 });
  assert.equal(page2.is_tabular, true);
  if (page2.is_tabular) {
    assert.equal(page2.rows.length, 20);
    assert.equal(page2.rows[0].order_id, 21);
    assert.equal(page2.has_more, true);
  }

  // Page 3: offset 40, limit 20 -> 10 rows remaining, has_more false
  const page3 = await fetchToolResultSlice(ws, result_ref, { offset: 40, limit: 20 });
  assert.equal(page3.is_tabular, true);
  if (page3.is_tabular) {
    assert.equal(page3.rows.length, 10);
    assert.equal(page3.has_more, false);
  }

  // Column projection
  const projected = await fetchToolResultSlice(ws, result_ref, {
    offset: 0,
    limit: 5,
    columns: ["order_id", "status"],
  });
  assert.equal(projected.is_tabular, true);
  if (projected.is_tabular) {
    assert.equal(projected.rows.length, 5);
    assert.deepEqual(Object.keys(projected.rows[0]), ["order_id", "status"]);
    assert.equal(projected.rows[0].order_id, 1);
  }

  await fs.rm(ws, { recursive: true, force: true });
});

test("fetchToolResultSlice rejects invalid result_ref to prevent directory traversal", async () => {
  const ws = tempWorkspace();
  await fs.mkdir(ws, { recursive: true });

  await assert.rejects(
    async () => {
      await fetchToolResultSlice(ws, "../../../etc/passwd");
    },
    /Invalid result_ref/
  );

  await fs.rm(ws, { recursive: true, force: true });
});

test("a symlink planted in results cannot read outside the workspace", async () => {
  // The ref pattern only blocks lexical traversal; a symlink inside the results
  // directory still resolves outward, so containment has to be checked against
  // real paths.
  const ws = tempWorkspace();
  const resultsDir = path.join(ws, ".dataagent", "results");
  await fs.mkdir(resultsDir, { recursive: true });
  const secret = path.join(ws, "..", `outside-${Date.now()}.json`);
  await fs.writeFile(secret, JSON.stringify({ SECRET: "outside" }));
  await fs.symlink(secret, path.join(resultsDir, "res_link.json"));

  await assert.rejects(
    () => fetchToolResultSlice(ws, "res_link", {}),
    /outside result storage/
  );

  await fs.rm(secret, { force: true });
  await fs.rm(ws, { recursive: true, force: true });
});

test("an existing result_ref is never silently overwritten", async () => {
  // Stored results are evidence a later turn may recall; replacing one on a
  // collision would rewrite history instead of failing loudly.
  const ws = tempWorkspace();
  const saved = await saveToolResult(ws, JSON.stringify([{ a: 1 }]));

  await assert.rejects(() => saveToolResult(ws, "different", saved.result_ref));

  const back = await fetchToolResultSlice(ws, saved.result_ref, {});
  assert.equal(back.is_tabular, true);
  await fs.rm(ws, { recursive: true, force: true });
});

test("tabular results are stored line-oriented and sliced without a full parse", async () => {
  const ws = tempWorkspace();
  const rows = Array.from({ length: 5_000 }, (_, i) => ({ id: i, name: `n${i}` }));
  const saved = await saveToolResult(ws, JSON.stringify(rows));

  // Stored layout: header line + one row per line.
  const onDisk = await fs.readFile(saved.file_path, "utf8");
  const lines = onDisk.split("\n");
  assert.equal(lines.length, rows.length + 1);
  assert.equal(JSON.parse(lines[0])._format, "dataagent_jsonl_v1");
  assert.equal(JSON.parse(lines[0]).total_rows, 5_000);

  const page = await fetchToolResultSlice(ws, saved.result_ref, { offset: 4_990, limit: 5 });
  assert.equal(page.is_tabular, true);
  assert.equal((page as any).total_rows, 5_000);
  assert.deepEqual(
    (page as any).rows.map((r: any) => r.id),
    [4990, 4991, 4992, 4993, 4994]
  );
  assert.equal(page.has_more, true);

  await fs.rm(ws, { recursive: true, force: true });
});

test("slicing reads only up to the requested window, not the whole file", async () => {
  // The point of the layout change: cost tracks offset+limit, not file size.
  // Compare bytes actually pulled off disk for an early page against a file
  // that is far larger than that page.
  const ws = tempWorkspace();
  const rows = Array.from({ length: 20_000 }, (_, i) => ({ id: i, blob: "x".repeat(200) }));
  const saved = await saveToolResult(ws, JSON.stringify(rows));

  const realRead = fs.readFile;
  let wholeFileReads = 0;
  (fs as any).readFile = async (...args: any[]) => {
    if (String(args[0]).includes(saved.result_ref)) wholeFileReads += 1;
    return (realRead as any)(...args);
  };
  try {
    const page = await fetchToolResultSlice(ws, saved.result_ref, { offset: 0, limit: 3 });
    assert.equal((page as any).rows.length, 3);
    assert.equal((page as any).total_rows, 20_000);
  } finally {
    (fs as any).readFile = realRead;
  }

  assert.equal(wholeFileReads, 0, "tabular slice must not fall back to reading the whole file");
  assert.ok(saved.byte_size > 4_000_000, "fixture should be large enough for this to matter");

  await fs.rm(ws, { recursive: true, force: true });
});

test("column projection still applies on the streamed path", async () => {
  const ws = tempWorkspace();
  const rows = Array.from({ length: 50 }, (_, i) => ({ id: i, keep: `k${i}`, drop: "unwanted" }));
  const saved = await saveToolResult(ws, JSON.stringify(rows));

  const page = await fetchToolResultSlice(ws, saved.result_ref, { offset: 10, limit: 2, columns: ["id", "keep"] });
  assert.deepEqual((page as any).rows, [
    { id: 10, keep: "k10" },
    { id: 11, keep: "k11" },
  ]);

  await fs.rm(ws, { recursive: true, force: true });
});

test("non-tabular text still round-trips through the whole-file path", async () => {
  const ws = tempWorkspace();
  const saved = await saveToolResult(ws, "plain log line\nanother line");
  const page = await fetchToolResultSlice(ws, saved.result_ref, {});
  assert.equal(page.is_tabular, false);
  assert.match((page as any).content, /plain log line/);
  await fs.rm(ws, { recursive: true, force: true });
});

test("search finds rows without paging or re-running the query", async () => {
  // Paging helps only when the agent knows where to look. When it does not, it
  // has re-run the query instead — one probe turn issued fifteen SQL calls
  // rather than read back what it had already fetched.
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "odw-search-"));

  const rows = Array.from({ length: 300 }, (_, i) => ({
    id: i,
    name: i === 217 ? "needle-workflow" : `filler-${i}`,
  }));
  const saved = await saveToolResult(root, JSON.stringify(rows));

  const found = await searchToolResult(root, saved.result_ref, "needle-workflow");
  assert.equal(found.match_count, 1);
  assert.equal(found.hits[0].index, 217, "the row index must let the agent page from there");
  assert.equal((found.hits[0].row as Record<string, unknown>).name, "needle-workflow");
  assert.equal(found.is_tabular, true);
  assert.equal(found.total_scanned, 300, "the header line is not data");
});

test("search reports that it capped rather than implying it found exactly the cap", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "odw-search2-"));

  const rows = Array.from({ length: 120 }, (_, i) => ({ id: i, tag: "common" }));
  const saved = await saveToolResult(root, JSON.stringify(rows));

  const found = await searchToolResult(root, saved.result_ref, "common", { limit: 5 });
  assert.equal(found.hits.length, 5);
  assert.equal(found.match_count, 120, "the real count must survive the cap");
  assert.equal(found.truncated, true);
});

test("an empty query is refused rather than matching everything", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "odw-search3-"));
  const saved = await saveToolResult(root, JSON.stringify([{ id: 1 }]));

  await assert.rejects(() => searchToolResult(root, saved.result_ref, "   "), /must not be empty/);
});
