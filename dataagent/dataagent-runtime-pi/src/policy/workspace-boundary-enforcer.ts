/**
 * Workspace boundary enforcement for the Pi data plane.
 *
 * The policy is *generated* by Python (core/boundary_policy.build_boundary_policy)
 * and handed to this process once, in the cell.init frame. Enforcement then runs
 * locally so a tool call does not pay a stdio round-trip to be judged.
 *
 * That split means this file is a second implementation of rules that also exist
 * in Python (core/agent_runtime._validate_workspace_tool_boundary), which is a
 * real drift risk. What contains it is
 * dataagent/contracts/boundary/v1/conformance-cases.json: both implementations
 * run the same case table, so a divergence fails a test rather than quietly
 * widening the boundary in production. Change behaviour here only together with
 * a fixture case, and expect the Python side to have to agree.
 *
 * The pi_agent_core profile is a strict subset of the claude_code one: this Cell
 * truncates tool output in process and never offloads it to disk, so the CLI's
 * offloaded-tool-result exception (and its read-only command gate, and the pager
 * exclusion) has nothing to apply to and is deliberately absent here.
 */

import path from "node:path";

export interface BoundaryPolicy {
  policy_version: number;
  profile: string;
  workspace_root: string;
  allowed_roots: string[];
  allowed_executables: string[];
  discard_sinks: string[];
  tool_path_keys: Record<string, string[]>;
  operator_chars: string;
  tool_result_root: string | null;
  readonly_commands: string[];
}

// Shared with the Claude Code profile (core/agent_runtime.py); the conformance
// cases match on the codes, so keep the two wordings in step.
function parentDenial(subject: string): string {
  return (
    `错误 [PARENT_SEGMENT]：${subject} 的路径含 ".." 段，已拒绝，未执行；同参重试无效。` +
    "下一步：业务文件写相对工作区根目录的路径；技能里的脚本写成“本技能目录”下的路径。"
  );
}

function scopeDenial(subject: string, value: string): string {
  return (
    `错误 [PATH_OUT_OF_SCOPE]：${subject} 的 ${value} 在允许范围（工作区与已启用技能目录）之外，已拒绝，未执行；` +
    "同参重试无效。下一步：改用工作区内的相对路径，或已启用技能目录下的路径。"
  );
}

const PARENT_SEGMENT_DENIAL = parentDenial("Bash 命令");

