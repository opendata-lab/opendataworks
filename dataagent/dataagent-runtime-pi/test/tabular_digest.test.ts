import test from "node:test";
import assert from "node:assert/strict";
import {
  shouldFold,
  extractDigest,
  formatDigestText,
  isRenderableStructuredOutput,
  STRUCTURED_OUTPUT_MAX_BYTES,
} from "../src/context/tabular-digest.js";

test("shouldFold identifies payloads exceeding byte threshold", () => {
  const small = { rows: [{ id: 1, name: "Alice" }] };
  assert.equal(shouldFold(small, 1024), false);

  const large = { rows: Array.from({ length: 500 }, (_, i) => ({ id: i, data: "a".repeat(100) })) };
  assert.equal(shouldFold(large, 1024), true);
});

test("extractDigest extracts schema, sample rows, and numerical stats from tabular data", () => {
  const rows = Array.from({ length: 25 }, (_, i) => ({
    user_id: i + 1,
    username: `user_${i + 1}`,
    score: (i + 1) * 4.5,
    joined_date: "2026-09-01",
    notes: i % 5 === 0 ? null : "regular member",
  }));

  const digest = extractDigest(rows, {
    resultRef: "res_test_123",
    toolName: "portal_query_readonly",
  });

  assert.equal(digest._type, "dataagent_folded_result");
  assert.equal(digest.is_tabular, true);
  if (digest.is_tabular) {
    assert.equal(digest.total_rows, 25);
    assert.equal(digest.result_ref, "res_test_123");
    assert.equal(digest.tool_name, "portal_query_readonly");

    // Columns schema
    const colNames = digest.columns.map((c) => c.name);
    assert.ok(colNames.includes("user_id"));
    assert.ok(colNames.includes("username"));
    assert.ok(colNames.includes("score"));

    // Previews
    assert.equal(digest.preview_head.length, 5);
    assert.equal(digest.preview_head[0].user_id, 1);
    assert.ok(digest.preview_tail);
    assert.equal(digest.preview_tail.length, 5);
    assert.equal(digest.preview_tail[digest.preview_tail.length - 1].user_id, 25);

    // Stats
    const scoreStats = digest.column_stats?.score;
    assert.ok(scoreStats);
    assert.equal(scoreStats.min, 4.5);
    assert.equal(scoreStats.null_count, 0);

    const notesStats = digest.column_stats?.notes;
    assert.ok(notesStats);
    assert.ok(notesStats.null_count > 0);

    // Notice mentions 25 rows and warns not to assume 10 rows
    assert.match(digest.notice, /25 rows/);
    assert.match(digest.notice, /Do NOT assume/);
    assert.match(digest.notice, /fetch_tool_result/);
  }

  const jsonStr = formatDigestText(digest);
  assert.ok(jsonStr.includes("dataagent_folded_result"));
  assert.ok(jsonStr.length < 5000); // Compact representation remains well bounded
});

test("extractDigest handles non-tabular text gracefully", () => {
  const longText = "Log line " + "x".repeat(5000) + " end of log";
  const digest = extractDigest(longText, {
    resultRef: "res_log_456",
    toolName: "Bash",
  });

  assert.equal(digest.is_tabular, false);
  if (!digest.is_tabular) {
    assert.ok(digest.total_chars > 5000);
    assert.ok(digest.preview_head.length <= 1200);
    assert.match(digest.notice, /ResultStore/);
  }
});

test("schema discovery finds a column that only appears in a late row", () => {
  // A 10-row sample used to drop such columns entirely, so the model never
  // learned it could query them.
  const rows: Record<string, unknown>[] = [];
  for (let i = 0; i < 60; i += 1) {
    rows.push(i === 50 ? { id: i, late_column: "surprise" } : { id: i });
  }

  const digest = extractDigest(JSON.stringify(rows), { resultRef: "res_x", toolName: "run_sql" }) as any;
  const names = digest.columns.map((c: any) => c.name);
  assert.ok(names.includes("late_column"), `late_column missing from ${JSON.stringify(names)}`);
});

test("column stats cover every row, not a 100-row prefix", () => {
  const rows = Array.from({ length: 101 }, (_, i) => ({ n: i === 100 ? 99999 : i % 100 }));
  const digest = extractDigest(JSON.stringify(rows), { resultRef: "res_y", toolName: "run_sql" }) as any;
  assert.equal(digest.column_stats.n.max, 99999);
});

