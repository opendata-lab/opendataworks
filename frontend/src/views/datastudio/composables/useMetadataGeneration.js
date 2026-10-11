import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'
import { nl2sqlApi, nl2sqlErrorMessage } from '@/api/nl2sql'
import { settingsApi } from '@/api/settings'
import { businessDomainApi, dataDomainApi } from '@/api/domain'
import { tableApi } from '@/api/table'
import { getObjectSourceId } from '../objectSource'
import { buildFieldPayload } from '../fieldEdit'
import {
  buildMetadataPrompt,
  buildObservedValueIndex,
  describeFreshnessConfig,
  filterEnumValuesByObserved,
  filterFreshness,
  filterTableAttributes,
  formatFieldComment,
  formatFreshnessContract,
  normalizeFreshnessConfig,
  parseMetadataResponse,
  sameFreshnessContract
} from '../metadataGeneration'

// 智能元数据：复用智能问数的发送消息端点发起后台任务，解析助手消息中的格式化内容，
// 经弹窗复核后采纳，直接走既有表/字段写回接口。
//
// 枚举取值不由模型推导：生成前先经 tableApi.profileColumnValues 查该表真实取值，
// 既进 prompt 也用于写回前过滤，模型给出的清单外取值一律丢弃。
//
// 建议结果为内存态：useTabPersistence 只持久化 tab 骨架，刷新页面后需重新生成。

const TERMINAL_STATUSES = new Set(['finished', 'success', 'completed', 'error', 'failed', 'suspended'])
const SUCCESS_STATUSES = new Set(['finished', 'success', 'completed'])
const POLL_INTERVAL_MS = 2000
const MAX_WAIT_MS = 300000
const MAX_TASK_SQL = 5

// 表属性建议的展示顺序与中文名；layer 必须在最前，采纳时它是 update 接口的必填项
const TABLE_ATTRIBUTE_META = [
  { key: 'layer', label: '分层' },
  { key: 'businessDomain', label: '业务域' },
  { key: 'dataDomain', label: '数据域' }
]

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// 建话题必须带 agent_id：省略时后端回落到 DEFAULT_AGENT_ID，若其可见范围不含匿名调用，
// 会返回 400 "agent not found"。使用哪个助手由「配置管理 / 智能助手」显式配置，
// 不在代码里隐式挑选；这里只校验该助手当前确实可用（助手目录与建话题共用同一套可见性过滤）。
const resolveAgentId = async () => {
  const [settings, agents] = await Promise.all([
    settingsApi.getAgentSettings().catch(() => ({})),
    nl2sqlApi.listAgents().catch(() => [])
  ])

  const configured = String(settings?.metadataAgentId || '').trim()
  if (!configured) {
    throw new Error('尚未配置智能元数据使用的助手，请前往「配置管理 / 智能助手」选择')
  }

  const available = (Array.isArray(agents) ? agents : [])
    .map((item) => String(item?.agent_id || '').trim())
    .filter(Boolean)
  if (available.length && !available.includes(configured)) {
    throw new Error(`配置的助手 ${configured} 当前不可用，请前往「配置管理 / 智能助手」重新选择`)
  }
  return configured
}

const messageText = (message) => {
  if (!message) return ''
  if (Array.isArray(message.blocks)) {
    const parts = message.blocks
      .filter((block) => block && block.type === 'main_text' && String(block.text || '').trim())
      .map((block) => String(block.text).trim())
    if (parts.length) return parts.join('\n\n')
  }
  return String(message.content || '').trim()
}

