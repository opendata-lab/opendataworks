import test from "node:test";
import assert from "node:assert/strict";
import { connectMcpServers, createMcpTransport } from "../src/mcp/portal-mcp-client.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

test("connectMcpServers returns empty tools when servers list is empty or undefined", async () => {
  const result1 = await connectMcpServers(undefined);
  assert.deepEqual(result1.tools, []);
  await result1.close();

  const result2 = await connectMcpServers([]);
  assert.deepEqual(result2.tools, []);
  await result2.close();
});

test("connectMcpServers handles unreachable server gracefully without crashing", async () => {
  const result = await connectMcpServers(
    [
      {
        name: "portal",
        url: "http://127.0.0.1:59999/mcp/",
        type: "http",
        headers: { "X-Portal-MCP-Token": "fake" },
      },
    ],
    { connectTimeoutMs: 500 }
  );

  assert.deepEqual(result.tools, []);
  await result.close();
});

test("createMcpTransport builds stdio transport from persisted command contract", () => {
  const transport = createMcpTransport({
    name: "filesystem",
    type: "stdio",
    command: "npx",
    args: ["-y", "@modelcontextprotocol/server-filesystem", "/data"],
    env: { DIR_ROOT: "/data" },
  });

  assert.ok(transport instanceof StdioClientTransport);
});

test("createMcpTransport rejects incomplete stdio config", () => {
  assert.throws(
    () => createMcpTransport({ name: "broken", type: "stdio" }),
    /missing command/
  );
});

import {
  prepareArguments,
  resolveRef,
  resolvePropertySchema,
  isObjectProperty,
  formatSchemaHelp,
} from "../src/mcp/portal-mcp-client.js";

const SAMPLE_SCHEMA = {
  $defs: {
    TableDdlInput: {
      type: "object",
      properties: {
        database: { type: "string", description: "数据库名" },
        table: { type: "string", description: "表名" },
        table_id: { type: "integer", description: "表 ID" },
      },
      required: ["database", "table"],
    },
  },
  properties: {
    params: {
      $ref: "#/$defs/TableDdlInput",
    },
    extra_flag: {
      type: "boolean",
    },
  },
  required: ["params"],
  type: "object",
};

test("resolveRef and resolvePropertySchema resolve local $defs correctly", () => {
  const resolved = resolveRef("#/$defs/TableDdlInput", SAMPLE_SCHEMA);
  assert.ok(resolved);
  assert.equal(resolved.type, "object");
  assert.deepEqual(resolved.required, ["database", "table"]);

  const propResolved = resolvePropertySchema(SAMPLE_SCHEMA.properties.params, SAMPLE_SCHEMA);
  assert.equal(propResolved.type, "object");

  assert.equal(isObjectProperty(SAMPLE_SCHEMA.properties.params, SAMPLE_SCHEMA), true);
  assert.equal(isObjectProperty(SAMPLE_SCHEMA.properties.extra_flag, SAMPLE_SCHEMA), false);
});

test("prepareArguments preserves native object arguments (Claude / native format)", () => {
  const input = {
    params: { database: "opendataworks", table: "workflow_publish_record" },
    extra_flag: true,
  };
  const prepared = prepareArguments(input, SAMPLE_SCHEMA, "portal_get_table_ddl");

  assert.deepEqual(prepared, input);
  assert.equal(typeof prepared.params, "object");
});

test("prepareArguments restores valid JSON string to object for object property", () => {
  const input = {
    params: JSON.stringify({ database: "opendataworks", table: "workflow_publish_record" }),
    extra_flag: true,
  };
  const prepared = prepareArguments(input, SAMPLE_SCHEMA, "portal_get_table_ddl");

  assert.deepEqual(prepared, {
    params: { database: "opendataworks", table: "workflow_publish_record" },
    extra_flag: true,
  });
  assert.equal(typeof prepared.params, "object");
  assert.equal((prepared.params as any).database, "opendataworks");
});

test("prepareArguments rejects invalid JSON string with schema help and example", () => {
  const input = {
    params: "{database: opendataworks",
  };

  assert.throws(
    () => prepareArguments(input, SAMPLE_SCHEMA, "portal_get_table_ddl"),
    (err: Error) => {
      assert.match(err.message, /Failed to parse 'portal_get_table_ddl\.params' as JSON string/);
      assert.match(err.message, /database: string \(required\)/);
      assert.match(err.message, /table: string \(required\)/);
      assert.match(err.message, /Correct call format: \{"params":\{/);
      return true;
    }
  );
});

test("prepareArguments rejects string that parses to non-object (array, null, number)", () => {
  assert.throws(
    () => prepareArguments({ params: "[1, 2, 3]" }, SAMPLE_SCHEMA, "portal_get_table_ddl"),
    (err: Error) => {
      assert.match(err.message, /Parsed 'portal_get_table_ddl\.params' must be a non-null object, got array/);
      return true;
    }
  );

  assert.throws(
    () => prepareArguments({ params: "null" }, SAMPLE_SCHEMA, "portal_get_table_ddl"),
    (err: Error) => {
      assert.match(err.message, /Parsed 'portal_get_table_ddl\.params' must be a non-null object, got null/);
      return true;
    }
  );

  assert.throws(
    () => prepareArguments({ params: "123" }, SAMPLE_SCHEMA, "portal_get_table_ddl"),
    (err: Error) => {
      assert.match(err.message, /Parsed 'portal_get_table_ddl\.params' must be a non-null object, got number/);
      return true;
    }
  );
});

test("prepareArguments rejects non-object raw arguments or non-object values", () => {
  assert.throws(
    () => prepareArguments("not an object", SAMPLE_SCHEMA, "portal_get_table_ddl"),
    /must be a JSON object/
  );

  assert.throws(
    () => prepareArguments(null, SAMPLE_SCHEMA, "portal_get_table_ddl"),
    /must be a JSON object/
  );

  assert.throws(
    () => prepareArguments({ params: 42 }, SAMPLE_SCHEMA, "portal_get_table_ddl"),
    /Field 'portal_get_table_ddl\.params' must be a non-null object, got number/
  );

  assert.throws(
    () => prepareArguments({ params: ["list"] }, SAMPLE_SCHEMA, "portal_get_table_ddl"),
    /Field 'portal_get_table_ddl\.params' must be a non-null object, got array/
  );
});
