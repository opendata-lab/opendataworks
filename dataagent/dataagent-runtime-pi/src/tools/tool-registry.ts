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
  "SKILLS_ROOT_DIR",
  "DATAAGENT_ENABLED_SKILLS",
  "DATAAGENT_ENABLED_SKILL_ROOTS",

  // Query & task runtime parameters
  "DATAAGENT_QUERY_LIMIT",
  "DATAAGENT_RESULT_PREVIEW_ROWS",
  "DATAAGENT_ORIGINAL_QUESTION",
  "DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK",
  "DATAAGENT_SQL_READ_TIMEOUT_SECONDS",
  "DATAAGENT_SQL_WRITE_TIMEOUT_SECONDS",

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
  toolTimeoutMs: number;
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
  /** Bytes emitted after the per-stream capture limits had been reached. */
  droppedBytes: number;
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

/** stat, or an error saying what is wrong with the path, relative as the model wrote it. */
async function statOrExplain(resolved: string, shown: string) {
  try {
    return await fs.stat(resolved);
  } catch (err) {
    const code = (err as NodeJS.ErrnoException).code;
    if (code === "ENOENT") {
      const parent = path.dirname(shown) || ".";
      throw new Error(
        `错误 [FILE_NOT_FOUND]：${shown} 不存在（相对路径以工作区根目录为准）。未读取任何内容。` +
          `下一步：LS(${JSON.stringify({ path: parent })}) 查看现有文件`
      );
    }
    if (code === "EACCES") {
      throw new Error(`错误 [READ_DENIED]：没有权限读取 ${shown}。未读取任何内容；同参重试无效。`);
    }
    throw new Error(`错误 [READ_FAILED]：读取 ${shown} 失败（${code ?? "未知原因"}）。未读取任何内容。`);
  }
}

