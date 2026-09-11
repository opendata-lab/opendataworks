/**
 * Canonical tools exposed to the agent.
 *
 * Two properties are enforced here rather than left to the model:
 *
 * 1. Every path argument passes the workspace boundary before the tool runs.
 * 2. A spawned shell gets an *allowlisted* environment, never a copy of this
 *    process's env. The Cell's environment carries provider API keys, the
 *    database password and other runtime secrets; handing all of that to a
 *    model-driven `bash -c` would make every one of them readable with `env`.
 */

import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import { Type } from "@earendil-works/pi-ai";
import type { AgentTool } from "@earendil-works/pi-agent-core";
import type { WorkspaceBoundaryEnforcer } from "../policy/workspace-boundary-enforcer.js";
import { createSkillTool, type SkillEntry } from "../skills/skill-loader.js";
import { createFetchToolResultTool, createSearchToolResultTool } from "./fetch-tool-result.js";

const MAX_OUTPUT_CHARS = 100 * 1024;
const MAX_READ_BYTES = 64 * 1024;
const DEFAULT_TOOL_TIMEOUT_MS = 120_000;

/**
 * Environment variables a skill script legitimately needs. Everything else in
 * the Cell's env — provider keys, DB credentials, the runtime secret — stays
 * out of the child.
 */
export const SHELL_ENV_ALLOWLIST = [
  // OS & runtime basics
  "PATH",
  "HOME",
  "LANG",
  "LC_ALL",
  "TZ",
  "PWD",
  "TMPDIR",
  "VIRTUAL_ENV",

  // Skill paths and active folders
  "DATAAGENT_PYTHON_BIN",
  "DATAAGENT_SKILL_ROOT",
  "DATAAGENT_PLATFORM_SKILL_ROOT",
  "DATAAGENT_ENABLED_SKILLS",
  "DATAAGENT_ENABLED_SKILL_ROOTS",

  // Query & task runtime parameters
  "DATAAGENT_QUERY_LIMIT",
  "DATAAGENT_RESULT_PREVIEW_ROWS",
  "DATAAGENT_ORIGINAL_QUESTION",
  "DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK",
  "DATAAGENT_SQL_READ_TIMEOUT_SECONDS",
  "DATAAGENT_SQL_WRITE_TIMEOUT_SECONDS",

  // Data scope headers and definitions
  "ODW_AGENT_DATA_SCOPE_HEADER",
  "DATAAGENT_DATA_SCOPE_HEADER",
  "DATAAGENT_DATA_SCOPE_JSON",

  // Backend agent API access channel
  "ODW_BACKEND_BASE_URL",
  "ODW_AGENT_SERVICE_TOKEN",
  "ODW_AGENT_SERVICE_TOKEN_HEADER_NAME",
  "ODW_BACKEND_TIMEOUT_SECONDS",
];

export interface ToolRegistryOptions {
  boundary: WorkspaceBoundaryEnforcer;
  workspaceRoot: string;
  runtimeEnv: Record<string, string>;
  toolTimeoutMs?: number;
  skills?: SkillEntry[];
  extraTools?: unknown[];
}

/** A tool call refused by the workspace boundary policy. */
export class ToolDeniedError extends Error {
  constructor(reason: string) {
    super(reason);
    this.name = "ToolDeniedError";
  }
}

export interface ShellResult {
  exitCode: number | null;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}

export function buildShellEnv(
  processEnv: NodeJS.ProcessEnv,
  runtimeEnv: Record<string, string>
): Record<string, string> {
  const env: Record<string, string> = {};
  for (const key of SHELL_ENV_ALLOWLIST) {
    const value = processEnv[key];
    if (typeof value === "string") {
      env[key] = value;
    }
  }
  // Values the control plane explicitly designated for skill scripts. These are
  // additive on purpose; they are the one channel for run-scoped configuration.
  for (const [key, value] of Object.entries(runtimeEnv)) {
    if (SHELL_ENV_ALLOWLIST.includes(key)) {
      env[key] = value;
    }
  }
  return env;
}

export async function runShell(
  command: string,
  options: { cwd: string; env: Record<string, string>; timeoutMs: number; signal?: AbortSignal }
): Promise<ShellResult> {
  return new Promise<ShellResult>((resolve, reject) => {
    // A pipeline must fail when any stage fails. Without pipefail, commands such
    // as `python missing.py | head` inherit head's zero exit code and are
    // incorrectly reported to the agent as successful tool calls.
    const child = spawn("bash", ["-o", "pipefail", "-c", command], {
      cwd: options.cwd,
      env: options.env,
    });

    let stdout = "";
    let stderr = "";
    let timedOut = false;

    child.stdout.on("data", (chunk: Buffer) => {
      if (stdout.length < MAX_OUTPUT_CHARS) {
        stdout += chunk.toString("utf8");
      }
    });
    child.stderr.on("data", (chunk: Buffer) => {
      if (stderr.length < MAX_OUTPUT_CHARS) {
        stderr += chunk.toString("utf8");
      }
    });

    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, options.timeoutMs);

    const onAbort = () => child.kill("SIGTERM");
    options.signal?.addEventListener("abort", onAbort, { once: true });

    child.on("close", (code) => {
      clearTimeout(timer);
      options.signal?.removeEventListener("abort", onAbort);
      resolve({ exitCode: code, stdout, stderr, timedOut });
    });
    child.on("error", (err) => {
      clearTimeout(timer);
      options.signal?.removeEventListener("abort", onAbort);
      reject(err);
    });
  });
}