export function useMetadataGeneration({
  clusterId,
  tabStates,
  taskApi,
  loadDdl,
  layerOptions,
  warnPlatformMetadataMissing
}) {
  const metadataGenerating = ref(false)
  const metadataAdopting = ref(false)
  const metadataDialogVisible = ref(false)
  const metadataResult = ref(null)

  const collectRelatedTasks = async (state) => {
    const picked = [
      ...(state.tasks?.writeTasks || []),
      ...(state.tasks?.readTasks || [])
    ].slice(0, MAX_TASK_SQL)

    const collected = []
    for (const item of picked) {
      try {
        const detail = await taskApi.getById(item.id)
        const taskSql = detail?.taskSql || detail?.task_sql || ''
        if (String(taskSql).trim()) {
          collected.push({
            taskName: item.taskName || detail?.taskName || '',
            engine: item.engine || detail?.engine || '',
            relationType: item.relationType || '',
            taskSql
          })
        }
      } catch (error) {
        console.warn('获取关联任务代码失败', item?.id, error)
      }
    }
    return collected
  }

  // 先查一次再等待：任务若立即失败（如未配置模型服务），能马上把错误反馈出来
  const waitForResult = async (taskId) => {
    const deadline = Date.now() + MAX_WAIT_MS
    while (Date.now() < deadline) {
      let task = null
      try {
        task = await nl2sqlApi.getTask(taskId)
      } catch {
        // 轮询期间的瞬时错误不终止等待，交给超时兜底
      }
      const status = String(task?.task_status || '').toLowerCase()
      if (status && TERMINAL_STATUSES.has(status)) {
        if (!SUCCESS_STATUSES.has(status)) {
          throw new Error(`生成任务未成功（状态：${status}）`)
        }
        return
      }
      await sleep(POLL_INTERVAL_MS)
    }
    throw new Error('生成超时，请稍后重试')
  }

  // 只有实测取值分布里出现过的枚举值才允许写进描述：模型编造的取值在这里被丢掉
  const collectColumnValueProfiles = async (state) => {
    try {
      const profiles = await tableApi.profileColumnValues(state.table.id, getObjectSourceId(state.table, clusterId.value))
      return Array.isArray(profiles) ? profiles : []
    } catch (error) {
      console.warn('获取字段实测取值失败，本次不生成枚举', error)
      return []
    }
  }

  // 现有新鲜度契约：既喂给模型（避免重复建议），也用于弹窗展示「当前值」
  const collectCurrentFreshness = async (state) => {
    try {
      const resp = await tableApi.getFreshness(state.table.id)
      return resp?.configured ? resp.config || null : null
    } catch (error) {
      console.warn('获取现有新鲜度契约失败', error)
      return null
    }
  }

  // 分层/业务域/数据域的候选取值：既喂给模型，也用于写回前硬过滤
  const collectAttributeOptions = async () => {
    const [businessDomains, dataDomains] = await Promise.all([
      businessDomainApi.list().catch(() => []),
      dataDomainApi.list().catch(() => [])
    ])
    return {
      layerOptions: layerOptions || [],
      businessDomains: Array.isArray(businessDomains) ? businessDomains : [],
      dataDomains: Array.isArray(dataDomains) ? dataDomains : []
    }
  }

  const buildAttributeRows = (state, parsed, attributeOptions) => {
    const suggested = filterTableAttributes(parsed.tableAttributes, attributeOptions)
    return TABLE_ATTRIBUTE_META.map(({ key, label }) => {
      const currentValue = String(state.table?.[key] || '')
      const suggestedValue = String(suggested[key] || '')
      return {
        key,
        label,
        currentValue,
        suggestedValue,
        hasRecommendation: !!suggestedValue && suggestedValue !== currentValue
      }
    })
  }

  // 新鲜度建议块：loaded_at_field 必须是真实字段（filterFreshness 已约束），
  // 与现有 column 契约一致时不算「可采纳建议」。
  const buildFreshnessBlock = (state, parsed, currentFreshness) => {
    const suggested = filterFreshness(parsed.freshness, { fields: state.fields || [] })
    const current = normalizeFreshnessConfig(currentFreshness)
    const hasRecommendation = !!suggested && !sameFreshnessContract(current, suggested)
    return {
      currentText: describeFreshnessConfig(currentFreshness),
      suggested,
      suggestedText: suggested ? formatFreshnessContract(suggested) : '',
      hasRecommendation
    }
  }

  const buildResult = (tabId, state, parsed, columnValueProfiles, attributeOptions, currentFreshness) => {
    const currentByName = new Map((state.fields || []).map((field) => [field.fieldName, field]))
    const observedByField = buildObservedValueIndex(columnValueProfiles)
    const fields = parsed.fields
      .filter((item) => currentByName.has(item.fieldName))
      .map((item) => {
        const current = currentByName.get(item.fieldName)
        const enumValues = filterEnumValuesByObserved(item.enumValues, observedByField.get(item.fieldName))
        const suggestedComment = formatFieldComment(item.comment, enumValues)
        const currentComment = current?.fieldComment || ''
        return {
          fieldId: current?.id,
          fieldName: item.fieldName,
          fieldType: current?.fieldType || '',
          currentComment,
          suggestedComment,
          hasRecommendation: !!suggestedComment && suggestedComment !== currentComment
        }
      })

    // 供主字段表的「智能描述」内联列消费
    state.metaSuggestion = {
      fields: fields.map((field) => ({
        fieldName: field.fieldName,
        suggestedComment: field.suggestedComment
      }))
    }

    const tableComment = state.table.tableComment || ''
    return {
      tabId,
      tableName: state.table.tableName || '',
      table: {
        currentComment: tableComment,
        suggestedComment: parsed.tableComment,
        hasRecommendation: !!parsed.tableComment && parsed.tableComment !== tableComment
      },
      attributes: buildAttributeRows(state, parsed, attributeOptions),
      freshness: buildFreshnessBlock(state, parsed, currentFreshness),
      fields
    }
  }

  // force=true 时忽略已有结果重新生成；否则已有结果直接打开弹窗
  const generateMetadata = async (tabId, { force = false } = {}) => {
    if (isDemoMode) {
      showDemoReadonlyMessage('智能元数据')
      return
    }
    const state = tabStates[tabId]
    if (!state?.table?.id) return
    if (warnPlatformMetadataMissing(state.table)) return
    if (!force && metadataResult.value?.tabId === tabId) {
      metadataDialogVisible.value = true
      return
    }
    if (metadataGenerating.value) return

    metadataGenerating.value = true
    try {
      if (!state.ddl && typeof loadDdl === 'function') {
        try {
          await loadDdl(tabId)
        } catch {
          // DDL 缺失不阻断生成，其余上下文仍可用
        }
      }

      const relatedTasks = await collectRelatedTasks(state)
      const columnValueProfiles = await collectColumnValueProfiles(state)
      const attributeOptions = await collectAttributeOptions()
      const currentFreshness = await collectCurrentFreshness(state)
      const prompt = buildMetadataPrompt({
        dbName: state.table.dbName,
        tableName: state.table.tableName,
        tableType: state.table.tableType,
        layer: state.table.layer,
        tableComment: state.table.tableComment,
        ddl: state.ddl,
        fields: (state.fields || []).map((field) => ({
          fieldName: field.fieldName,
          fieldType: field.fieldType,
          fieldComment: field.fieldComment
        })),
        upstreamTables: state.lineage?.upstreamTables || [],
        downstreamTables: state.lineage?.downstreamTables || [],
        relatedTasks,
        columnValueProfiles,
        currentFreshness,
        ...attributeOptions
      })

      const agentId = await resolveAgentId()
      const topic = await nl2sqlApi.createTopic({
        title: `元数据生成: ${state.table.dbName || ''}.${state.table.tableName || ''}`,
        agent_id: agentId
      })
      const topicId = topic?.topic_id || topic?.id
      if (!topicId) throw new Error('创建会话失败')

      const submitted = await nl2sqlApi.deliverMessage({
        topic_id: topicId,
        content: prompt,
        agent_id: agentId,
        execution_mode: 'background'
      })
      const taskId = submitted?.task_id || submitted?.taskId
      if (!taskId) throw new Error('发起生成任务失败')

      await waitForResult(taskId)
      const parsed = parseMetadataResponse(messageText(await nl2sqlApi.getTaskMessage(taskId)))

      metadataResult.value = buildResult(tabId, state, parsed, columnValueProfiles, attributeOptions, currentFreshness)
      metadataDialogVisible.value = true
      if (
        !metadataResult.value.table.hasRecommendation &&
        !metadataResult.value.attributes.some((item) => item.hasRecommendation) &&
        !metadataResult.value.freshness.hasRecommendation &&
        !metadataResult.value.fields.some((field) => field.hasRecommendation)
      ) {
        ElMessage.warning('AI 未生成可采纳的元数据建议')
      }
    } catch (error) {
      ElMessage.error(nl2sqlErrorMessage(error, '智能生成元数据失败'))
    } finally {
      metadataGenerating.value = false
    }
  }

  // payload: { table: { text } | null, attributes: [{ key, value }], freshness: contract | null, fields: [{ fieldName, text }] }
  const adoptMetadata = async (tabId, payload = {}) => {
    const state = tabStates[tabId]
    if (!state?.table?.id) return
    const { table = null, attributes = [], freshness = null, fields = [] } = payload
    const sourceId = getObjectSourceId(state.table, clusterId.value)

    metadataAdopting.value = true
    try {
      if (table && String(table.text || '').trim()) {
        await tableApi.updateComment(state.table.id, table.text, sourceId)
        state.table.tableComment = table.text
        if (state.metaForm) state.metaForm.tableComment = table.text
        if (state.metaOriginal) state.metaOriginal.tableComment = table.text
      }

      const adoptedAttrs = (attributes || []).filter((item) => item?.key && String(item.value || '').trim())
      if (adoptedAttrs.length) {
        const next = {}
        adoptedAttrs.forEach((item) => {
          next[item.key] = String(item.value).trim()
        })
        // updateTable 强制校验分层非空，而缺分层的表正是本功能的目标对象：
        // 采纳属性时必须带上有效分层，否则后端会以「数据分层不能为空」失败
        const effectiveLayer = next.layer || state.table.layer || ''
        if (!effectiveLayer) {
          throw new Error('该表尚未设置数据分层，请同时采纳「分层」建议，或先手动设置分层')
        }
        // 只带必要字段：MyBatis-Plus 按非空字段更新，其余字段不受影响；
        // tableComment/bucketNum/replicaNum 不在此提交，避免触发 Doris 物理变更
        const updated = await tableApi.update(
          state.table.id,
          { layer: effectiveLayer, ...next },
          sourceId
        )
        state.table = { ...state.table, ...(updated || next), layer: effectiveLayer }
        if (state.metaForm) {
          state.metaForm.layer = state.table.layer || ''
          state.metaForm.businessDomain = state.table.businessDomain || ''
          state.metaForm.dataDomain = state.table.dataDomain || ''
        }
      }

      for (const item of fields) {
        const original = (state.fields || []).find((field) => field.fieldName === item.fieldName)
        if (!original?.id) continue
        await tableApi.updateField(
          state.table.id,
          original.id,
          buildFieldPayload({ ...original, fieldComment: item.text }),
          sourceId
        )
      }

      // 新鲜度契约：走既有 upsert 接口（loaded_at_field 已在建议阶段校验为真实字段）
      if (freshness && String(freshness.loadedAtField || '').trim()) {
        await tableApi.saveFreshness(state.table.id, freshness)
      }

      // 刷新字段：内联列的「已采纳」态与元数据完善度随之更新
      const list = await tableApi.getFields(state.table.id)
      if (Array.isArray(list)) state.fields = list

      ElMessage.success('已采纳并保存')
      metadataDialogVisible.value = false
    } catch (error) {
      ElMessage.error(error?.message || '采纳失败')
    } finally {
      metadataAdopting.value = false
    }
  }

  return {
    metadataGenerating,
    metadataAdopting,
    metadataDialogVisible,
    metadataResult,
    generateMetadata,
    adoptMetadata
  }
}
