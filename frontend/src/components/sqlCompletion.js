// NOTE: keep each branch single-character (no nested `+`). A previous form
// `(?:`[^`]*`|[\w$]+|\.)+$` nested a `+` quantifier inside another `+`, which
// caused catastrophic regex backtracking: on completion the editor froze for
// ~18s ("setTimeout handler took 18000ms" in CodeMirror) whenever a long
// word-character run preceded a non-identifier boundary before the cursor.
// This form matches the same language (identifiers, dotted paths, backtick
// quoted names) but runs in linear time.
const IDENTIFIER_TOKEN = /(?:`[^`]*`|[\w$]|\.)+$/
const VALID_FOR = /^[`"'\[\]\w$.]*$/

const SQL_KEYWORDS = [
  'SELECT',
  'FROM',
  'WHERE',
  'GROUP BY',
  'ORDER BY',
  'LIMIT',
  'HAVING',
  'JOIN',
  'LEFT JOIN',
  'RIGHT JOIN',
  'INNER JOIN',
  'OUTER JOIN',
  'ON',
  'AS',
  'WITH',
  'DISTINCT',
  'UNION',
  'UNION ALL',
  'EXPLAIN',
  'SHOW',
  'DESCRIBE',
  'DESC',
  'AND',
  'OR',
  'NOT',
  'IN',
  'LIKE',
  'IS NULL',
  'IS NOT NULL',
  'BETWEEN',
  'CASE',
  'WHEN',
  'THEN',
  'ELSE',
  'END'
]

const SQL_FUNCTIONS = [
  'COUNT',
  'SUM',
  'AVG',
  'MIN',
  'MAX',
  'ROUND',
  'ABS',
  'COALESCE',
  'IFNULL',
  'NULLIF',
  'CONCAT',
  'SUBSTRING',
  'LOWER',
  'UPPER',
  'TRIM',
  'DATE_FORMAT',
  'DATE_ADD',
  'DATE_SUB',
  'DATEDIFF',
  'NOW',
  'ROW_NUMBER',
  'RANK',
  'DENSE_RANK'
]

const RESERVED_ALIAS_WORDS = new Set([
  'where',
  'join',
  'left',
  'right',
  'inner',
  'outer',
  'full',
  'cross',
  'on',
  'group',
  'order',
  'limit',
  'having',
  'union',
  'as'
])

const uniqueOptions = (options) => {
  const seen = new Set()
  const result = []
  options.forEach((option) => {
    const key = `${option.type || ''}::${option.label || ''}::${option.detail || ''}`
    if (seen.has(key)) return
    seen.add(key)
    result.push(option)
  })
  return result
}

const normalizeIdentifier = (value) => {
  const text = String(value || '').trim()
  const quoted = text.match(/^`(.*)`$/)
  return quoted ? quoted[1] : text
}

const splitIdentifierPath = (value) => {
  const text = String(value || '')
  const parts = []
  let current = ''
  let quoted = false
  for (const char of text) {
    if (char === '`') {
      quoted = !quoted
      current += char
      continue
    }
    if (char === '.' && !quoted) {
      parts.push(normalizeIdentifier(current))
      current = ''
      continue
    }
    current += char
  }
  parts.push(normalizeIdentifier(current))
  return parts.filter((item) => item !== '')
}

const getDocText = (context) => {
  const doc = context?.state?.doc
  if (!doc) return ''
  if (typeof doc.toString === 'function') return doc.toString()
  return String(doc)
}

const getToken = (context) => {
  const docText = getDocText(context)
  const before = docText.slice(0, context.pos)
  const match = before.match(IDENTIFIER_TOKEN)
  if (!match) {
    return { text: '', from: context.pos, parents: [], typed: '' }
  }
  const text = match[0]
  const from = context.pos - text.length
  const endsWithDot = text.endsWith('.')
  const parts = splitIdentifierPath(text)
  const parents = endsWithDot ? parts : parts.slice(0, -1)
  const typed = endsWithDot ? '' : parts[parts.length - 1] || ''
  const replaceFrom = endsWithDot ? context.pos : context.pos - typed.length
  return { text, from: replaceFrom, parents, typed }
}

const tableOption = (table, schema, boost = 70) => {
  const tableName = String(table?.tableName || table?.name || table || '').trim()
  if (!tableName) return null
  const comment = String(table?.tableComment || table?.comment || '').trim()
  return {
    label: tableName,
    type: 'variable',
    detail: schema || '',
    info: comment || undefined,
    boost
  }
}

const columnOption = (column) => {
  const name = String(column?.columnName || column?.fieldName || column?.name || '').trim()
  if (!name) return null
  return {
    label: name,
    type: 'property',
    detail: String(column?.dataType || column?.fieldType || '').trim(),
    info: String(column?.columnComment || column?.fieldComment || '').trim() || undefined,
    boost: 90
  }
}

const keywordOptions = () =>
  SQL_KEYWORDS.map((keyword) => ({
    label: keyword,
    type: 'keyword',
    boost: 10
  }))

const functionOptions = () =>
  SQL_FUNCTIONS.map((name) => ({
    label: name,
    type: 'function',
    detail: 'function',
    apply: `${name}()`,
    boost: 30
  }))

