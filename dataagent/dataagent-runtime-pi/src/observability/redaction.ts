/**
 * Redaction for anything that leaves this process as an event payload.
 *
 * Applied on the *live* path, not in a helper that only tests reach: tool
 * arguments and results are persisted to da_agent_sdk_record and streamed to
 * the browser, so an unredacted connection string or key would be durable and
 * visible, not merely logged.
 */

const SECRET_KEY_HINTS = ["password", "passwd", "secret", "token", "apikey", "api_key", "credential"];

const SECRET_VALUE_PATTERNS: RegExp[] = [
  /(password\s*[:=]\s*)[^\s,;'"]+/gi,
  /(token\s*[:=]\s*)[^\s,;'"]+/gi,
  /(api[_-]?key\s*[:=]\s*)[^\s,;'"]+/gi,
  /sk-[A-Za-z0-9_-]{16,}/g,
  /\bBearer\s+[A-Za-z0-9._-]{16,}/gi,
];

const REDACTED = "***REDACTED***";

export function redactString(text: string): string {
  let out = text;
  for (const pattern of SECRET_VALUE_PATTERNS) {
    out = out.replace(pattern, (match, prefix?: string) =>
      typeof prefix === "string" ? `${prefix}${REDACTED}` : REDACTED
    );
  }
  return out;
}

export function redact<T>(value: T, depth = 0): T {
  // Bound the walk: a deeply nested or cyclic tool result must not hang the run.
  if (depth > 12) {
    return REDACTED as unknown as T;
  }
  if (typeof value === "string") {
    return redactString(value) as unknown as T;
  }
  if (value === null || typeof value !== "object") {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map((item) => redact(item, depth + 1)) as unknown as T;
  }
  // Null-prototype accumulator: keys come from tool output, and assigning
  // `__proto__` to an ordinary object sets its prototype instead of storing
  // anything — redaction would drop the value on the floor. Spread restores an
  // ordinary object without running setters.
  const result: Record<string, unknown> = Object.create(null);
  for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
    const lowered = key.toLowerCase();
    result[key] = SECRET_KEY_HINTS.some((hint) => lowered.includes(hint)) ? REDACTED : redact(item, depth + 1);
  }
  return { ...result } as unknown as T;
}
