/**
 * Resolve a StreamFn and Model for the provider the control plane selected.
 *
 * main.ts must construct the kernel *with* a resolved StreamFn. A kernel built
 * without one throws on the first run, which is a failure that only shows up in
 * production because every test injects its own stream function.
 *
 * Credentials are read from the process environment, never from the cell.init
 * payload, so they cannot appear in a protocol frame or a persisted record.
 */

import { anthropicMessagesApi } from "@earendil-works/pi-ai/api/anthropic-messages.lazy";
import { openAICompletionsApi } from "@earendil-works/pi-ai/api/openai-completions.lazy";
import type { Api, Context, Model, ProviderStreams, SimpleStreamOptions } from "@earendil-works/pi-ai";
import type { StreamFn } from "@earendil-works/pi-agent-core";

export interface ResolvedRuntimeModel {
  model: Model<Api>;
  streamFn: StreamFn;
}

interface ProviderProfile {
  api: Api;
  streams: () => ProviderStreams;
  defaultBaseUrl: string;
  apiKeyEnvVars: string[];
  baseUrlEnvVars: string[];
}

/**
 * Providers this Cell can drive. Anthropic-compatible and OpenAI-compatible
 * cover every provider DataAgent currently configures; a genuinely new API
 * shape belongs here as a new entry rather than as a special case elsewhere.
 */
const PROVIDER_PROFILES: Record<string, ProviderProfile> = {
  anthropic: {
    api: "anthropic-messages" as Api,
    streams: anthropicMessagesApi,
    defaultBaseUrl: "https://api.anthropic.com",
    apiKeyEnvVars: ["ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"],
    baseUrlEnvVars: ["ANTHROPIC_BASE_URL"],
  },
  anthropic_compatible: {
    api: "anthropic-messages" as Api,
    streams: anthropicMessagesApi,
    defaultBaseUrl: "https://api.anthropic.com",
    apiKeyEnvVars: ["ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"],
    baseUrlEnvVars: ["ANTHROPIC_BASE_URL"],
  },
  // The Python side always overwrites ANTHROPIC_BASE_URL/AUTH_TOKEN from the
  // selected provider before spawning this Cell (build_provider_env), so the
  // gateway needs no default URL of its own — only the Anthropic API shape.
  anyrouter: {
    api: "anthropic-messages" as Api,
    streams: anthropicMessagesApi,
    defaultBaseUrl: "https://api.anthropic.com",
    apiKeyEnvVars: ["ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY"],
    baseUrlEnvVars: ["ANTHROPIC_BASE_URL"],
  },
  openai: {
    api: "openai-completions" as Api,
    streams: openAICompletionsApi,
    defaultBaseUrl: "https://api.openai.com/v1",
    apiKeyEnvVars: ["OPENAI_API_KEY"],
    baseUrlEnvVars: ["OPENAI_BASE_URL"],
  },
  openai_compatible: {
    api: "openai-completions" as Api,
    streams: openAICompletionsApi,
    defaultBaseUrl: "https://api.openai.com/v1",
    apiKeyEnvVars: ["OPENAI_API_KEY", "OPENAI_COMPATIBLE_API_KEY"],
    baseUrlEnvVars: ["OPENAI_BASE_URL", "OPENAI_COMPATIBLE_BASE_URL"],
  },
};

export class ProviderNotSupportedError extends Error {}
export class ProviderCredentialsMissingError extends Error {}

function firstEnv(names: string[]): string | undefined {
  for (const name of names) {
    const value = process.env[name];
    if (typeof value === "string" && value.trim()) {
      return value.trim();
    }
  }
  return undefined;
}

export function resolveProviderProfile(providerId: string): ProviderProfile {
  const key = String(providerId || "").trim().toLowerCase();
  const profile = PROVIDER_PROFILES[key];
  if (!profile) {
    const supported = Object.keys(PROVIDER_PROFILES).join(", ");
    throw new ProviderNotSupportedError(
      `Pi 运行时不支持 provider '${providerId}'；当前支持：${supported}`
    );
  }
  return profile;
}

export function resolveModel(providerId: string, modelId: string): Model<Api> {
  const profile = resolveProviderProfile(providerId);
  const baseUrl = firstEnv(profile.baseUrlEnvVars) ?? profile.defaultBaseUrl;

  return {
    id: modelId,
    name: modelId,
    api: profile.api,
    provider: providerId,
    baseUrl,
    reasoning: false,
    input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 200_000,
    maxTokens: 8_192,
  } as unknown as Model<Api>;
}

export function resolveStreamFn(providerId: string, cacheRetention?: string): StreamFn {
  const profile = resolveProviderProfile(providerId);
  const apiKey = firstEnv(profile.apiKeyEnvVars);
  if (!apiKey) {
    throw new ProviderCredentialsMissingError(
      `provider '${providerId}' 缺少凭据：请在运行时环境提供 ${profile.apiKeyEnvVars.join(" 或 ")}`
    );
  }
  const streams = profile.streams();
  // streamSimple is the shape Agent's StreamFn contract expects; the api key is
  // injected here so it never travels through agent state or an event payload.
  //
  // cacheRetention has to be injected here too. pi-ai defaults it to "short"
  // when the option is absent, so declaring the setting anywhere else — an env
  // var, a field on the init frame — leaves caching on while the operator
  // believes it is off. That is exactly how DISABLE_PROMPT_CACHING became a
  // setting the Pi runtime never read.
  const retention = toPiCacheRetention(cacheRetention);
  return ((model: Model<Api>, context: Context, options?: SimpleStreamOptions) =>
    streams.streamSimple(model, context, {
      ...(options ?? {}),
      apiKey,
      ...(retention ? { cacheRetention: retention } : {}),
    })) as unknown as StreamFn;
}

/**
 * Translate the contract's value into pi-ai's.
 *
 * The contract says "off" because that is what an operator writes; pi-ai calls
 * the same thing "none". Passing "off" through would not disable anything —
 * resolveCacheRetention only recognises "none" and would fall back to "short".
 */
export function toPiCacheRetention(
  value: string | undefined
): "none" | "short" | "long" | undefined {
  switch (String(value ?? "").trim()) {
    case "off":
    case "none":
      return "none";
    case "short":
      return "short";
    case "long":
      return "long";
    default:
      return undefined;
  }
}

export function resolveRuntimeModel(
  providerId: string,
  modelId: string,
  cacheRetention?: string
): ResolvedRuntimeModel {
  return {
    model: resolveModel(providerId, modelId),
    streamFn: resolveStreamFn(providerId, cacheRetention),
  };
}
