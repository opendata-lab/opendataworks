<template>
  <div v-loading="loading" class="meta-section meta-section-fill">
    <section class="section-block section-fill">
      <div class="section-header">
        <div class="section-title">数据新鲜度</div>
        <div class="section-actions">
          <!-- 查看态：操作集中在右上角 -->
          <template v-if="!editing">
            <el-button size="small" :disabled="!tableId || loading || loadError || busy || isDemoMode" :loading="checking" :title="demoTitle" @click="checkNow">
              立即检查
            </el-button>
            <el-button size="small" :disabled="!tableId" @click="openHistory">历史</el-button>
            <el-button size="small" type="primary" :disabled="!tableId || loading || loadError || busy || isDemoMode" :title="demoTitle" @click="beginEdit">
              {{ configured ? '编辑' : '配置' }}
            </el-button>
          </template>
          <!-- 编辑态：删除 / 取消 / 保存 -->
          <template v-else>
            <el-button v-if="configured" size="small" text type="danger" :loading="deleting" :disabled="saving" @click="removeConfig">删除</el-button>
            <el-button size="small" :disabled="busy" @click="cancelEdit">取消</el-button>
            <el-button size="small" type="primary" :loading="saving" :disabled="!tableId || deleting" @click="save">保存</el-button>
          </template>
        </div>
      </div>

      <el-scrollbar class="meta-scroll">
        <!-- 未选择表 -->
        <el-empty v-if="!tableId" :image-size="60" description="未选择表" />

        <!-- 当前表加载失败：不沿用上一张表的结果 -->
        <el-empty v-else-if="loadError" :image-size="60" :description="loadError">
          <el-button size="small" @click="loadFreshness">重试</el-button>
        </el-empty>

        <!-- 未配置且非编辑：引导配置 -->
        <el-empty
          v-else-if="!editing && !configured && !loading"
          :image-size="70"
          description="尚未配置新鲜度"
        >
          <el-button type="primary" size="small" :disabled="busy || isDemoMode" :title="demoTitle" @click="beginEdit">配置新鲜度</el-button>
        </el-empty>

        <!-- 查看态：只读描述卡 -->
        <p v-if="isDemoMode && !editing && configured" class="demo-note">{{ DEMO_NOTE }}</p>
        <el-descriptions v-if="tableId && !loadError && !editing && configured" :column="1" size="small" class="fresh-view">
          <el-descriptions-item>
            <template #label><span class="lbl">状态<Help :tip="TIP.status" /></span></template>
            <div class="fv-status">
              <el-tag :type="latest ? statusType(latest.status) : 'info'" size="small" effect="plain">
                {{ latest ? statusLabel(latest.status) : '未检查' }}
              </el-tag>
              <span class="fv-age">
                <template v-if="!latest">配置后点「立即检查」</template>
                <template v-else-if="latest.reason === 'never_loaded'">从未产出数据</template>
                <template v-else-if="latest.ageSeconds != null">数据年龄 {{ humanizeAge(latest.ageSeconds) }}</template>
              </span>
            </div>
          </el-descriptions-item>
          <el-descriptions-item>
            <template #label><span class="lbl">时间来源<Help :tip="TIP.source" /></span></template>
            <span class="fv-mode">{{ modeLabel(effectiveMode) }}</span>
            <code v-if="viewValueExpr" class="fv-code">{{ viewValueExpr }}</code>
            <span v-else-if="effectiveMode === 'metadata'" class="fv-hint">读表最后更新时间</span>
          </el-descriptions-item>
          <el-descriptions-item>
            <template #label><span class="lbl">时限<Help :tip="TIP.thresholds" /></span></template>
            <span class="th-lab warn">预警</span> {{ thresholdText('warn') }}
            <span class="dot">·</span>
            <span class="th-lab err">过期</span> {{ thresholdText('error') }}
          </el-descriptions-item>
          <el-descriptions-item>
            <template #label><span class="lbl">最新数据时间<Help :tip="TIP.maxLoadedAt" /></span></template>
            {{ fmt(latest && latest.maxLoadedAt) }}
          </el-descriptions-item>
          <el-descriptions-item>
            <template #label><span class="lbl">检查时间<Help :tip="TIP.snapshottedAt" /></span></template>
            {{ fmt(latest && (latest.createdAt || latest.snapshottedAt)) }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- 编辑态：行内表单，不弹层 -->
        <el-form v-if="tableId && editing" class="fresh-form" label-width="92px" label-position="left" size="small" @submit.prevent>
          <el-form-item>
            <template #label><span class="lbl">时间来源<Help :tip="TIP.source" /></span></template>
            <el-select v-model="form.mode" style="width: 100%">
              <el-option label="表字段 · 取某列的最大值" value="column" />
              <el-option label="自定义查询 · 写 SQL 取最新时间" value="custom_sql" />
              <el-option label="表元数据 · 读表最后更新时间" value="metadata" />
            </el-select>
            <div v-if="form.mode === 'metadata'" class="mode-hint">不看数据内容，只能发现长期无写入</div>
          </el-form-item>

          <el-form-item v-if="form.mode === 'column'">
            <template #label><span class="lbl">时间字段<Help :tip="TIP.loadedAtField" /></span></template>
            <el-select
              v-model="form.loadedAtField"
              filterable
              allow-create
              default-first-option
              placeholder="选择时间列"
              style="width: 100%"
              :loading="fieldsLoading"
            >
              <el-option v-for="f in columnOptions" :key="f.fieldName" :label="f.fieldName" :value="f.fieldName">
                <span class="opt-name">{{ f.fieldName }}</span>
                <span class="opt-type">{{ f.fieldType }}</span>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item v-else-if="form.mode === 'custom_sql'">
            <template #label><span class="lbl">自定义查询<Help :tip="TIP.loadedAtQuery" /></span></template>
            <el-input v-model="form.loadedAtQuery" type="textarea" :rows="2" placeholder="SELECT MAX(order_time) FROM db.tbl" />
          </el-form-item>

          <el-form-item>
            <template #label><span class="lbl">时限<Help :tip="TIP.thresholds" /></span></template>
            <div class="thresholds">
              <span class="th-group">
                <span class="th-lab warn">预警</span>
                <el-input-number v-model="form.warnAfterCount" :min="1" :controls="false" class="th-count" />
                <el-select v-model="form.warnAfterPeriod" class="th-period">
                  <el-option label="分" value="minute" /><el-option label="时" value="hour" /><el-option label="天" value="day" />
                </el-select>
              </span>
              <span class="th-group">
                <span class="th-lab err">过期</span>
                <el-input-number v-model="form.errorAfterCount" :min="1" :controls="false" class="th-count" />
                <el-select v-model="form.errorAfterPeriod" class="th-period">
                  <el-option label="分" value="minute" /><el-option label="时" value="hour" /><el-option label="天" value="day" />
                </el-select>
              </span>
            </div>
          </el-form-item>

          <el-form-item>
            <template #label><span class="lbl">过滤<Help :tip="TIP.filter" /></span></template>
            <el-input v-model="form.filterExpr" placeholder="可选 WHERE 谓词" />
          </el-form-item>

          <el-form-item label="启用">
            <el-switch v-model="form.enabled" />
          </el-form-item>
        </el-form>
      </el-scrollbar>
    </section>

    <!-- 历史：按钮点开，不常驻 -->
    <el-dialog v-model="historyVisible" title="新鲜度检查历史" width="640px" append-to-body>
      <el-table v-loading="historyLoading" :data="history" border size="small" max-height="420">
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small" effect="plain">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据年龄" min-width="110">
          <template #default="{ row }">{{ humanizeAge(row.ageSeconds) }}</template>
        </el-table-column>
        <el-table-column label="时间来源" width="110">
          <template #default="{ row }">{{ modeLabel(row.mode) }}</template>
        </el-table-column>
        <el-table-column label="触发" width="80">
          <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!historyLoading && history.length === 0" :image-size="60" description="暂无历史" />
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, h, inject, reactive, ref, watch, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox, ElTooltip, ElIcon } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { tableApi } from '@/api/table'
import { isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'

// 术语后的帮助图标：悬浮展示字段解释。
const Help = (props) =>
  h(
    ElTooltip,
    { content: props.tip, placement: 'top', 'raw-content': false },
    { default: () => h(ElIcon, { class: 'lbl-help' }, { default: () => h(QuestionFilled) }) }
  )
Help.props = ['tip']

// 每个术语一句话；具体取值方式由各选项/字段就地自解释，不堆在一处。
const TIP = {
  status: '最近一次检查结果',
  source: '用什么方式确定数据的最新时间',
  thresholds: '数据落后多久判为预警 / 过期',
  maxLoadedAt: '按时间来源算出的最新一条数据时间',
  snapshottedAt: '本次检查执行的时刻',
  loadedAtField: '取该列的最大值当作最新数据时间',
  loadedAtQuery: '写一段 SQL 返回最新数据时间',
  filter: '只统计满足条件的行，如 dt = current_date - 1',
}

// 演示环境与其他写操作一致：配置、保存、删除和立即检查都会写入真实检查记录，因此只读。
const DEMO_NOTE = '演示样例：展示固定的检查结果与历史，配置与立即检查不可用'
const demoTitle = isDemoMode ? '演示环境仅支持浏览，配置与检查不可用' : undefined

const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('DataStudioRightPanelFreshness 需要 dataStudioCtx')
}

