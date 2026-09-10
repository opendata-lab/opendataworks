import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { SSEClientTransport } from "@modelcontextprotocol/sdk/client/sse.js";
import { Type } from "@earendil-works/pi-ai";
import { logDiagnostic } from "../protocol/channel.js";
import type { McpServerConfig } from "../protocol/frames.js";

export interface McpBridgeResult {
  tools: unknown[];
  close: () => Promise<void>;
}

export function resolveRef(ref: string, rootSchema: Record<string, unknown>): Record<string, unknown> | undefined {
  if (!ref.startsWith("#/")) return undefined;
  const parts = ref.slice(2).split("/");
  let current: any = rootSchema;
  for (const part of parts) {
    if (!current || typeof current !== "object") return undefined;
    current = current[part];
  }
  return typeof current === "object" && current !== null ? current : undefined;
}

export function resolvePropertySchema(
  propSchema: Record<string, unknown>,
  rootSchema: Record<string, unknown>
): Record<string, unknown> {
  if (propSchema && typeof propSchema === "object" && typeof propSchema.$ref === "string") {
    const resolved = resolveRef(propSchema.$ref, rootSchema);
    if (resolved) {
      return resolvePropertySchema(resolved, rootSchema);
    }
  }
  return propSchema;
}

export function isObjectProperty(
  propSchema: Record<string, unknown>,
  rootSchema: Record<string, unknown>
): boolean {
  const resolved = resolvePropertySchema(propSchema, rootSchema);
  if (!resolved || typeof resolved !== "object") return false;
  if (resolved.type === "object") return true;
  if (resolved.properties && typeof resolved.properties === "object") return true;
  if (Array.isArray(resolved.type) && resolved.type.includes("object")) return true;
  return false;
}

export function formatSchemaHelp(
  toolName: string,
  propName: string,
  resolvedSchema: Record<string, unknown>
): string {
  const properties = (resolvedSchema.properties || {}) as Record<string, Record<string, unknown>>;
  const required = Array.isArray(resolvedSchema.required) ? (resolvedSchema.required as string[]) : [];

  const fieldLines: string[] = [];
  // Null-prototype: property names come from the MCP server's own schema, so a
  // field called `__proto__` would set this object's prototype and drop
  // silently out of the example the model is shown.
  const exampleObj: Record<string, unknown> = Object.create(null);

  for (const [key, def] of Object.entries(properties)) {
    let typeName = "any";
    if (typeof def.type === "string") {
      typeName = def.type;
    } else if (Array.isArray(def.anyOf)) {
      const nonNull = def.anyOf.map((t: any) => t?.type).filter((t: any) => t && t !== "null");
      typeName = nonNull.join("|") || "any";
    } else if (Array.isArray(def.type)) {
      typeName = def.type.filter((t: any) => t !== "null").join("|");
    }

    const isReq = required.includes(key);
    fieldLines.push(`  - ${key}: ${typeName}${isReq ? " (required)" : " (optional)"}`);

    if (isReq || Object.keys(exampleObj).length < 2) {
      if (typeName.includes("string")) {
        exampleObj[key] = key.includes("database") || key.includes("db")
          ? "dw_db"
          : key.includes("table")
          ? "dim_table"
          : key.includes("sql")
          ? "SELECT 1"
          : "value";
      } else if (typeName.includes("integer") || typeName.includes("number")) {
        exampleObj[key] = 1;
      } else if (typeName.includes("boolean")) {
        exampleObj[key] = true;
      } else if (typeName.includes("array")) {
        exampleObj[key] = [];
      } else {
        exampleObj[key] = {};
      }
    }
  }

  const exampleCall = JSON.stringify({ [propName]: exampleObj });
  return `Expected '${toolName}.${propName}' to be an object with fields:\n${fieldLines.join("\n")}\nCorrect call format: ${exampleCall}`;
}