test("a wide cell cannot make the digest larger than the original", () => {
  const raw = JSON.stringify([{ note: "数".repeat(20_000) }]);
  const digest = extractDigest(raw, { resultRef: "res_z", toolName: "run_sql" });
  const digestBytes = Buffer.byteLength(formatDigestText(digest), "utf8");
  assert.ok(
    digestBytes < Buffer.byteLength(raw, "utf8"),
    `digest ${digestBytes}B should be smaller than original ${Buffer.byteLength(raw, "utf8")}B`
  );
});

test("a large chart_spec is exempt from folding so it can still render", () => {
  // Folding replaces the payload with a digest, and the chart is gone from the
  // transcript for good. A 37,845-byte chart_spec — the size review reproduced
  // — is far past the 16 KiB fold threshold but well inside the persistence
  // ceiling, so it must survive intact.
  const spec = {
    kind: "chart_spec",
    version: 1,
    chart_type: "bar",
    columns: ["name", "value"],
    dataset: Array.from({ length: 900 }, (_, i) => ({ name: `项目-${i}`, value: i })),
    error: null,
  };
  const text = JSON.stringify(spec);
  assert.ok(Buffer.byteLength(text, "utf8") > 16 * 1024, "fixture must exceed the fold threshold");
  assert.ok(Buffer.byteLength(text, "utf8") < STRUCTURED_OUTPUT_MAX_BYTES);
  assert.equal(isRenderableStructuredOutput(text), true);
});

test("ordinary large output is not mistaken for structured output", () => {
  // Only a top-level `kind` marks a payload the renderer can draw; without it,
  // folding is still the right call.
  assert.equal(isRenderableStructuredOutput("数".repeat(20_000)), false);
  assert.equal(isRenderableStructuredOutput(JSON.stringify({ rows: [1, 2, 3] })), false);
  assert.equal(isRenderableStructuredOutput(JSON.stringify([{ kind: "chart_spec" }])), false);
  assert.equal(isRenderableStructuredOutput("not json at all"), false);
});

test("a column named like a prototype member is not dropped from its own digest", () => {
  // Column names come from query results. Writing `__proto__` into an ordinary
  // accumulator set its prototype instead of storing anything, so the digest
  // listed the column and then omitted it from both stats and preview — it
  // described data the model was never shown.
  const rows = JSON.parse('[{"__proto__": 7, "x": 2}, {"__proto__": 8, "x": 3}]');
  const digest = extractDigest(JSON.stringify(rows), { resultRef: "r", toolName: "t" });
  assert.equal(digest.is_tabular, true);
  if (!digest.is_tabular) return;

  const declared = digest.columns.map((c) => c.name);
  assert.ok(declared.includes("__proto__"));
  // Every column it declares, it must also describe.
  for (const name of declared) {
    assert.ok(
      Object.hasOwn(digest.column_stats ?? {}, name),
      `${name} is declared but has no stats`
    );
  }
  const first = digest.preview_head![0];
  assert.equal(Object.getOwnPropertyDescriptor(first, "__proto__")?.value, 7);
  assert.equal(Object.getPrototypeOf(first), Object.prototype);
  assert.equal(({} as Record<string, unknown>).x, undefined);
});

test("the digest tells the model to keep the folding to itself", () => {
  // The notice explains why it is showing a sample, and the model repeated that
  // explanation to the user: a real answer ended with "此前工作流全量与执行日志明细
  // 结果因上下文限制被折叠". Internal plumbing reached the transcript because
  // nothing told the model it was internal.
  const rows = Array.from({ length: 400 }, (_, i) => ({ id: i, name: `n-${i}` }));
  const tabular = extractDigest(JSON.stringify(rows), { resultRef: "r1", toolName: "t" });
  const text = extractDigest("x".repeat(50_000), { resultRef: "r2", toolName: "t" });

  for (const digest of [tabular, text]) {
    const notice = digest.notice;
    // Still has to say what it needs the model to do.
    assert.match(notice, /fetch_tool_result/);
    // And that this is not for the reader.
    assert.match(notice, /never mention it/);
  }
});
