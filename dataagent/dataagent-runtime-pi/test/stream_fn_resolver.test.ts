import test from "node:test";
import assert from "node:assert/strict";
import {
  resolveProviderProfile,
  resolveModel,
  ProviderNotSupportedError,
} from "../src/providers/stream-fn-resolver.js";

test("resolveProviderProfile resolves built-in providers including anyrouter", () => {
  const supported = [
    "anthropic",
    "anthropic_compatible",
    "anyrouter",
    "openai",
    "openai_compatible",
  ];
  for (const providerId of supported) {
    const profile = resolveProviderProfile(providerId);
    assert.ok(profile, `expected profile for ${providerId}`);
    assert.ok(profile.api, `expected api for ${providerId}`);
  }
});

test("resolveProviderProfile rejects unsupported provider", () => {
  assert.throws(
    () => resolveProviderProfile("unsupported_xyz"),
    (err: unknown) => {
      assert.ok(err instanceof ProviderNotSupportedError);
      assert.match(err.message, /unsupported_xyz/);
      assert.match(err.message, /anyrouter/);
      return true;
    }
  );
});

test("resolveModel builds model for anyrouter", () => {
  const anyrouterModel = resolveModel("anyrouter", "claude-opus-4-6");
  assert.equal(anyrouterModel.id, "claude-opus-4-6");
  assert.equal(anyrouterModel.provider, "anyrouter");
  assert.equal(anyrouterModel.api, "anthropic-messages");
});

// openrouter is deliberately absent: it needs Authorization: Bearer, which this
// resolver cannot express (it passes the token as apiKey -> x-api-key), and no
// authenticated end-to-end run has verified it. Register it only alongside a
// transport-level auth fix.
test("resolveProviderProfile rejects openrouter until Bearer auth is supported", () => {
  assert.throws(() => resolveProviderProfile("openrouter"), ProviderNotSupportedError);
});

test("cache retention reaches the stream options, not just the init frame", async () => {
  // The previous attempt declared cache_retention on the contract and never
  // passed it to pi-ai, which defaults to "short" whenever the option is
  // absent. Asserting the field exists on cell.init would have passed while
  // caching stayed on — the same failure as DISABLE_PROMPT_CACHING.
  const { toPiCacheRetention } = await import("../src/providers/stream-fn-resolver.js");

  // "off" is what an operator writes; pi-ai only recognises "none".
  assert.equal(toPiCacheRetention("off"), "none");
  assert.equal(toPiCacheRetention("none"), "none");
  assert.equal(toPiCacheRetention("short"), "short");
  assert.equal(toPiCacheRetention("long"), "long");
  // Unset must stay unset so pi-ai keeps its own default rather than being
  // handed an invalid string.
  assert.equal(toPiCacheRetention(undefined), undefined);
  assert.equal(toPiCacheRetention("nonsense"), undefined);
});
