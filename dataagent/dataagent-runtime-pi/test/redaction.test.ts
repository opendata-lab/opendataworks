import test from "node:test";
import assert from "node:assert/strict";
import { redactString } from "../src/observability/redaction.js";

test("credentials in DSNs, URLs and quoted JSON never pass through", () => {
  for (const text of [
    "connect mysql://reader:demo-pass@db.invalid/demo failed",
    "GET https://user:demo-pass@example.invalid/ refused",
    '{"password":"demo-pass","host":"db.invalid"}',
    '{"api_key": "demo-pass"}',
    "password=demo-pass",
  ]) {
    assert.doesNotMatch(redactString(text), /demo-pass/, text);
  }
  // What is not a secret stays readable.
  assert.match(redactString("mysql://reader:demo-pass@db.invalid/demo"), /mysql:\/\/reader:.*@db\.invalid\/demo/);
});
