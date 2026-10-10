// SQL 语句拆分：纯函数，无 Vue 依赖、无副作用，便于单测。
// 从 DataStudioNew.vue 逐字抽出（P2-2 F3），行为保持不变。
// 按分号拆分多条语句，正确跳过单/双引号字符串、行注释（-- 与 #）和块注释（/* */）中的分号。

export const splitSqlStatements = (sqlText) => {
  const text = String(sqlText || '')
  const statements = []
  let current = ''
  let inSingle = false
  let inDouble = false
  let inLineComment = false
  let inHashComment = false
  let inBlockComment = false

  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i]
    const next = text[i + 1] || ''

    if (inLineComment) {
      current += ch
      if (ch === '\n' || ch === '\r') inLineComment = false
      continue
    }
    if (inHashComment) {
      current += ch
      if (ch === '\n' || ch === '\r') inHashComment = false
      continue
    }
    if (inBlockComment) {
      current += ch
      if (ch === '*' && next === '/') {
        current += next
        inBlockComment = false
        i += 1
      }
      continue
    }
    if (inSingle) {
      current += ch
      if (ch === '\'' && next === '\'') {
        current += next
        i += 1
        continue
      }
      if (ch === '\'') inSingle = false
      continue
    }
    if (inDouble) {
      current += ch
      if (ch === '"' && next === '"') {
        current += next
        i += 1
        continue
      }
      if (ch === '"') inDouble = false
      continue
    }

    if (ch === '-' && next === '-') {
      inLineComment = true
      current += ch + next
      i += 1
      continue
    }
    if (ch === '#') {
      inHashComment = true
      current += ch
      continue
    }
    if (ch === '/' && next === '*') {
      inBlockComment = true
      current += ch + next
      i += 1
      continue
    }
    if (ch === '\'') {
      inSingle = true
      current += ch
      continue
    }
    if (ch === '"') {
      inDouble = true
      current += ch
      continue
    }

    if (ch === ';') {
      const stmt = current.trim()
      if (stmt) statements.push(stmt)
      current = ''
      continue
    }
    current += ch
  }

  const tail = current.trim()
  if (tail) statements.push(tail)
  return statements
}

// 保留注释及已有 EXPLAIN；注释本身不构成一条可解释的语句。
export const buildExplainSql = (sqlText) => splitSqlStatements(sqlText)
  .flatMap((statement) => {
    const sql = statement.replace(/^(?:\s+|--[^\r\n]*(?:\r?\n|$)|#[^\r\n]*(?:\r?\n|$)|\/\*[\s\S]*?\*\/)*/, '')
    if (!sql.trim()) return []
    return [/^EXPLAIN\b/i.test(sql) ? statement : `EXPLAIN ${statement}`]
  }).join(';\n')
