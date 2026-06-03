const CATEGORY_ORDER = {
  root: 0,
  reference: 1,
  scripts: 2,
  assets: 3
}

const SOURCE_LABELS = {
  bundled: '内置 Skill',
  managed: '本地导入'
}

export const cloneValue = (value) => JSON.parse(JSON.stringify(value))

export const sourceLabel = (source) => SOURCE_LABELS[source] || '未知来源'

export const sortDocuments = (documents = []) => {
  return [...documents].sort((left, right) => {
    const categoryDiff = (CATEGORY_ORDER[left.category] ?? 99) - (CATEGORY_ORDER[right.category] ?? 99)
    if (categoryDiff !== 0) return categoryDiff
    return String(left.relative_path || '').localeCompare(String(right.relative_path || ''))
  })
}

export const documentsForFolder = (documents = [], folder = '') => {
  return sortDocuments(documents.filter((item) => item.folder === folder))
}

export const pickDefaultDocument = (documents = []) => {
  const sorted = sortDocuments(documents)
  return sorted.find((item) => item.file_name === 'SKILL.md') || sorted[0] || null
}

export const buildSkillItems = (documents = []) => {
  const grouped = new Map()

  documents.forEach((document) => {
    const folder = String(document.folder || '').trim()
    if (!folder) return
    if (!grouped.has(folder)) {
      grouped.set(folder, [])
    }
    grouped.get(folder).push(document)
  })

  return Array.from(grouped.entries())
    .map(([folder, items]) => {
      const sorted = sortDocuments(items)
      const primary = pickDefaultDocument(sorted) || sorted[0]
      const updatedAtValues = sorted
        .map((item) => String(item.updated_at || ''))
        .sort()
      const latestUpdatedAt = updatedAtValues[updatedAtValues.length - 1] || ''

      return {
        folder,
        source: primary?.source || 'bundled',
        enabled: sorted.some((item) => item.enabled),
        editable: sorted.some((item) => item.editable !== false),
        documentCount: sorted.length,
        versionCount: sorted.reduce((total, item) => total + Number(item.version_count || 0), 0),
        updatedAt: latestUpdatedAt,
        primaryDocumentId: primary?.id || null,
        primaryFileName: primary?.file_name || '',
        primaryPath: primary?.relative_path || '',
        documents: sorted
      }
    })
    .sort((left, right) => {
      if (left.enabled !== right.enabled) {
        return left.enabled ? -1 : 1
      }
      return left.folder.localeCompare(right.folder)
    })
}

const makeFolderNode = (key, label) => ({
  key,
  label,
  type: 'folder',
  children: []
})

const makeFileNode = (key, label, documentId) => ({
  key,
  label,
  type: 'file',
  documentId
})

const sortTreeNodes = (nodes = []) => {
  nodes.sort((left, right) => {
    if (left.label === 'SKILL.md') return -1
    if (right.label === 'SKILL.md') return 1
    if (left.type !== right.type) {
      return left.type === 'folder' ? -1 : 1
    }
    return left.label.localeCompare(right.label)
  })
  nodes.forEach((node) => {
    if (node.type === 'folder') {
      sortTreeNodes(node.children || [])
    }
  })
  return nodes
}

export const buildDocumentTree = (documents = []) => {
  const rootNodes = []
  const folderIndex = new Map()

  sortDocuments(documents).forEach((document) => {
    const parts = String(document.relative_path || '').split('/').filter(Boolean)
    if (!parts.length) return

    let parentKey = ''
    let siblings = rootNodes

    parts.forEach((part, index) => {
      const isLeaf = index === parts.length - 1
      const nextKey = parentKey ? `${parentKey}/${part}` : part

      if (isLeaf) {
        siblings.push(makeFileNode(`file:${document.id}:${nextKey}`, part, document.id))
        return
      }

      if (!folderIndex.has(nextKey)) {
        const folderNode = makeFolderNode(`folder:${nextKey}`, part)
        folderIndex.set(nextKey, folderNode)
        siblings.push(folderNode)
      }

      const folderNode = folderIndex.get(nextKey)
      siblings = folderNode.children
      parentKey = nextKey
    })
  })

  return sortTreeNodes(rootNodes)
}