const { activeTab, tabStates, formatDateTime } = ctx

const activeTabId = computed(() => String(activeTab.value || ''))
const table = computed(() => {
  const id = activeTabId.value
  if (!id) return null
  return (tabStates[id] || {}).table || null
})
const tableId = computed(() => table.value?.id || null)

const loading = ref(false)
const checking = ref(false)
const saving = ref(false)
const deleting = ref(false)
const busy = computed(() => checking.value || saving.value || deleting.value)
const editing = ref(false)
const resp = ref(null)
const loadError = ref('')
const fields = ref([])
const fieldsLoading = ref(false)
const history = ref([])
const historyVisible = ref(false)
const historyLoading = ref(false)

const configured = computed(() => Boolean(resp.value?.configured))
const latest = computed(() => resp.value?.latestResult || null)
const columnOptions = computed(() => fields.value)

const DEFAULTS = {
  mode: 'column',
  loadedAtField: '',
  loadedAtQuery: '',
  filterExpr: '',
  warnAfterCount: 1,
  warnAfterPeriod: 'day',
  errorAfterCount: 1,
  errorAfterPeriod: 'day',
  enabled: true,
}

const form = reactive({ ...DEFAULTS })

const fmt = (value) => (value ? formatDateTime(value) : '-')

const STATUS = {
  pass: { label: '正常', type: 'success' },
  warn: { label: '预警', type: 'warning' },
  error: { label: '过期', type: 'danger' },
  runtime_error: { label: '检查失败', type: 'info' },
}
const statusLabel = (s) => STATUS[s]?.label || s || '-'
const statusType = (s) => STATUS[s]?.type || 'info'

