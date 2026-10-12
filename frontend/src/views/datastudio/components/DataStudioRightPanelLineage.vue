<!-- 数据血缘连线可视化组件 -->
<template>
  <section class="lineage-panel">
    <div class="lineage-header">
      <div class="header-main">
        <div class="header-title-row">
          <div class="section-title">数据血缘流向</div>
          <el-tooltip content="点击上下游表先查看基本信息，再进入详情" placement="top">
            <el-icon class="header-tip-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
        </div>
      </div>
      <el-button type="primary" link size="small" @click="emit('go-lineage')">
        查看完整血缘
      </el-button>
    </div>

    <!-- 上游数据流入 -->
    <div class="flow-section">
      <div class="flow-section-title">上游数据流入</div>
      <div ref="upstreamDiagramRef" class="lineage-diagram">
        <div class="diagram-column">
          <div class="column-header">
            <el-icon><Grid /></el-icon>
            上游表 ({{ upstreamTables.length }})
          </div>
          <div class="node-list">
            <div
              v-for="item in upstreamTables"
              :key="`up-${item.id}`"
              :data-node-id="`upstream-table-${item.id}`"
              class="lineage-node table-node"
              @click="openTablePreview(item)"
            >
              <el-icon class="node-icon"><Grid /></el-icon>
              <div class="node-content">
                <div class="node-name" :title="item.tableName">{{ item.tableName }}</div>
                <div class="node-desc" :title="item.tableComment || '-'">{{ item.tableComment || '-' }}</div>
              </div>
              <el-tag v-if="item.layer" size="small" :type="getLayerType(item.layer, '')">{{ item.layer }}</el-tag>
            </div>
            <div v-if="!upstreamTables.length" class="empty-placeholder">暂无上游表</div>
          </div>
        </div>

        <div class="diagram-column">
          <div class="column-header">
            <el-icon><Operation /></el-icon>
            写入任务 ({{ writeTasks.length }})
          </div>
          <div class="node-list">
            <div
              v-for="task in writeTasks"
              :key="`write-${task.id}`"
              :data-node-id="`write-task-${task.id}`"
              class="lineage-node task-node"
              @click="emit('open-task', task.id)"
            >
              <el-icon class="node-icon"><Operation /></el-icon>
              <div class="node-content">
                <div class="node-name" :title="task.taskName || '-'">{{ task.taskName || '-' }}</div>
                <div class="node-desc" :title="task.engine || '-'">{{ task.engine || '-' }}</div>
              </div>
            </div>
            <div v-if="!writeTasks.length" class="empty-placeholder">暂无写入任务</div>
          </div>
          <div class="node-actions">
            <el-button
              type="primary"
              size="small"
              plain
              :disabled="isDemoMode || !currentTable?.id"
              @click.stop="emit('create-task', 'write')"
            >
              <el-icon><Plus /></el-icon>
              新增写入任务
            </el-button>
          </div>
        </div>

        <div class="diagram-column diagram-current">
          <div class="column-header">
            <el-icon><Grid /></el-icon>
            当前表
          </div>
          <div class="lineage-node table-node current-table-node is-current" :data-node-id="`current-table-${currentTable?.id}`">
            <el-icon class="node-icon current-node-icon"><Grid /></el-icon>
            <div class="node-content current-node-content">
              <div class="node-name current-node-name" :title="currentTable?.tableName || '-'">
                {{ currentTable?.tableName || '-' }}
              </div>
              <div class="node-desc current-node-desc" :title="currentTable?.tableComment || '-'">
                {{ currentTable?.tableComment || '-' }}
              </div>
            </div>
          </div>
        </div>

        <!-- SVG 连线层 -->
        <svg class="lineage-connections-svg">
          <defs>
            <marker
              id="arrowhead-upstream"
              markerWidth="8"
              markerHeight="6"
              refX="0"
              refY="3"
              orient="auto"
              markerUnits="userSpaceOnUse"
            >
              <path d="M0,0 L0,6 L8,3 z" fill="#2f6aa3" />
            </marker>
          </defs>
          <path
            v-for="line in upstreamLines"
            :key="line.id"
            :d="line.path"
            class="connection-line"
            :class="line.className"
            marker-end="url(#arrowhead-upstream)"
          />
        </svg>
      </div>
    </div>

    <!-- 下游数据流出 -->
    <div class="flow-section">
      <div class="flow-section-title">下游数据流出</div>
      <div ref="downstreamDiagramRef" class="lineage-diagram">
        <div class="diagram-column diagram-current">
          <div class="column-header">
            <el-icon><Grid /></el-icon>
            当前表
          </div>
          <div class="lineage-node table-node current-table-node is-current" :data-node-id="`current-table-down-${currentTable?.id}`">
            <el-icon class="node-icon current-node-icon"><Grid /></el-icon>
            <div class="node-content current-node-content">
              <div class="node-name current-node-name" :title="currentTable?.tableName || '-'">
                {{ currentTable?.tableName || '-' }}
              </div>
              <div class="node-desc current-node-desc" :title="currentTable?.tableComment || '-'">
                {{ currentTable?.tableComment || '-' }}
              </div>
            </div>
          </div>
        </div>

        <div class="diagram-column">
          <div class="column-header">
            <el-icon><Operation /></el-icon>
            读取任务 ({{ orderedReadTasks.length }})
          </div>
          <div class="node-list">
            <div
              v-for="task in orderedReadTasks"
              :key="`read-${task.id}`"
              :data-node-id="`read-task-${task.id}`"
              class="lineage-node task-node"
              @click="emit('open-task', task.id)"
            >
              <el-icon class="node-icon"><Operation /></el-icon>
              <div class="node-content">
                <div class="node-name" :title="task.taskName || '-'">{{ task.taskName || '-' }}</div>
                <div class="node-desc" :title="task.engine || '-'">{{ task.engine || '-' }}</div>
              </div>
            </div>
            <div v-if="!orderedReadTasks.length" class="empty-placeholder">暂无读取任务</div>
          </div>
          <div class="node-actions">
            <el-button
              type="primary"
              size="small"
              plain
              :disabled="isDemoMode || !currentTable?.id"
              @click.stop="emit('create-task', 'read')"
            >
              <el-icon><Plus /></el-icon>
              新增读取任务
            </el-button>
          </div>
        </div>

        <div class="diagram-column">
          <div class="column-header">
            <el-icon><Grid /></el-icon>
            下游表 ({{ orderedDownstreamTables.length }})
          </div>
          <div class="node-list">
            <div
              v-for="item in orderedDownstreamTables"
              :key="`down-${item.id}`"
              :data-node-id="`downstream-table-${item.id}`"
              class="lineage-node table-node"
              @click="openTablePreview(item)"
            >
              <el-icon class="node-icon"><Grid /></el-icon>
              <div class="node-content">
                <div class="node-name" :title="item.tableName">{{ item.tableName }}</div>
                <div class="node-desc" :title="item.tableComment || '-'">{{ item.tableComment || '-' }}</div>
              </div>
              <el-tag v-if="item.layer" size="small" :type="getLayerType(item.layer, '')">{{ item.layer }}</el-tag>
            </div>
            <div v-if="!orderedDownstreamTables.length" class="empty-placeholder">暂无下游表</div>
          </div>
        </div>

        <!-- SVG 连线层 -->
        <svg class="lineage-connections-svg">
          <defs>
            <marker
              id="arrowhead-downstream"
              markerWidth="8"
              markerHeight="6"
              refX="0"
              refY="3"
              orient="auto"
              markerUnits="userSpaceOnUse"
            >
              <path d="M0,0 L0,6 L8,3 z" fill="#2f6aa3" />
            </marker>
          </defs>
          <path
            v-for="line in downstreamLines"
            :key="line.id"
            :d="line.path"
            class="connection-line"
            :class="line.className"
            marker-end="url(#arrowhead-downstream)"
          />
        </svg>
      </div>
    </div>

    <el-dialog v-model="tablePreviewVisible" title="表基本信息" width="460px" append-to-body>
      <el-descriptions v-if="tablePreview" :column="1" border size="small">
        <el-descriptions-item label="表名">{{ tablePreview.tableName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="注释">{{ tablePreview.tableComment || '-' }}</el-descriptions-item>
        <el-descriptions-item label="分层">{{ tablePreview.layer || '-' }}</el-descriptions-item>
        <el-descriptions-item label="数据库">{{ resolveTableDatabase(tablePreview) }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="tablePreviewVisible = false">关闭</el-button>
        <el-button type="primary" @click="openTableFromPreview">查看详情</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { Grid, Operation, Plus, QuestionFilled } from '@element-plus/icons-vue'
import { isDemoMode } from '@/demo/runtime'
import { getLayerType } from '../tableFormat'

const props = defineProps({
  currentTable: {
    type: Object,
    default: () => ({})
  },
  upstreamTables: {
    type: Array,
    default: () => []
  },
  downstreamTables: {
    type: Array,
    default: () => []
  },
  writeTasks: {
    type: Array,
    default: () => []
  },
  readTasks: {
    type: Array,
    default: () => []
  },
  edges: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['open-table', 'open-task', 'create-task', 'go-lineage'])

const upstreamDiagramRef = ref(null)
const downstreamDiagramRef = ref(null)
const upstreamLines = ref([])
const downstreamLines = ref([])
let resizeObserver = null
const tablePreviewVisible = ref(false)
const tablePreview = ref(null)
const ORDER_FALLBACK = Number.MAX_SAFE_INTEGER

const orderedDownstreamTables = computed(() => {
  const tables = Array.isArray(props.downstreamTables) ? [...props.downstreamTables] : []
  if (!tables.length || !Array.isArray(props.edges) || props.edges.length === 0) {
    return tables
  }

  const currentTableId = String(props.currentTable?.id || '')
  const readTaskOrder = new Map((props.readTasks || []).map((task, index) => [String(task.id), index]))
  const originalOrder = new Map(tables.map((table, index) => [String(table.id), index]))
  const rankByTable = new Map()

  props.edges.forEach((edge) => {
    const taskId = edge?.taskId !== undefined && edge?.taskId !== null ? String(edge.taskId) : ''
    const targetId = edge?.target !== undefined && edge?.target !== null ? String(edge.target) : ''
    if (!taskId || !targetId || targetId === currentTableId) return

    const taskOrder = readTaskOrder.has(taskId) ? readTaskOrder.get(taskId) : ORDER_FALLBACK
    const existing = rankByTable.get(targetId)
    if (existing === undefined || taskOrder < existing) {
      rankByTable.set(targetId, taskOrder)
    }
  })

  return tables.sort((a, b) => {
    const aId = String(a?.id || '')
    const bId = String(b?.id || '')
    const aRank = rankByTable.has(aId) ? rankByTable.get(aId) : ORDER_FALLBACK
    const bRank = rankByTable.has(bId) ? rankByTable.get(bId) : ORDER_FALLBACK
    if (aRank !== bRank) return aRank - bRank
    return (originalOrder.get(aId) || 0) - (originalOrder.get(bId) || 0)
  })
})

const orderedReadTasks = computed(() => {
  const tasks = Array.isArray(props.readTasks) ? [...props.readTasks] : []
  if (!tasks.length || !Array.isArray(props.edges) || props.edges.length === 0) {
    return tasks
  }

  const currentTableId = String(props.currentTable?.id || '')
  const downstreamOrder = new Map(orderedDownstreamTables.value.map((table, index) => [String(table.id), index]))
  const originalOrder = new Map(tasks.map((task, index) => [String(task.id), index]))
  const rankByTask = new Map()

  props.edges.forEach((edge) => {
    const taskId = edge?.taskId !== undefined && edge?.taskId !== null ? String(edge.taskId) : ''
    const targetId = edge?.target !== undefined && edge?.target !== null ? String(edge.target) : ''
    if (!taskId || !targetId || targetId === currentTableId) return

    const tableOrder = downstreamOrder.has(targetId) ? downstreamOrder.get(targetId) : ORDER_FALLBACK
    const existing = rankByTask.get(taskId)
    if (existing === undefined || tableOrder < existing) {
      rankByTask.set(taskId, tableOrder)
    }
  })

  return tasks.sort((a, b) => {
    const aId = String(a?.id || '')
    const bId = String(b?.id || '')
    const aRank = rankByTask.has(aId) ? rankByTask.get(aId) : ORDER_FALLBACK
    const bRank = rankByTask.has(bId) ? rankByTask.get(bId) : ORDER_FALLBACK
    if (aRank !== bRank) return aRank - bRank
    return (originalOrder.get(aId) || 0) - (originalOrder.get(bId) || 0)
  })
})

const openTablePreview = (table) => {
  if (!table) return
  tablePreview.value = { ...table }
  tablePreviewVisible.value = true
}

const openTableFromPreview = () => {
  if (!tablePreview.value) return
  emit('open-table', tablePreview.value)
  tablePreviewVisible.value = false
}

const resolveTableDatabase = (table) => {
  if (!table) return '-'
  return table.dbName || table.databaseName || table.database || '-'
}

// 获取节点右边缘中心（用于连线起点）
const getNodeRightEdge = (container, nodeId) => {
  if (!container) return null
  const node = container.querySelector(`[data-node-id="${nodeId}"]`)
  if (!node) return null

  const containerRect = container.getBoundingClientRect()
  const nodeRect = node.getBoundingClientRect()

  return {
    x: nodeRect.left - containerRect.left + nodeRect.width,
    y: nodeRect.top - containerRect.top + nodeRect.height / 2
  }
}

// 获取节点左边缘中心（用于连线终点）
const getNodeLeftEdge = (container, nodeId) => {
  if (!container) return null
  const node = container.querySelector(`[data-node-id="${nodeId}"]`)
  if (!node) return null

  const containerRect = container.getBoundingClientRect()
  const nodeRect = node.getBoundingClientRect()

  return {
    x: nodeRect.left - containerRect.left - 8,
    y: nodeRect.top - containerRect.top + nodeRect.height / 2
  }
}

// 生成贝塞尔曲线路径
const generateCurvePath = (start, end) => {
  if (!start || !end) return ''

  const dx = end.x - start.x
  const controlOffset = Math.abs(dx) * 0.4

  return `M ${start.x} ${start.y} C ${start.x + controlOffset} ${start.y}, ${end.x - controlOffset} ${end.y}, ${end.x} ${end.y}`
}

// 计算上游连线
const calculateUpstreamLines = () => {
  if (!upstreamDiagramRef.value) return

  const lines = []
  const container = upstreamDiagramRef.value
  const currentTableId = String(props.currentTable?.id || '')
  const writeTaskIds = new Set(props.writeTasks.map(t => String(t.id)))
  const uniqueKeys = new Set()

  if (props.edges && props.edges.length > 0) {
    // 基于 edges 的 taskId 精确连线
    const writeEdges = props.edges.filter(e => String(e.target) === currentTableId && e.taskId)
    const writeTaskIdSet = new Set(writeEdges.map(e => String(e.taskId)).filter(id => writeTaskIds.has(id)))

    writeTaskIdSet.forEach(taskId => {
      const readEdges = props.edges.filter(e => String(e.taskId) === taskId && String(e.source) !== currentTableId)
      readEdges.forEach(edge => {
        const key = `up-table-${edge.source}-task-${taskId}`
        if (!uniqueKeys.has(key)) {
          uniqueKeys.add(key)
          const start = getNodeRightEdge(container, `upstream-table-${edge.source}`)
          const end = getNodeLeftEdge(container, `write-task-${taskId}`)
          if (start && end) {
            lines.push({
              id: key,
              path: generateCurvePath(start, end),
              className: 'connection-line-table-task'
            })
          }
        }
      })

      // 写入任务 -> 当前表
      const taskKey = `write-task-${taskId}-current`
      if (!uniqueKeys.has(taskKey)) {
        uniqueKeys.add(taskKey)
        const start = getNodeRightEdge(container, `write-task-${taskId}`)
        const end = getNodeLeftEdge(container, `current-table-${currentTableId}`)
        if (start && end) {
          lines.push({
            id: taskKey,
            path: generateCurvePath(start, end),
            className: 'connection-line-task-table'
          })
        }
      }
    })
  } else {
    // 无 edges 数据时，使用全连接 fallback
    props.upstreamTables.forEach((table) => {
      props.writeTasks.forEach((task) => {
        const start = getNodeRightEdge(container, `upstream-table-${table.id}`)
        const end = getNodeLeftEdge(container, `write-task-${task.id}`)
        if (start && end) {
          lines.push({
            id: `up-table-${table.id}-task-${task.id}`,
            path: generateCurvePath(start, end),
            className: 'connection-line-table-task'
          })
        }
      })
    })

    // 写入任务 -> 当前表
    props.writeTasks.forEach((task) => {
      const start = getNodeRightEdge(container, `write-task-${task.id}`)
      const end = getNodeLeftEdge(container, `current-table-${props.currentTable?.id}`)
      if (start && end) {
        lines.push({
          id: `write-task-${task.id}-current`,
          path: generateCurvePath(start, end),
          className: 'connection-line-task-table'
        })
      }
    })
  }

  upstreamLines.value = lines
}

// 计算下游连线
const calculateDownstreamLines = () => {
  if (!downstreamDiagramRef.value) return

  const lines = []
  const container = downstreamDiagramRef.value
  const currentTableId = String(props.currentTable?.id || '')
  const readTaskIds = new Set(orderedReadTasks.value.map(t => String(t.id)))
  const uniqueKeys = new Set()

  if (props.edges && props.edges.length > 0) {
    // 基于 edges 的 taskId 精确连线
    const readEdges = props.edges.filter(e => String(e.source) === currentTableId && e.taskId)
    const readTaskIdSet = new Set(readEdges.map(e => String(e.taskId)).filter(id => readTaskIds.has(id)))

    // 当前表 -> 读取任务
    readTaskIdSet.forEach(taskId => {
      const taskKey = `current-read-task-${taskId}`
      if (!uniqueKeys.has(taskKey)) {
        uniqueKeys.add(taskKey)
        const start = getNodeRightEdge(container, `current-table-down-${currentTableId}`)
        const end = getNodeLeftEdge(container, `read-task-${taskId}`)
        if (start && end) {
          lines.push({
            id: taskKey,
            path: generateCurvePath(start, end),
            className: 'connection-line-table-task'
          })
        }
      }

      // 对每个读取任务，找它写入了哪些下游表
      const writeEdges = props.edges.filter(e => String(e.taskId) === taskId && String(e.target) !== currentTableId)
      writeEdges.forEach(edge => {
        const key = `read-task-${taskId}-table-${edge.target}`
        if (!uniqueKeys.has(key)) {
          uniqueKeys.add(key)
          const start = getNodeRightEdge(container, `read-task-${taskId}`)
          const end = getNodeLeftEdge(container, `downstream-table-${edge.target}`)
          if (start && end) {
            lines.push({
              id: key,
              path: generateCurvePath(start, end),
              className: 'connection-line-task-table'
            })
          }
        }
      })
    })
  } else {
    // 无 edges 数据时，使用全连接 fallback
    // 当前表 -> 读取任务
    orderedReadTasks.value.forEach((task) => {
      const start = getNodeRightEdge(container, `current-table-down-${props.currentTable?.id}`)
      const end = getNodeLeftEdge(container, `read-task-${task.id}`)
      if (start && end) {
        lines.push({
          id: `current-read-task-${task.id}`,
          path: generateCurvePath(start, end),
          className: 'connection-line-table-task'
        })
      }
    })

    // 读取任务 -> 下游表
    orderedReadTasks.value.forEach((task) => {
      orderedDownstreamTables.value.forEach((table) => {
        const start = getNodeRightEdge(container, `read-task-${task.id}`)
        const end = getNodeLeftEdge(container, `downstream-table-${table.id}`)
        if (start && end) {
          lines.push({
            id: `read-task-${task.id}-table-${table.id}`,
            path: generateCurvePath(start, end),
            className: 'connection-line-task-table'
          })
        }
      })
    })
  }

  downstreamLines.value = lines
}

// 重新计算所有连线
const recalculateLines = () => {
  nextTick(() => {
    calculateUpstreamLines()
    calculateDownstreamLines()
  })
}

// 监听数据变化，重新计算连线
watch(
  () => [props.upstreamTables, props.writeTasks, orderedReadTasks.value, orderedDownstreamTables.value, props.edges, props.currentTable],
  () => {
    recalculateLines()
  },
  { deep: true, immediate: true }
)

// 监听容器大小变化，动态重绘连线
onMounted(() => {
  resizeObserver = new ResizeObserver(() => {
    recalculateLines()
  })
  // 观察上游和下游的 diagram 容器
  if (upstreamDiagramRef.value) {
    resizeObserver.observe(upstreamDiagramRef.value)
  }
  if (downstreamDiagramRef.value) {
    resizeObserver.observe(downstreamDiagramRef.value)
  }
})

onBeforeUnmount(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
})
</script>

<style scoped>
.lineage-panel {
  border: none;
  border-radius: 0;
  background: var(--panel);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  max-height: none;
  overflow-y: auto;
  overflow-x: hidden;
  flex: 1;
}

.lineage-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.header-main {
  min-width: 0;
}

.header-title-row {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.section-title {
  font-size: var(--studio-font-reading);
  font-weight: 600;
  color: var(--text);
}

.header-tip-icon {
  font-size: 14px;
  color: var(--text-muted);
  cursor: help;
  transition: color 0.2s ease;
}

.header-tip-icon:hover {
  color: var(--accent);
}

.flow-section {
  border: none;
  border-radius: 0;
  background: transparent;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.flow-section-title {
  font-size: var(--studio-font-reading);
  font-weight: 600;
  color: var(--text);
  padding-bottom: 4px;
  border-bottom: 1px solid var(--line);
}

.lineage-diagram {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
  min-height: 120px;
}

.diagram-column {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  position: relative;
  z-index: 1;
}

.column-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--studio-font-reading);
  font-weight: 700;
  color: var(--text-sub);
  padding-bottom: 6px;
}

.node-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  padding-right: 4px;
}

.lineage-node {
  border: 1px solid var(--line);
  border-radius: 3px;
  background: #fff;
  padding: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: all 0.2s ease;
  cursor: pointer;
  position: relative;
}

.lineage-node:hover {
  border-color: var(--accent);
  background: var(--accent-soft);
  transform: none;
  box-shadow: none;
}

.lineage-node.is-current {
  border-color: var(--accent);
  background: var(--accent-soft);
  box-shadow: none;
  cursor: default;
}

.lineage-node.is-current:hover {
  transform: none;
}

.node-icon {
  color: var(--accent);
  flex-shrink: 0;
  font-size: 16px;
}

.node-content {
  flex: 1;
  min-width: 0;
}

.node-name {
  font-size: var(--studio-font-reading);
  font-weight: 700;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.3;
}

.node-desc {
  margin-top: 2px;
  font-size: var(--studio-font-detail);
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.3;
}

.diagram-current {
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
}

.current-table-node {
  position: relative;
}

.current-node-icon {
  font-size: 16px;
}

.current-node-content {
  flex: 1;
  min-width: 0;
}

.current-node-name {
  margin-bottom: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.current-node-desc {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.node-actions {
  padding-top: 4px;
}

.empty-placeholder {
  font-size: var(--studio-font-reading);
  color: var(--text-muted);
  text-align: center;
  padding: 16px 8px;
  border: none;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.5);
}

.lineage-connections-svg {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 2;
  overflow: visible;
}

.connection-line {
  fill: none;
  stroke: var(--accent);
  stroke-width: 2;
  opacity: 0.4;
  transition: all 0.3s ease;
}

.connection-line-table-task {
  stroke: #5b8ec6;
  opacity: 0.5;
}

.connection-line-task-table {
  stroke: #2f6aa3;
  opacity: 0.6;
}

.connection-line:hover {
  opacity: 1;
  stroke-width: 3;
}

/* 窄侧栏只列关系分组，精确图连线仍在完整血缘画布查看。 */
.lineage-panel {
  container-type: inline-size;
  padding: 14px;
  gap: 20px;
}
@container (max-width: 600px) {
  .lineage-diagram {
    grid-template-columns: minmax(0, 1fr);
    gap: 18px;
    min-height: 0;
  }
  .diagram-current,
  .lineage-connections-svg {
    display: none;
  }
  .flow-section {
    gap: 12px;
  }
  .flow-section-title {
    font-size: var(--studio-font-detail, 10px);
    color: #526174;
    font-weight: 500;
    padding-bottom: 8px;
  }
  .column-header {
    font-size: var(--studio-font-detail);
    color: #8a96a5;
    font-weight: 400;
    padding: 0;
  }
  .column-header .el-icon {
    font-size: var(--studio-font-detail);
  }
  .lineage-node {
    padding: 8px;
    border-color: #e2e8f0;
  }
  .node-icon {
    color: #7b8fa8;
    font-size: 13px;
  }
  .node-name {
    font-size: var(--studio-font-detail);
    font-weight: 500;
    line-height: 16px;
  }
  .node-desc {
    font-size: var(--studio-font-detail);
    line-height: 16px;
  }
  .lineage-node :deep(.el-tag) {
    font-size: var(--studio-font-detail);
    height: 16px;
    padding: 0 4px;
  }
  .node-actions {
    padding: 0;
  }
  .node-actions .el-button {
    font-size: var(--studio-font-detail);
    padding: 0;
    height: 20px;
    border: 0;
    background: transparent;
  }
  .empty-placeholder {
    text-align: left;
    padding: 4px 0;
    font-size: var(--studio-font-detail);
  }
}
</style>