export function prepareArguments(
  rawArgs: unknown,
  inputSchema: Record<string, unknown>,
  toolName: string
): Record<string, unknown> {
  if (rawArgs === null || typeof rawArgs !== "object" || Array.isArray(rawArgs)) {
    throw new Error(
      `Tool '${toolName}' arguments must be a JSON object, got ${rawArgs === null ? "null" : Array.isArray(rawArgs) ? "array" : typeof rawArgs}`
    );
  }

  const args = { ...(rawArgs as Record<string, unknown>) };
  const properties = (inputSchema.properties || {}) as Record<string, Record<string, unknown>>;
  const requiredProps = Array.isArray(inputSchema.required) ? (inputSchema.required as string[]) : [];

  for (const [propName, propDef] of Object.entries(properties)) {
    if (!isObjectProperty(propDef, inputSchema)) {
      continue;
    }

    const resolved = resolvePropertySchema(propDef, inputSchema);
    const value = args[propName];

    if (typeof value === "string") {
      let parsed: unknown;
      try {
        parsed = JSON.parse(value);
      } catch (err: unknown) {
        throw new Error(
          `Failed to parse '${toolName}.${propName}' as JSON string: ${err instanceof Error ? err.message : String(err)}\n${formatSchemaHelp(toolName, propName, resolved)}`
        );
      }

      if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error(
          `Parsed '${toolName}.${propName}' must be a non-null object, got ${parsed === null ? "null" : Array.isArray(parsed) ? "array" : typeof parsed}\n${formatSchemaHelp(toolName, propName, resolved)}`
        );
      }

      args[propName] = parsed;
    } else if (value !== undefined && value !== null) {
      if (typeof value !== "object" || Array.isArray(value)) {
        throw new Error(
          `Field '${toolName}.${propName}' must be a non-null object, got ${Array.isArray(value) ? "array" : typeof value}\n${formatSchemaHelp(toolName, propName, resolved)}`
        );
      }
    } else if (value === null && requiredProps.includes(propName)) {
      throw new Error(
        `Field '${toolName}.${propName}' cannot be null\n${formatSchemaHelp(toolName, propName, resolved)}`
      );
    }
  }

  return args;
}

export async function connectMcpServers(
  servers: McpServerConfig[] | undefined,
  options?: { connectTimeoutMs?: number }
): Promise<McpBridgeResult> {
  const allTools: unknown[] = [];
  const clients: Client[] = [];
  const timeoutMs = options?.connectTimeoutMs ?? 10_000;

  for (const server of servers || []) {
    if (!server.url) {
      continue;
    }

    try {
      const url = new URL(server.url);
      const headers = server.headers || {};

      let transport;
      if (server.type === "sse") {
        transport = new SSEClientTransport(url, {
          eventSourceInit: { headers } as never,
          requestInit: { headers },
        });
      } else {
        transport = new StreamableHTTPClientTransport(url, {
          requestInit: { headers },
        });
      }

      const client = new Client(
        { name: "opendataworks-pi-cell", version: "0.1.0" },
        { capabilities: {} }
      );

      // Connect with timeout
      await Promise.race([
        client.connect(transport),
        new Promise((_, reject) =>
          setTimeout(() => reject(new Error(`Connection to MCP server '${server.name}' timed out after ${timeoutMs}ms`)), timeoutMs)
        ),
      ]);

      clients.push(client);

      const toolsList = await client.listTools();
      logDiagnostic(`MCP server '${server.name}' connected, discovered ${toolsList.tools.length} tools`);

      for (const mcpTool of toolsList.tools) {
        const toolName = mcpTool.name;
        const toolDescription = mcpTool.description || "";
        const inputSchema = (mcpTool.inputSchema as Record<string, unknown>) || Type.Object({});

        allTools.push({
          name: toolName,
          label: toolName,
          description: toolDescription,
          parameters: inputSchema,
          prepareArguments: (args: unknown) => prepareArguments(args, inputSchema, toolName),
          execute: async (_toolCallId: string, params: Record<string, unknown>) => {
            const prepared = prepareArguments(params || {}, inputSchema, toolName);
            try {
              const res = await client.callTool({
                name: toolName,
                arguments: prepared,
              });

              const content = Array.isArray(res.content)
                ? res.content.map((item: any) => {
                    if (item.type === "text") {
                      return { type: "text" as const, text: String(item.text ?? "") };
                    }
                    if (item.type === "image") {
                      return {
                        type: "image" as const,
                        data: item.data,
                        mimeType: item.mimeType,
                      };
                    }
                    return { type: "text" as const, text: JSON.stringify(item) };
                  })
                : [{ type: "text" as const, text: String(res.content ?? "") }];

              if (res.isError) {
                const text = content
                  .map((item) => (item.type === "text" ? item.text : ""))
                  .filter(Boolean)
                  .join("\n");
                throw new Error(text || `MCP tool '${toolName}' call failed`);
              }

              return {
                content,
                isError: false,
              };
            } catch (err: unknown) {
              const errMsg = err instanceof Error ? err.message : String(err);
              logDiagnostic(`Tool '${toolName}' call failed: ${errMsg}`);
              throw err instanceof Error ? err : new Error(errMsg);
            }
          },
        });
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      logDiagnostic(`Failed to connect to MCP server '${server.name}' at ${server.url}: ${message}`);
    }
  }

  const close = async () => {
    for (const client of clients) {
      try {
        await client.close();
      } catch (err: unknown) {
        logDiagnostic(`Error closing MCP client: ${err instanceof Error ? err.message : String(err)}`);
      }
    }
  };

  return { tools: allTools, close };
}