const MODE = { column: '表字段', custom_sql: '自定义查询', metadata: '表元数据' }
const modeLabel = (m) => MODE[m] || m || '-'

const PERIOD = { minute: '分钟', hour: '小时', day: '天' }

const TRIGGER = { manual: '手动', schedule: '定时', inspection: '巡检', workflow: '工作流' }
const triggerLabel = (t) => TRIGGER[t] || t || '-'

const effectiveMode = computed(
  () => resp.value?.effective?.mode || resp.value?.config?.mode || 'column'
)
const viewValueExpr = computed(() => {
  const cfg = resp.value?.config || {}
  if (effectiveMode.value === 'column') return cfg.loadedAtField || resp.value?.effective?.loadedAtField || ''
  if (effectiveMode.value === 'custom_sql') return cfg.loadedAtQuery || ''
  return ''
})
const thresholdText = (which) => {
  const eff = resp.value?.effective
  const t = which === 'warn' ? eff?.warnAfter : eff?.errorAfter
  if (!t || t.count == null) return '—'
  return `${t.count} ${PERIOD[t.period] || t.period}`
}

const humanizeAge = (seconds) => {
  if (seconds === null || seconds === undefined) return '-'
  let s = Math.max(0, Number(seconds))
  const d = Math.floor(s / 86400); s -= d * 86400
  const h = Math.floor(s / 3600); s -= h * 3600
  const m = Math.floor(s / 60)
  if (d > 0) return h > 0 ? `${d} 天 ${h} 小时` : `${d} 天`
  if (h > 0) return m > 0 ? `${h} 小时 ${m} 分钟` : `${h} 小时`
  return `${m} 分钟`
}

