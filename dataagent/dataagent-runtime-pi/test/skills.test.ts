import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { createSkillTool } from "../src/skills/skill-loader.js";
import { createTools } from "../src/tools/tool-registry.js";
import { WorkspaceBoundaryEnforcer } from "../src/policy/workspace-boundary-enforcer.js";

test("Skill tool is registered when skills are enabled", async () => {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "pi-skills-test-"));
  try {
    const skillDir = path.join(tmpDir, "opendataworks-platform-tools");
    await fs.mkdir(skillDir, { recursive: true });
    await fs.writeFile(path.join(skillDir, "SKILL.md"), "# Test Skill Content\nRun SQL and inspect metadata.");

    const boundary = new WorkspaceBoundaryEnforcer({
      policy_version: 1,
      profile: "pi_agent_core",
      workspace_root: tmpDir,
      allowed_roots: [tmpDir],
      allowed_executables: [],
      discard_sinks: [],
      tool_path_keys: {},
      operator_chars: "",
      tool_result_root: null,
      readonly_commands: [],
    });

    const tools = createTools({
      toolTimeoutMs: 30_000,
      boundary,
      workspaceRoot: tmpDir,
      runtimeEnv: {},
      skills: [{ name: "opendataworks-platform-tools", root_path: skillDir }],
    }) as Array<{ name: string; execute: Function }>;

    const skillTool = tools.find((t) => t.name === "Skill");
    assert.ok(skillTool, "Skill tool must be registered");

    // Execute Skill tool with enabled skill
    const successResult = await skillTool.execute("call-1", {
      skill_name: "opendataworks-platform-tools",
    });
    assert.equal(successResult.isError, undefined);
    assert.match(successResult.content[0].text, /Test Skill Content/);

    // Execute Skill tool with non-enabled skill
    const deniedResult = await skillTool.execute("call-2", {
      skill_name: "unknown-skill",
    });
    assert.equal(deniedResult.isError, true);
    assert.match(deniedResult.content[0].text, /not enabled/);
  } finally {
    await fs.rm(tmpDir, { recursive: true, force: true });
  }
});
