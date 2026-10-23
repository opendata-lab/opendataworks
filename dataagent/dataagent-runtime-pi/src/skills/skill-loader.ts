import fs from "node:fs/promises";
import path from "node:path";
import { Type } from "@earendil-works/pi-ai";

export interface SkillEntry {
  name: string;
  root_path: string;
}

export function createSkillTool(skills: SkillEntry[]): unknown {
  const allowedSkills = new Map<string, string>();
  for (const s of skills || []) {
    if (s.name && s.root_path) {
      allowedSkills.set(s.name, s.root_path);
      // Also allow resolving by folder basename if different
      const base = path.basename(s.root_path);
      if (base && !allowedSkills.has(base)) {
        allowedSkills.set(base, s.root_path);
      }
    }
  }

  const availableList = Array.from(allowedSkills.keys()).join(", ") || "(none)";

  return {
    name: "Skill",
    label: "Skill",
    description: `Load instructions, reference rules, and operational guidelines for an enabled skill. Available skills: ${availableList}`,
    parameters: Type.Object({
      skill_name: Type.String({
        description: `The exact name of the skill to load. Enabled: ${availableList}`,
      }),
    }),
    execute: async (_toolCallId: string, params: { skill_name: string }) => {
      const targetName = String(params?.skill_name || "").trim();
      const rootPath = allowedSkills.get(targetName);
      if (!rootPath) {
        return {
          content: [
            {
              type: "text",
              text: `错误 [SKILL_NOT_ENABLED]：技能 ${targetName} 未启用。未加载任何内容。下一步：从已启用技能中选一个：${availableList}`,
            },
          ],
          details: { denied: true, available: Array.from(allowedSkills.keys()) },
          isError: true,
        };
      }

      const skillFilePath = path.join(rootPath, "SKILL.md");
      try {
        const content = await fs.readFile(skillFilePath, "utf8");
        // Like Claude Code's "Base directory for this skill": a skill names its
        // scripts and references relative to itself, never by a runtime path.
        const header =
          `本技能目录：${rootPath}\n` +
          "SKILL.md 及其引用文件里的相对路径（如 scripts/、references/）都以本技能目录为准。\n\n";
        return {
          content: [{ type: "text", text: header + content }],
          details: { skill_name: targetName, root_path: rootPath },
        };
      } catch (err: unknown) {
        const code = (err as NodeJS.ErrnoException)?.code;
        const reason = code === "ENOENT" ? "不存在" : code === "EACCES" ? "无权限读取" : "无法读取";
        return {
          content: [
            {
              type: "text",
              text:
                `错误 [SKILL_FILE_UNREADABLE]：已启用技能 ${targetName} 的 SKILL.md ${reason}。` +
                "技能内容未加载，需要维护者修复安装；同参重试无效。",
            },
          ],
          details: { error: code ?? "unknown" },
          isError: true,
        };
      }
    },
  };
}