export async function runShell(
  command: string,
  options: { cwd: string; env: Record<string, string>; timeoutMs: number; signal?: AbortSignal }
): Promise<ShellResult> {
  if (options.signal?.aborted) {
    throw new Error("Bash command aborted before start");
  }
  return new Promise<ShellResult>((resolve, reject) => {
    // A pipeline must fail when any stage fails. Without pipefail, commands such
    // as `python missing.py | head` inherit head's zero exit code and are
    // incorrectly reported to the agent as successful tool calls.
    const child = spawn("bash", ["-o", "pipefail", "-c", command], {
      cwd: options.cwd,
      env: options.env,
      // Linux containers and local macOS use a POSIX process group. Killing
      // only bash leaves pipeline/background children running with open pipes.
      detached: true,
    });

    const stdoutChunks: Buffer[] = [];
    const stderrChunks: Buffer[] = [];
    let stdoutBytes = 0;
    let stderrBytes = 0;
    let droppedBytes = 0;
    let timedOut = false;

    child.stdout.on("data", (chunk: Buffer) => {
      const remaining = Math.max(0, MAX_OUTPUT_CHARS - stdoutBytes);
      if (remaining > 0) {
        const captured = chunk.subarray(0, remaining);
        stdoutChunks.push(captured);
        stdoutBytes += captured.length;
      }
      droppedBytes += Math.max(0, chunk.length - remaining);
    });
    child.stderr.on("data", (chunk: Buffer) => {
      const remaining = Math.max(0, MAX_OUTPUT_CHARS - stderrBytes);
      if (remaining > 0) {
        const captured = chunk.subarray(0, remaining);
        stderrChunks.push(captured);
        stderrBytes += captured.length;
      }
      droppedBytes += Math.max(0, chunk.length - remaining);
    });

    const terminate = () => {
      if (child.pid === undefined) return;
      try {
        process.kill(-child.pid, "SIGKILL");
      } catch (err) {
        if ((err as NodeJS.ErrnoException).code !== "ESRCH") reject(err);
      }
    };
    const timer = setTimeout(() => {
      timedOut = true;
      terminate();
    }, options.timeoutMs);

    const onAbort = terminate;
    options.signal?.addEventListener("abort", onAbort, { once: true });

    child.on("close", (code) => {
      clearTimeout(timer);
      options.signal?.removeEventListener("abort", onAbort);
      resolve({
        exitCode: code,
        stdout: Buffer.concat(stdoutChunks).toString("utf8"),
        stderr: Buffer.concat(stderrChunks).toString("utf8"),
        timedOut,
        droppedBytes,
      });
    });
    child.on("error", (err) => {
      clearTimeout(timer);
      options.signal?.removeEventListener("abort", onAbort);
      reject(err);
    });
    // Cover cancellation between the initial check and listener registration.
    if (options.signal?.aborted) onAbort();
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
  file_path: Type.String({
    description: "Absolute path, or a path relative to the workspace root (never to an earlier cd).",
  }),
  offset: Type.Optional(
    Type.Integer({
      description: "Byte offset to start reading from. Defaults to 0.",
      default: 0,
      minimum: 0,
    })
  ),
  limit: Type.Optional(
    Type.Integer({
      description: `Maximum bytes to return (default and max ${MAX_READ_BYTES}).`,
      default: MAX_READ_BYTES,
      minimum: 1,
      maximum: MAX_READ_BYTES,
    })
  ),
});
const LS_SCHEMA = Type.Object({
  path: Type.String({
    description: "Absolute path, or a path relative to the workspace root (never to an earlier cd).",
  }),
});
const BASH_SCHEMA = Type.Object({
  command: Type.String({ description: "The bash command line to execute." }),
});

export function createTools(options: ToolRegistryOptions): AgentTool<any>[] {
  const { boundary, workspaceRoot, runtimeEnv } = options;
  const timeoutMs = options.toolTimeoutMs;
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new Error("Bash timeout must be a positive number of milliseconds");
  }

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
      const stat = await statOrExplain(resolved, params.file_path);
      if (stat.isDirectory()) {
        throw new Error(
          `错误 [IS_DIRECTORY]：${params.file_path} 是目录，不能用 Read 读取。未读取任何内容。` +
            `下一步：LS(${JSON.stringify({ path: params.file_path })})`
        );
      }
      const offset = params.offset ?? 0;
      const limit = Math.min(params.limit ?? MAX_READ_BYTES, MAX_READ_BYTES);
      const handle = await fs.open(resolved, "r");
      try {
        const size = Math.min(Math.max(0, stat.size - offset), limit);
        const buffer = Buffer.alloc(size);
        const { bytesRead } =
          size > 0 ? await handle.read(buffer, 0, size, offset) : { bytesRead: 0 };
        const end = offset + bytesRead;
        const truncated = end < stat.size;
        const text = buffer.subarray(0, bytesRead).toString("utf8");
        const visibleText = truncated
          ? `${text}\n\n[Read truncated: returned byte range [${offset}, ${end}) of ${stat.size} total bytes. ` +
            `Do not treat this as the complete file. Continue with Read(file_path=${JSON.stringify(params.file_path)}, offset=${end}).]`
          : text;
        return {
          content: [{ type: "text" as const, text: visibleText }],
          details: {
            truncated,
            bytes: bytesRead,
            offset,
            total_bytes: stat.size,
          },
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
      const stat = await statOrExplain(resolved, params.path);
      if (!stat.isDirectory()) {
        throw new Error(
          `错误 [NOT_A_DIRECTORY]：${params.path} 是文件，不是目录。未列出任何内容。` +
            `下一步：Read(${JSON.stringify({ file_path: params.path })})`
        );
      }
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
    description:
      "Run a bash command inside the agent workspace. Every call starts in the workspace root: " +
      "a cd lasts only within that one command, so write paths relative to the workspace root.",
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
        // Whatever the command printed before it was killed is often the only
        // clue to why it hung; keep it rather than throwing it away.
        const partial = (result.stdout + (result.stderr ? `\n${result.stderr}` : "")).trim();
        throw new Error(
          `错误 [TOOL_TIMEOUT]：命令运行超过 ${timeoutMs} 毫秒，整个进程组已终止。` +
            "命令可能只完成了一部分，有副作用的命令不要直接重跑；只读命令可缩小范围后再执行。" +
            (partial ? `\n终止前的输出：\n${partial}` : "\n终止前没有输出。")
        );
      }
      const output = (result.stdout + (result.stderr ? `\n${result.stderr}` : "")).trim();
      const truncationNotice =
        result.droppedBytes > 0
          ? `\n\n提示 [OUTPUT_TRUNCATED]：标准输出与标准错误各最多保留 ${MAX_OUTPUT_CHARS} 字节，` +
            `另有 ${result.droppedBytes} 字节已丢弃、未保存、无法取回，上面的输出不完整。` +
            "下一步：只读命令可把输出写到文件再分段读，例如 `<原命令> > command-output.log 2>&1`，" +
            "然后 Read 该文件；有副作用的命令不要为取输出而重跑。"
          : "";
      const visibleOutput = `${output || "(no output)"}${truncationNotice}`;
      if (result.exitCode !== 0) {
        // Carry the output in the message: createErrorToolResult keeps only the
        // error text, so returning it normally would lose the very output the
        // model needs in order to understand the failure.
        throw new Error(
          (result.exitCode === null
            ? "错误 [TOOL_KILLED]：命令被信号终止，未正常结束。"
            : `错误 [EXIT_${String(result.exitCode)}]：命令以退出码 ${String(result.exitCode)} 结束，下面是它的输出。`) +
            `\n${visibleOutput}`
        );
      }
      return {
        content: [{ type: "text" as const, text: visibleOutput }],
        details: { exitCode: result.exitCode, droppedBytes: result.droppedBytes },
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