// 上下文序号在切表/切页签/卸载时失效，覆盖读取及写操作后的 UI 更新。
let contextSeq = 0
let loadSeq = 0
let fieldsSeq = 0
let historySeq = 0
const isCurrent = (context) => context === contextSeq

const resetFormDefaults = () => Object.assign(form, DEFAULTS)

const applyConfigToForm = () => {
  const cfg = resp.value?.config
  if (!cfg) {
    resetFormDefaults()
    return
  }
  form.mode = cfg.mode || 'column'
  form.loadedAtField = cfg.loadedAtField || ''
  form.loadedAtQuery = cfg.loadedAtQuery || ''
  form.filterExpr = cfg.filterExpr || ''
  form.warnAfterCount = cfg.warnAfterCount ?? DEFAULTS.warnAfterCount
  form.warnAfterPeriod = cfg.warnAfterPeriod || DEFAULTS.warnAfterPeriod
  form.errorAfterCount = cfg.errorAfterCount ?? DEFAULTS.errorAfterCount
  form.errorAfterPeriod = cfg.errorAfterPeriod || DEFAULTS.errorAfterPeriod
  form.enabled = cfg.enabled !== false
}

// 列下拉：优先复用当前表已加载的字段，缺失时按需拉取。
const ensureFields = async () => {
  const seq = ++fieldsSeq
  const context = contextSeq
  const cached = (tabStates[activeTabId.value] || {}).fields
  if (Array.isArray(cached) && cached.length) {
    fields.value = cached
    fieldsLoading.value = false
    return
  }
  const targetId = tableId.value
  if (!targetId) return
  fieldsLoading.value = true
  try {
    const list = await tableApi.getFields(targetId)
    if (isCurrent(context) && seq === fieldsSeq) fields.value = Array.isArray(list) ? list : []
  } catch {
    if (isCurrent(context) && seq === fieldsSeq) fields.value = []
  } finally {
    if (isCurrent(context) && seq === fieldsSeq) fieldsLoading.value = false
  }
}

const loadFreshness = async () => {
  const seq = ++loadSeq
  const context = contextSeq
  const targetId = tableId.value
  resp.value = null
  loadError.value = ''
  if (!targetId) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result = await tableApi.getFreshness(targetId)
    if (!isCurrent(context) || seq !== loadSeq) return
    resp.value = result
    applyConfigToForm()
  } catch {
    if (isCurrent(context) && seq === loadSeq) loadError.value = '新鲜度加载失败'
  } finally {
    if (isCurrent(context) && seq === loadSeq) loading.value = false
  }
}

const beginEdit = () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('配置数据新鲜度')
    return
  }
  if (!tableId.value || loading.value || loadError.value || busy.value) return
  applyConfigToForm()
  editing.value = true
  ensureFields()
}

const cancelEdit = () => {
  if (busy.value) return
  editing.value = false
  applyConfigToForm()
}

const checkNow = async () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('立即检查')
    return
  }
  const targetId = tableId.value
  const context = contextSeq
  if (!targetId || busy.value || loading.value || loadError.value) return
  checking.value = true
  try {
    await tableApi.checkFreshness(targetId)
    if (!isCurrent(context)) return
    ElMessage.success('检查完成')
    await loadFreshness()
  } catch {
    // 拦截器已提示错误
  } finally {
    if (isCurrent(context)) checking.value = false
  }
}

const save = async () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('保存新鲜度配置')
    return
  }
  const targetId = tableId.value
  const context = contextSeq
  if (!targetId || busy.value || !editing.value) return
  saving.value = true
  try {
    await tableApi.saveFreshness(targetId, { ...form })
    if (!isCurrent(context)) return
    ElMessage.success('保存成功')
    editing.value = false
    await loadFreshness()
  } catch {
    // 拦截器已提示错误
  } finally {
    if (isCurrent(context)) saving.value = false
  }
}