/** Mirrors Python's _BASH_PARENT_SEGMENT_RE. */
const PARENT_SEGMENT_RE = /(^|[\s;&|()])\.\.(?=$|[/\s;&|()])/;
/** Mirrors Python's _URL_SCHEME_RE. */
// Quoted heredoc bodies (<<'EOF', <<"EOF", <<-'EOF') are literal stdin data: the
// shell expands nothing inside them, so their text is never a path argument.
// The terminator pattern accepts more lines than bash does, so stripping can only
// stop early and leave extra text to be checked, never hide executed text.
const QUOTED_HEREDOC_RE = /(<<-?[ \t]*(['"])([A-Za-z_][A-Za-z0-9_]*)\2[^\n]*\n)([\s\S]*?\n)?([ \t]*\3[ \t]*)(?=\n|$)/g;

const URL_SCHEME_RE = /^[A-Za-z][A-Za-z0-9+.-]*:\/\//;
/** Mirrors Python's _BASH_OPTION_ASSIGNMENT_RE. */
const OPTION_ASSIGNMENT_RE = /^-{0,2}[A-Za-z_][A-Za-z0-9_-]*=/;
/** Mirrors Python's _BASH_ASSIGNMENT_VALUE_SPLIT_RE. */
const ASSIGNMENT_VALUE_SPLIT_RE = /[=:]/;

export class WorkspaceBoundaryEnforcer {
  private readonly policy: BoundaryPolicy;
  private readonly allowedRoots: string[];
  private readonly allowedExecutables: Set<string>;
  private readonly discardSinks: Set<string>;
  private readonly operatorChars: Set<string>;

  constructor(policy: BoundaryPolicy) {
    this.policy = policy;
    this.allowedRoots = policy.allowed_roots.map((root) => path.resolve(root));
    this.allowedExecutables = new Set((policy.allowed_executables || []).map((p) => path.resolve(p)));
    this.discardSinks = new Set((policy.discard_sinks || []).map((p) => path.resolve(p)));
    this.operatorChars = new Set((policy.operator_chars || "();<>|&").split(""));
  }

  /** Denial reason, or null when the call is allowed. */
  public validate(toolName: string, toolInput: Record<string, unknown> | null | undefined): string | null {
    const name = String(toolName || "").trim();
    const input = toolInput || {};

    if (name === "Bash") {
      return this.validateBash(String((input as { command?: unknown }).command ?? ""));
    }
    return this.validateFileTool(name, input);
  }

  private validateFileTool(toolName: string, input: Record<string, unknown>): string | null {
    const keys = this.policy.tool_path_keys[toolName] || [];
    for (const key of keys) {
      const raw = input[key];
      if (raw === null || raw === undefined) {
        continue;
      }
      const values = Array.isArray(raw) ? raw : [raw];
      for (const item of values) {
        const text = String(item ?? "").trim();
        if (!text) {
          continue;
        }
        if (hasParentSegment(text)) {
          return parentDenial(`${toolName}.${key}`);
        }
        const resolved = this.resolveCandidate(text);
        if (this.isDiscardSink(resolved)) {
          continue;
        }
        if (!this.isAllowed(resolved)) {
          return scopeDenial(`${toolName}.${key}`, text);
        }
      }
    }
    return null;
  }

  private validateBash(rawCommand: string): string | null {
    const command = rawCommand.replace(QUOTED_HEREDOC_RE, (_m, header: string, _q, _tag, _body, terminator: string) => header + terminator);
    if (PARENT_SEGMENT_RE.test(command.replace(/\\/g, "/"))) {
      return PARENT_SEGMENT_DENIAL;
    }

    for (const token of tokenize(command)) {
      if (this.classifyOperator(token) !== null) {
        continue;
      }
      const normalized = normalizeToken(token);
      if (!normalized || normalized.startsWith("$") || URL_SCHEME_RE.test(normalized)) {
        continue;
      }
      if (hasParentSegment(normalized)) {
        return PARENT_SEGMENT_DENIAL;
      }
      for (const value of pathCandidates(normalized)) {
        const resolved = path.resolve(value);
        if (this.allowedExecutables.has(resolved)) {
          continue;
        }
        if (this.isDiscardSink(resolved)) {
          continue;
        }
        if (this.isAllowed(resolved)) {
          continue;
        }
        return scopeDenial("Bash 命令", value);
      }
    }
    return null;
  }

  private resolveCandidate(raw: string): string {
    const expanded = expandVars(raw);
    if (path.isAbsolute(expanded)) {
      return path.resolve(expanded);
    }
    return path.resolve(this.policy.workspace_root, expanded);
  }

  /** Matched exactly: /dev/null rides along, anything under it does not. */
  private isDiscardSink(resolved: string): boolean {
    return this.discardSinks.has(resolved);
  }

  private isAllowed(resolved: string): boolean {
    return this.allowedRoots.some((root) => resolved === root || resolved.startsWith(root + path.sep));
  }

  /** An operator token is a run made up *only* of operator chars. */
  private classifyOperator(token: string): "redirect" | "separator" | null {
    if (!token) {
      return null;
    }
    for (const ch of token) {
      if (!this.operatorChars.has(ch)) {
        return null;
      }
    }
    return /[<>]/.test(token) ? "redirect" : "separator";
  }
}

function hasParentSegment(value: string): boolean {
  return value
    .replace(/\\/g, "/")
    .split("/")
    .some((part) => part === "..");
}

function normalizeToken(token: string): string {
  return String(token ?? "")
    .trim()
    .replace(/^['"]+|['"]+$/g, "")
    .replace(/[,;]+$/g, "");
}

/**
 * Absolute paths carried by one Bash word.
 *
 * A word that is itself an absolute path is its own candidate. Paths also hide
 * inside key=value words (`dd if=/etc/shadow`, `FOO=/etc/shadow cat $FOO`),
 * which would otherwise skip the check entirely because the word does not start
 * with "/". Checking the assignment is what catches variable indirection: the
 * literal is visible where it is assigned even though $FOO is opaque at the use
 * site. Mirrors Python's _bash_token_path_candidates.
 */
function pathCandidates(normalized: string): string[] {
  if (normalized.startsWith("/")) {
    return [normalized];
  }
  if (!OPTION_ASSIGNMENT_RE.test(normalized)) {
    return [];
  }
  const value = normalized.slice(normalized.indexOf("=") + 1);
  return value.split(ASSIGNMENT_VALUE_SPLIT_RE).filter((segment) => segment.startsWith("/"));
}

function expandVars(value: string): string {
  return value.replace(/\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?/g, (match, name: string) => {
    const found = process.env[name];
    return found === undefined ? match : found;
  });
}

/**
 * Punctuation-aware split into words *and* standalone shell operators.
 *
 * Mirrors Python's shlex with punctuation_chars=True: `cat a>/tmp/out` must
 * yield ["cat", "a", ">", "/tmp/out"] so the redirect target is checked rather
 * than hidden inside a glued token. Quotes suppress both splitting and operator
 * recognition, so `sed 's/a=/b/g'` stays one word.
 */
function tokenize(command: string): string[] {
  const tokens: string[] = [];
  let current = "";
  let quote: string | null = null;
  let operatorRun = "";
  const operatorChars = new Set("();<>|&".split(""));

  const flushWord = () => {
    if (current) {
      tokens.push(current);
      current = "";
    }
  };
  const flushOperator = () => {
    if (operatorRun) {
      tokens.push(operatorRun);
      operatorRun = "";
    }
  };

  for (const ch of command) {
    if (quote) {
      if (ch === quote) {
        quote = null;
      } else {
        current += ch;
      }
      continue;
    }
    if (ch === "'" || ch === '"') {
      flushOperator();
      quote = ch;
      continue;
    }
    if (/\s/.test(ch)) {
      flushOperator();
      flushWord();
      continue;
    }
    if (operatorChars.has(ch)) {
      flushWord();
      operatorRun += ch;
      continue;
    }
    flushOperator();
    current += ch;
  }
  if (quote) {
    // Unbalanced quotes: fall back to whitespace splitting, as Python does when
    // shlex raises.
    return command.split(/\s+/).filter(Boolean);
  }
  flushOperator();
  flushWord();
  return tokens;
}
