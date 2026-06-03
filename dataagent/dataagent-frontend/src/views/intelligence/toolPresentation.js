const isPlainObject = (value) => value && typeof value === 'object' && !Array.isArray(value)

const parseMaybeJson = (value) => {
  if (typeof value !== 'string') return null
  const raw = value.trim()
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch (_error) {
    return null
  }
}

const firstFilled = (...values) => {
  for (const value of values) {
    const text = String(value ?? '').trim()
    if (text) return text
  }
  return ''
}

const hasWord = (text, word) => {
  const normalized = String(text || '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
  if (!normalized) return false
  return normalized.split(/\s+/).includes(String(word || '').toLowerCase())
}

export const parseToolInput = (value) => {
  if (isPlainObject(value)) return value
  if (typeof value === 'string') {
    const parsed = parseMaybeJson(value)
    if (isPlainObject(parsed)) return parsed
    const text = value.trim()
    return text ? { command: text } : {}
  }
  return {}
}

const buildSearchDetail = ({ pattern, directory, path, description }) => {
  if (pattern && (directory || path)) {
    return `${pattern} · ${directory || path}`
  }
  return firstFilled(pattern, directory, path, description)
}

const SKILL_LAUNCH_OUTPUT_RE = /^Launching skill(?::\s*(.+))?$/i

export const extractToolSkillName = (tool = {}) => {
  const input = parseToolInput(tool?.input)
  const fromInput = firstFilled(
    input.skill,
    input.skill_name,
    input.skillName,
    input.skill_id,
    input.skillId
  )
  if (fromInput) return fromInput

  const output = String(tool?.output || '').trim()
  const match = output.match(SKILL_LAUNCH_OUTPUT_RE)
  if (match?.[1]) return String(match[1]).trim()

  return ''
}

export const isSkillBootstrapPlaceholder = (tool = {}) => {
  const action = describeToolAction(tool)
  if (action.kind !== 'skill') return false

  const output = String(tool?.output || '').trim()
  if (!output) return true
  return SKILL_LAUNCH_OUTPUT_RE.test(output)
}

export const formatSkillBootstrapLabel = (skillName) => {
  const value = String(skillName || '').trim()
  return value ? `加载技能（${value}）` : '加载技能'
}

export const describeToolAction = (tool = {}) => {
  const name = String(tool?.name || tool?.toolName || '').trim()
  const lowerName = name.toLowerCase()
  const input = parseToolInput(tool?.input)
  const skillName = extractToolSkillName(tool)

  const command = firstFilled(
    input.command,
    input.cmd,
    input.Command,
    input.script,
    input.Script,
    input.argv
  )
  const path = firstFilled(
    input.file_path,
    input.path,
    input.AbsolutePath,
    input.TargetFile,
    input.file,
    input.File
  )
  const directory = firstFilled(
    input.directory,
    input.dir,
    input.SearchDirectory,
    input.Directory,
    input.cwd,
    input.workdir,
    input.root
  )
  const pattern = firstFilled(
    input.pattern,
    input.Pattern,
    input.query,
    input.Query,
    input.keyword,
    input.Keyword,
    input.glob,
    input.Glob,
    input.name,
    input.Name
  )
  const description = firstFilled(
    input.description,
    input.summary,
    input.reason,
    input.purpose,
    input.note
  )

  const isMcp = lowerName.startsWith('mcp__')

  let kind = 'tool'
  if (isMcp) {
    // Keep MCP tools generic; never let substrings like "search"/"read" in the
    // qualified name (mcp__server__tool) misclassify them as file traces.
    kind = 'tool'
  } else if (lowerName.includes('skill') || skillName) {
    kind = 'skill'
  } else if (
    lowerName.includes('bash')
    || lowerName.includes('shell')
    || lowerName.includes('terminal')
    || lowerName.includes('run_command')
    || command
  ) {
    kind = 'shell'
  } else if (
    lowerName.includes('replace')
    || lowerName.includes('write')
    || lowerName.includes('edit')
    || lowerName.includes('apply_patch')
  ) {
    kind = 'edit'
  } else if (
    lowerName.includes('grep')
    || hasWord(lowerName, 'glob')
    || lowerName.includes('search')
    || lowerName.includes('find_by_name')
    || lowerName.includes('find_file')
    || (pattern && (directory || path))
  ) {
    kind = 'search'
  } else if (
    hasWord(lowerName, 'ls')
    || lowerName.includes('list_dir')
    || lowerName.includes('list_directory')
    || lowerName.includes('list_files')
    || lowerName.includes('listdir')
    || directory
  ) {
    kind = 'list'
  } else if (
    hasWord(lowerName, 'read')
    || lowerName.includes('read_file')
    || lowerName.includes('readfile')
    || lowerName.includes('view')
    || lowerName.includes('cat')
    || path
  ) {
    kind = 'read'
  }

  // Render MCP tool names (mcp__<server>__<tool>) as "<server> / <tool>"
  // instead of the raw double-underscore identifier.
  const mcpMatch = name.match(/^mcp__(.+?)__(.+)$/i)
  const displayName = mcpMatch ? `${mcpMatch[1]} / ${mcpMatch[2]}` : name

  const labelMap = {
    shell: '执行命令',
    read: '读取文件',
    list: '查看目录',
    search: '搜索文件',
    edit: '修改文件',
    skill: '执行技能',
    tool: displayName ? `执行工具：${displayName}` : '执行工具'
  }

  let detail = ''
  if (kind === 'shell') {
    detail = firstFilled(command, description, path)
  } else if (kind === 'read') {
    detail = firstFilled(path, description, directory)
  } else if (kind === 'list') {
    detail = firstFilled(directory, path, description)
  } else if (kind === 'search') {
    detail = buildSearchDetail({ pattern, directory, path, description })
  } else if (kind === 'edit') {
    detail = firstFilled(path, directory, description, command)
  } else if (kind === 'skill') {
    detail = firstFilled(skillName, description, command, path, directory, name)
  } else {
    detail = firstFilled(description, command, path, directory, pattern, name)
  }

  return {
    kind,
    label: labelMap[kind],
    detail,
    preview: detail,
    input,
    name,
    command,
    path,
    directory,
    pattern,
    description,
    isTrace: kind !== 'tool'
  }
}
