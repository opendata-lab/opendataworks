import { probeMcpServer } from "./portal-mcp-client.js";
import type { McpServerConfig } from "../protocol/frames.js";

function shortError(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error || "MCP detection failed");
  return message.trim().slice(0, 500) || "MCP detection failed";
}

async function readStdin(): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function main(): Promise<void> {
  const startedAt = Date.now();
  try {
    const raw = await readStdin();
    const server = JSON.parse(raw) as McpServerConfig;
    if (!server || typeof server !== "object" || !String(server.name || "").trim()) {
      throw new Error("MCP server configuration is invalid");
    }
    const result = await probeMcpServer(server, { timeoutMs: 10_000 });
    process.stdout.write(JSON.stringify({
      status: "verified",
      message: `MCP 检测通过，发现 ${result.toolCount} 个工具`,
      tool_count: result.toolCount,
      tool_names: result.toolNames.slice(0, 100),
      latency_ms: result.latencyMs,
    }));
  } catch (error) {
    process.stdout.write(JSON.stringify({
      status: "failed",
      message: `MCP 检测失败: ${shortError(error)}`,
      tool_count: 0,
      tool_names: [],
      latency_ms: Math.max(0, Date.now() - startedAt),
    }));
  }
}

await main();
