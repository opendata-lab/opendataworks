import test from "node:test";
import assert from "node:assert/strict";
import {
  resolveApiFormatProfile,
  resolveModel,
  ApiFormatNotSupportedError,
} from "../src/providers/stream-fn-resolver.js";

test("resolveApiFormatProfile supports both configured API formats", () => {
  assert.equal(resolveApiFormatProfile("/v1/messages").api, "anthropic-messages");
  assert.equal(resolveApiFormatProfile("/v1/chat/completions").api, "openai-completions");
});

test("resolveApiFormatProfile rejects an unknown API format", () => {
  assert.throws(
    () => resolveApiFormatProfile("/v1/responses"),
    (err: unknown) => {
      assert.ok(err instanceof ApiFormatNotSupportedError);
      assert.match(err.message, /\/v1\/responses/);
      assert.match(err.message, /\/v1\/messages/);
      return true;
    }
  );
});

test("resolveModel accepts an arbitrary registry provider id and routes by api_format", () => {
  const previousAnthropicBase = process.env.ANTHROPIC_BASE_URL;
  const previousOpenAIBase = process.env.OPENAI_BASE_URL;
  process.env.ANTHROPIC_BASE_URL = "https://anthropic-gateway.example";
  process.env.OPENAI_BASE_URL = "https://openai-gateway.example/v1";
  try {
    const anthropicModel = resolveModel("custom_provider_1", "claude-x", "/v1/messages");
    assert.equal(anthropicModel.provider, "custom_provider_1");
    assert.equal(anthropicModel.api, "anthropic-messages");
    assert.equal(anthropicModel.baseUrl, "https://anthropic-gateway.example");

    const openAIModel = resolveModel("custom_provider_2", "gpt-x", "/v1/chat/completions");
    assert.equal(openAIModel.provider, "custom_provider_2");
    assert.equal(openAIModel.api, "openai-completions");
    assert.equal(openAIModel.baseUrl, "https://openai-gateway.example/v1");
  } finally {
    if (previousAnthropicBase === undefined) delete process.env.ANTHROPIC_BASE_URL;
    else process.env.ANTHROPIC_BASE_URL = previousAnthropicBase;
    if (previousOpenAIBase === undefined) delete process.env.OPENAI_BASE_URL;
    else process.env.OPENAI_BASE_URL = previousOpenAIBase;
  }
});

test("cache retention reaches the stream options, not just the init frame", async () => {
  const { toPiCacheRetention } = await import("../src/providers/stream-fn-resolver.js");
  assert.equal(toPiCacheRetention("off"), "none");
  assert.equal(toPiCacheRetention("none"), "none");
  assert.equal(toPiCacheRetention("short"), "short");
  assert.equal(toPiCacheRetention("long"), "long");
  assert.equal(toPiCacheRetention(undefined), undefined);
  assert.equal(toPiCacheRetention("nonsense"), undefined);
});