const schemaOptions = (schemas = []) =>
  schemas
    .map((schema) => String(schema || '').trim())
    .filter(Boolean)
    .map((schema) => ({
      label: schema,
      type: 'namespace',
      detail: 'schema',
      boost: 60
    }))

const getTablesFromContext = async (ctx, schema) => {
  if (typeof ctx?.loadTables === 'function') {
    const loaded = await ctx.loadTables(schema)
    return Array.isArray(loaded) ? loaded : []
  }
  const existing = ctx?.tablesBySchema?.[schema]
  if (Array.isArray(existing)) return existing
  return []
}

const getColumnsFromContext = async (ctx, schema, table) => {
  if (!schema || !table || typeof ctx?.loadColumns !== 'function') return []
  const loaded = await ctx.loadColumns({ schema, table })
  return Array.isArray(loaded) ? loaded : []
}

const extractAliases = (docText, defaultSchema) => {
  const aliases = new Map()
  const pattern = /\b(?:from|join)\s+((?:`[^`]+`|[\w$]+)(?:\s*\.\s*(?:`[^`]+`|[\w$]+))?)(?:\s+(?:as\s+)?(`[^`]+`|[\w$]+))?/gi
  let match
  while ((match = pattern.exec(docText))) {
    const path = splitIdentifierPath(match[1].replace(/\s+/g, ''))
    if (!path.length) continue
    const table = path[path.length - 1]
    const schema = path.length > 1 ? path[path.length - 2] : defaultSchema
    if (!schema || !table) continue
    const alias = normalizeIdentifier(match[2] || '')
    if (alias && !RESERVED_ALIAS_WORDS.has(alias.toLowerCase())) {
      aliases.set(alias, { schema, table })
    }
    aliases.set(table, { schema, table })
  }
  return aliases
}

const completionResult = (from, options) => {
  const unique = uniqueOptions(options.filter(Boolean))
  if (!unique.length) return null
  return {
    from,
    options: unique,
    validFor: VALID_FOR
  }
}

export const createSqlCompletionSource = ({ getCompletionContext, getTableNames } = {}) => {
  return async (context) => {
    const token = getToken(context)
    if (!token.text && !context.explicit) return null

    const ctx = typeof getCompletionContext === 'function' ? getCompletionContext() : null
    const currentSchema = String(ctx?.currentSchema || ctx?.dbName || '').trim()

    if (token.parents.length >= 2 && ctx) {
      const table = token.parents[token.parents.length - 1]
      const schema = token.parents[token.parents.length - 2]
      const columns = await getColumnsFromContext(ctx, schema, table)
      return completionResult(token.from, columns.map(columnOption))
    }

    if (token.parents.length === 1 && ctx) {
      const parent = token.parents[0]
      const schemas = Array.isArray(ctx.schemas) ? ctx.schemas.map((item) => String(item)) : []
      if (schemas.includes(parent)) {
        const tables = await getTablesFromContext(ctx, parent)
        return completionResult(token.from, tables.map((table) => tableOption(table, parent, 80)))
      }

      const aliases = extractAliases(getDocText(context), currentSchema)
      const aliasTarget = aliases.get(parent)
      if (aliasTarget) {
        const columns = await getColumnsFromContext(ctx, aliasTarget.schema, aliasTarget.table)
        return completionResult(token.from, columns.map(columnOption))
      }
    }

    const options = []
    const legacyTables = typeof getTableNames === 'function' ? getTableNames() : []
    options.push(...(Array.isArray(legacyTables) ? legacyTables : []).map((name) => ({
      label: String(name || '').trim(),
      type: 'variable',
      boost: 70
    })).filter((item) => item.label))

    if (ctx) {
      const schemas = Array.isArray(ctx.schemas) ? ctx.schemas : []
      options.push(...schemaOptions(schemas))
      if (currentSchema) {
        const currentTables = await getTablesFromContext(ctx, currentSchema)
        options.push(...currentTables.map((table) => tableOption(table, currentSchema, 80)))
      }
      if (token.typed && token.typed.length >= 2 && typeof ctx.searchTables === 'function') {
        const searched = await ctx.searchTables(token.typed)
        options.push(...(Array.isArray(searched) ? searched : []).map((item) =>
          tableOption(
            { tableName: item.tableName, tableComment: item.tableComment },
            item.schemaName || item.dbName,
            65
          )
        ))
      }

      // Suggest columns from the tables referenced in the current FROM/JOIN
      // clauses so bare (unqualified) field names are completed too, not only
      // the `alias.column` / `schema.table.column` qualified forms.
      const referencedTables = extractAliases(getDocText(context), currentSchema)
      const seenTables = new Set()
      for (const target of referencedTables.values()) {
        const key = `${target.schema}::${target.table}`
        if (seenTables.has(key)) continue
        seenTables.add(key)
        const columns = await getColumnsFromContext(ctx, target.schema, target.table)
        options.push(...columns.map(columnOption))
      }
    }

    options.push(...functionOptions(), ...keywordOptions())
    return completionResult(token.from, options)
  }
}