const removeConfig = async () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('删除新鲜度配置')
    return
  }
  const targetId = tableId.value
  const context = contextSeq
  if (!targetId || busy.value) return
  const targetName = [table.value?.dbName, table.value?.tableName].filter(Boolean).join('.') || `ID ${targetId}`
  deleting.value = true
  try {
    await ElMessageBox.confirm(`删除 “${targetName}” 的配置后，该表将不再检查新鲜度。确认删除？`, '提示', { type: 'warning' })
    // 确认框期间切换目标后，取消原操作，避免删除用户正在查看的另一张表。
    if (!isCurrent(context)) return
    await tableApi.deleteFreshness(targetId)
    if (!isCurrent(context)) return
    ElMessage.success('已删除')
    editing.value = false
    await loadFreshness()
  } catch {
    // 用户取消或拦截器已提示错误
  } finally {
    if (isCurrent(context)) deleting.value = false
  }
}

const openHistory = async () => {
  const seq = ++historySeq
  const context = contextSeq
  const targetId = tableId.value
  history.value = []
  if (!targetId) return
  historyVisible.value = true
  historyLoading.value = true
  try {
    const list = (await tableApi.freshnessHistory(targetId, 50)) || []
    if (isCurrent(context) && seq === historySeq) history.value = list
  } catch {
    if (isCurrent(context) && seq === historySeq) history.value = []
  } finally {
    if (isCurrent(context) && seq === historySeq) historyLoading.value = false
  }
}

watch([activeTabId, tableId], () => {
  contextSeq++
  editing.value = false
  checking.value = false
  saving.value = false
  deleting.value = false
  fields.value = []
  fieldsLoading.value = false
  history.value = []
  historyVisible.value = false
  historyLoading.value = false
  resetFormDefaults()
  loadFreshness()
}, { immediate: true, flush: 'sync' })

onBeforeUnmount(() => { contextSeq++ })

</script>

<style scoped>
/* 术语标签 + 帮助图标 */
.lbl {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.lbl-help {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  cursor: help;
}
.lbl-help:hover {
  color: var(--el-color-primary);
}

/* 查看态描述卡 */
.fresh-view :deep(.el-descriptions__label) {
  width: 108px;
}
.demo-note {
  margin: 0 0 4px;
  font-size: var(--studio-font-detail);
  line-height: 16px;
  color: var(--el-text-color-secondary);
}
.fv-status {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fv-age {
  font-size: var(--studio-font-reading);
  color: var(--el-text-color-secondary);
}
.fv-mode {
  font-weight: 600;
}
.fv-hint {
  margin-left: 8px;
  font-size: var(--studio-font-reading);
  color: var(--el-text-color-secondary);
}
.fv-code {
  margin-left: 8px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-family: var(--el-font-family-mono, monospace);
  font-size: var(--studio-font-reading);
  color: var(--el-text-color-regular);
}
.dot {
  margin: 0 6px;
  color: var(--el-text-color-disabled);
}

/* 编辑态表单 */
.fresh-form {
  margin-bottom: 4px;
}
.fresh-form :deep(.el-form-item) {
  margin-bottom: 12px;
}
.fresh-form :deep(.el-form-item__label) {
  color: var(--el-text-color-regular);
}

.opt-name {
  float: left;
}
.opt-type {
  float: right;
  color: var(--el-text-color-secondary);
  font-size: var(--studio-font-reading);
}

.mode-hint {
  margin-top: 4px;
  font-size: var(--studio-font-reading);
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.thresholds {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
}
.th-group {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.th-lab {
  font-size: var(--studio-font-reading);
}
.th-lab.warn {
  color: var(--el-color-warning);
}
.th-lab.err {
  color: var(--el-color-danger);
}
.th-count {
  width: 66px;
}
.th-period {
  width: 62px;
}

.fresh-view :deep(.el-descriptions__cell) {
  padding: 10px 0 12px;
  line-height: 22px;
}
.fresh-view :deep(.el-descriptions__label) {
  display: block;
  font-size: var(--studio-font-detail);
  color: #8794a5;
  font-weight: 400;
  margin-bottom: 4px;
}
.fresh-view :deep(.el-descriptions__content) {
  font-size: var(--studio-font-ui);
  color: #41536a;
}
.fresh-view :deep(.el-descriptions__body) {
  background: transparent;
}
.section-actions :deep(.el-button) {
  padding: 4px 7px;
  font-size: var(--studio-font-detail);
}
</style>