/**
 * Returns real AgentTool values rather than unknown[]. The looser type used to
 * force an `as never` cast at the call site, which meant nothing checked that
 * these definitions actually match what the Agent expects — and no test
 * exercises a tool call through the real agent loop either.
 */
// Schemas are named so each tool can be typed as AgentTool<typeof schema>,
// which makes `params` concrete inside execute instead of unknown.
const READ_SCHEMA = Type.Object({
  file_path: Type.String({ description: "Absolute or workspace-relative file path." }),
});
const LS_SCHEMA = Type.Object({
  path: Type.String({ description: "Absolute or workspace-relative directory path." }),
});
const BASH_SCHEMA = Type.Object({
  command: Type.String({ description: "The bash command line to execute." }),
});

export function createTools(options: ToolRegistryOptions): AgentTool<any>[] {
  const { boundary, workspaceRoot, runtimeEnv } = options;
  const timeoutMs = options.toolTimeoutMs ?? DEFAULT_TOOL_TIMEOUT_MS;

  const readTool: AgentTool<typeof READ_SCHEMA> = {
    name: "Read",
    label: "Read",
    description: "Read a UTF-8 text file inside the agent workspace.",
    parameters: READ_SCHEMA,
    execute: async (_toolCallId, params) => {
      const reason = boundary.validate("Read", params);
      if (reason) {
        throw new ToolDeniedError(reason);
      }
      const resolved = path.resolve(workspaceRoot, params.file_path);
      const stat = await fs.stat(resolved);
      if (stat.isDirectory()) {
        throw new Error(`Cannot read '${params.file_path}': it is a directory`);
      }
      const handle = await fs.open(resolved, "r");
      try {
        const size = Math.min(stat.size, MAX_READ_BYTES);
        const buffer = Buffer.alloc(size);
        const { bytesRead } = await handle.read(buffer, 0, size, 0);
        const text = buffer.subarray(0, bytesRead).toString("utf8");
        return {
          content: [{ type: "text" as const, text }],
          details: { truncated: stat.size > MAX_READ_BYTES, bytes: bytesRead },
        };
      } finally {
        await handle.close();
      }
    },
  };

  const lsTool: AgentTool<typeof LS_SCHEMA> = {
    name: "LS",
    label: "LS",
    description: "List a directory inside the agent workspace.",
    parameters: LS_SCHEMA,
    execute: async (_toolCallId, params) => {
      const reason = boundary.validate("LS", params);
      if (reason) {
        throw new ToolDeniedError(reason);
      }
      const resolved = path.resolve(workspaceRoot, params.path);
      const entries = await fs.readdir(resolved, { withFileTypes: true });
      const text = entries
        .map((entry) => `${entry.isDirectory() ? "[DIR] " : "[FILE]"} ${entry.name}`)
        .join("\n");
      return {
        content: [{ type: "text" as const, text: text || "(empty)" }],
        details: { count: entries.length },
      };
    },
  };

  const bashTool: AgentTool<typeof BASH_SCHEMA> = {
    name: "Bash",
    label: "Bash",
    description: "Run a bash command inside the agent workspace.",
    parameters: BASH_SCHEMA,
    execute: async (_toolCallId, params, signal) => {
      const reason = boundary.validate("Bash", params);
      if (reason) {
        throw new ToolDeniedError(reason);
      }
      const result = await runShell(params.command, {
        cwd: workspaceRoot,
        env: buildShellEnv(process.env, runtimeEnv),
        timeoutMs,
        signal,
      });
      if (result.timedOut) {
        throw new Error(`Bash command exceeded ${Math.round(timeoutMs / 1000)}s and was killed`);
      }
      const output = (result.stdout + (result.stderr ? `\n${result.stderr}` : "")).trim();
      if (result.exitCode !== 0) {
        // Carry the output in the message: createErrorToolResult keeps only the
        // error text, so returning it normally would lose the very output the
        // model needs in order to understand the failure.
        throw new Error(
          `Bash command exited with code ${String(result.exitCode)}\n${output || "(no output)"}`
        );
      }
      return {
        content: [{ type: "text" as const, text: output || "(no output)" }],
        details: { exitCode: result.exitCode },
      };
    },
  };

  const tools: AgentTool<any>[] = [
    readTool,
    lsTool,
    bashTool,
    createFetchToolResultTool(workspaceRoot) as never,
    createSearchToolResultTool(workspaceRoot) as never,
  ];

  if (options.skills && options.skills.length > 0) {
    tools.push(createSkillTool(options.skills) as never);
  }

  if (options.extraTools && options.extraTools.length > 0) {
    tools.push(...(options.extraTools as AgentTool<any>[]));
  }

  return tools;
}
