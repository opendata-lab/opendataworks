import test from "node:test";
import assert from "node:assert/strict";
import { unwrapToolResult } from "../src/kernel/event-normalizer.js";

test("a pi tool result is split into SDK-shaped output and sibling meta", () => {
  // Emitting the whole {content, details} wrapper as `output` is what stopped
  // the frontend finding a platform `kind`, so charts and tables fell back to
  // raw JSON.
  const chart = JSON.stringify({ kind: "chart_spec", chart_type: "bar" });
  const { output, output_meta } = unwrapToolResult({
    content: [{ type: "text", text: chart }],
    details: { exitCode: 0, bytes: 128 },
  });

  assert.deepEqual(output, [{ type: "text", text: chart }]);
  assert.deepEqual(output_meta, { exit_code: 0, byte_count: 128 });
});

test("non-text blocks survive the unwrap", () => {
  const { output } = unwrapToolResult({
    content: [
      { type: "text", text: "see below" },
      { type: "image", data: "iVBOR", mimeType: "image/png" },
    ],
  });
  assert.equal((output as unknown[]).length, 2);
  assert.equal((output as Array<{ type: string }>)[1].type, "image");
});

test("unrecognised details are namespaced rather than dropped", () => {
  const { output_meta } = unwrapToolResult({
    content: [],
    details: { exitCode: 1, somethingNew: "keep me" },
  });
  assert.equal(output_meta?.exit_code, 1);
  assert.deepEqual(output_meta?.engine_details, { somethingNew: "keep me" });
});

test("a payload that is already plain passes through untouched", () => {
  // A platform object carrying `kind` at the top level must not be rewrapped.
  const platform = { kind: "sql_execution", rows: [{ a: 1 }] };
  const { output, output_meta } = unwrapToolResult(platform);
  assert.deepEqual(output, platform);
  assert.equal(output_meta, null);
});

test("a string result stays a string", () => {
  const { output, output_meta } = unwrapToolResult("plain text" as unknown);
  assert.equal(output, "plain text");
  assert.equal(output_meta, null);
});

test("no details means no output_meta key at all", () => {
  const { output_meta } = unwrapToolResult({ content: [{ type: "text", text: "x" }] });
  assert.equal(output_meta, null);
});
