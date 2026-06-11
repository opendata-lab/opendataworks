<template>
  <div class="skill-detail">
    <div class="skill-detail__topbar">
      <button type="button" class="skill-detail__back" @click="goBack">← Skill 列表</button>
      <span class="skill-detail__slash">/</span>
      <span class="skill-detail__folder">{{ folder || '-' }}</span>
    </div>

    <div class="skill-detail__layout">
      <aside class="skill-detail__sidebar">
        <div v-loading="listLoading" class="skill-panel">
          <template v-if="skillItem">
            <div class="skill-panel__summary">
              <div>
                <div class="skill-panel__headline">{{ skillItem.folder }}</div>
                <div class="skill-panel__meta">
                  {{ sourceLabel(skillItem.source) }} · {{ skillItem.documentCount }} 个文件
                </div>
              </div>
              <el-tag size="small" :type="skillItem.enabled ? 'success' : 'info'">
                {{ skillItem.enabled ? '已启用' : '未启用' }}
              </el-tag>
            </div>

            <div class="skill-panel__runtime">
              <span class="skill-panel__runtime-label">启用</span>
              <el-switch
                :model-value="skillItem.enabled"
                :loading="runtimeUpdating"
                :disabled="runtimeSwitchDisabled"
                :title="runtimeSwitchDisabled ? '至少保留一个启用 Skill' : ''"
                @update:model-value="toggleSkillEnabled"
              />
            </div>

            <el-button
              v-if="skillItem.source === 'managed'"
              class="skill-panel__uninstall"
              type="danger"
              plain
              :loading="uninstallLoading"
              @click="confirmUninstallCurrentSkill"
            >
              卸载 Skill
            </el-button>

            <div class="skill-panel__tree-head">
              <span>文件树</span>
              <span>{{ visibleDocuments.length }}</span>
            </div>

            <el-input
              v-model="searchKeyword"
              clearable
              placeholder="搜索文件名或路径"
              class="skill-panel__search"
            />

            <div v-if="treeNodes.length" class="skill-tree">
              <SkillFileTreeNode
                v-for="node in treeNodes"
                :key="node.key"
                :node="node"
                :selected-document-id="selectedDocumentId"
                @select="loadDocument"
              />
            </div>
            <el-empty v-else description="当前 Skill 还没有可展示的文件" :image-size="84" />
          </template>
          <el-empty v-else description="没有找到当前 Skill" :image-size="90" />
        </div>
      </aside>

      <main class="skill-detail__content">
        <template v-if="detail">
          <section v-loading="detailLoading" class="detail-panel">
            <div class="detail-panel__header">
              <div class="detail-panel__heading">
                <div class="detail-panel__title">{{ detail.file_name }}</div>
                <div class="detail-panel__path">
                  {{ folder }}/{{ detail.relative_path }} · {{ detail.content_type }} ·
                  {{ detail.enabled ? '已启用' : '未启用' }} ·
                  {{ editorDirty ? '未保存' : '已同步' }} ·
                  {{ formatTime(detail.updated_at) }}
                </div>
              </div>
              <div class="detail-panel__actions">
                <el-button @click="resetEditor" :disabled="!editorDirty">重置</el-button>
                <el-button
                  type="primary"
                  :disabled="!detail || detail.editable === false || !editorDirty"
                  :loading="saveLoading"
                  @click="saveDocument"
                >
                  保存
                </el-button>
              </div>
            </div>

            <div class="detail-panel__editor">
              <TextCodeEditor
                v-model="editorContent"
                :read-only="detail.editable === false"
                :placeholder="detail.content_type === 'json' ? '请输入 JSON 内容' : '请输入文件内容'"
              />
            </div>
          </section>

          <section class="detail-panel">
            <div class="detail-panel__header">
              <div class="detail-panel__heading">
                <div class="detail-panel__title">版本历史</div>
                <div class="detail-panel__path">当前版本 {{ currentVersionLabel }}</div>
              </div>
            </div>

            <el-table :data="detail.versions || []" size="small" height="260">
              <el-table-column prop="version_no" label="版本" width="84">
                <template #default="{ row }">
                  <span>V{{ row.version_no }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.is_current ? 'success' : 'info'">
                    {{ row.is_current ? '当前' : '历史' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="change_source" label="来源" width="100" />
              <el-table-column prop="change_summary" label="说明" min-width="220" show-overflow-tooltip />
              <el-table-column label="时间" min-width="170">
                <template #default="{ row }">
                  {{ formatTime(row.created_at) }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-button text type="primary" @click="openCompareDialog(row.id)">对比</el-button>
                  <el-button text type="danger" :disabled="row.is_current" @click="confirmRollback(row)">
                    回滚
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </template>

        <section v-else class="detail-panel detail-panel--empty">
          <el-empty description="请选择一个文件开始查看和编辑" :image-size="110" />
        </section>
      </main>
    </div>

    <el-dialog
      v-model="compareDialogVisible"
      title="版本比对"
      width="86%"
      :close-on-click-modal="false"
    >
      <div v-if="detail" class="compare-toolbar">
        <el-select v-model="leftCompareVersionId" class="compare-toolbar__select" @change="loadCompare">
          <el-option :value="CURRENT_COMPARE_VERSION" label="当前版本" />
          <el-option
            v-for="item in detail.versions || []"
            :key="item.id"
            :value="item.id"
            :label="`V${item.version_no} · ${item.change_source}`"
          />
        </el-select>
        <span class="compare-toolbar__vs">vs</span>
        <el-select v-model="rightCompareVersionId" class="compare-toolbar__select" @change="loadCompare">
          <el-option :value="CURRENT_COMPARE_VERSION" label="当前版本" />
          <el-option
            v-for="item in detail.versions || []"
            :key="item.id"
            :value="item.id"
            :label="`V${item.version_no} · ${item.change_source}`"
          />
        </el-select>
      </div>

      <div v-loading="compareLoading" class="compare-body">
        <div v-if="compareResult" class="compare-body__stats">
          <el-tag size="small" type="primary">{{ compareResult.left_label }}</el-tag>
          <el-tag size="small" type="success">{{ compareResult.right_label }}</el-tag>
          <el-tag size="small" type="warning">变更行 {{ compareResult.changed_lines }}</el-tag>
          <el-tag size="small">+{{ compareResult.added_lines }}</el-tag>
          <el-tag size="small">-{{ compareResult.removed_lines }}</el-tag>
        </div>

        <div v-if="compareResult" class="compare-body__editors">
          <div class="compare-pane">
            <div class="compare-pane__title">{{ compareResult.left_label }}</div>
            <div class="compare-pane__editor">
              <TextCodeEditor :model-value="compareResult.left_content" read-only />
            </div>
          </div>
          <div class="compare-pane">
            <div class="compare-pane__title">{{ compareResult.right_label }}</div>
            <div class="compare-pane__editor">
              <TextCodeEditor :model-value="compareResult.right_content" read-only />
            </div>
          </div>
        </div>

        <div v-if="compareResult" class="compare-pane">
          <div class="compare-pane__title">Unified Diff</div>
          <div class="compare-pane__diff">
            <TextCodeEditor :model-value="compareResult.diff_text" read-only />
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dataagentApi } from '@/api/dataagent'
import TextCodeEditor from '@/components/TextCodeEditor.vue'
import SkillFileTreeNode from './components/SkillFileTreeNode.vue'
import {
  buildDocumentTree,
  buildSkillItems,
  documentsForFolder,
  pickDefaultDocument,
  sourceLabel
} from './skillAdminShared'

const route = useRoute()
const router = useRouter()

const listLoading = ref(false)
const detailLoading = ref(false)
const saveLoading = ref(false)
const compareLoading = ref(false)
const runtimeUpdating = ref(false)
const uninstallLoading = ref(false)

const documents = ref([])
const selectedDocumentId = ref(null)
const detail = ref(null)
const editorContent = ref('')
const searchKeyword = ref('')

const compareDialogVisible = ref(false)
const compareResult = ref(null)
const CURRENT_COMPARE_VERSION = '__current__'
const leftCompareVersionId = ref(CURRENT_COMPARE_VERSION)
const rightCompareVersionId = ref(CURRENT_COMPARE_VERSION)

const folder = computed(() => String(route.params.folder || ''))

const skillDocuments = computed(() => documentsForFolder(documents.value, folder.value))

const skillItems = computed(() => buildSkillItems(documents.value))

const skillItem = computed(() => skillItems.value.find((item) => item.folder === folder.value) || null)

const enabledSkillCount = computed(() => skillItems.value.filter((item) => item.enabled).length)

const runtimeSwitchDisabled = computed(() => Boolean(skillItem.value?.enabled) && enabledSkillCount.value <= 1)

const visibleDocuments = computed(() => {
  const keyword = String(searchKeyword.value || '').trim().toLowerCase()
  if (!keyword) {
    return skillDocuments.value
  }
  return skillDocuments.value.filter((item) => {
    return String(item.file_name || '').toLowerCase().includes(keyword)
      || String(item.relative_path || '').toLowerCase().includes(keyword)
  })
})

const treeNodes = computed(() => buildDocumentTree(visibleDocuments.value))

const editorDirty = computed(() => {
  return !!detail.value && editorContent.value !== (detail.value.current_content || '')
})

const currentVersionLabel = computed(() => {
  const current = (detail.value?.versions || []).find((item) => item.is_current)
  return current ? `V${current.version_no}` : '-'
})

const formatTime = (value) => {
  if (!value) return '-'
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss')
}

const notifyError = (error, fallbackMessage) => {
  if (!error?.__odwNotified) {
    ElMessage.error(error?.message || fallbackMessage)
  }
}

const goBack = () => {
  router.push({
    path: '/intelligent-query',
    query: { tab: 'skills' }
  })
}

const loadDocument = async (documentId) => {
  if (!documentId) return
  selectedDocumentId.value = documentId
  detailLoading.value = true
  try {
    const payload = await dataagentApi.getSkillDocument(documentId)
    detail.value = payload
    editorContent.value = payload?.current_content || ''
  } catch (error) {
    notifyError(error, '加载 Skill 文件失败')
  } finally {
    detailLoading.value = false
  }
}

const syncSelectionFromDocuments = async () => {
  if (!skillDocuments.value.length) {
    selectedDocumentId.value = null
    detail.value = null
    editorContent.value = ''
    return
  }

  const selectedStillExists = skillDocuments.value.some((item) => item.id === selectedDocumentId.value)
  const nextDocument = selectedStillExists
    ? skillDocuments.value.find((item) => item.id === selectedDocumentId.value)
    : pickDefaultDocument(skillDocuments.value)

  if (nextDocument?.id) {
    await loadDocument(nextDocument.id)
  }
}

const loadDocuments = async () => {
  listLoading.value = true
  try {
    documents.value = await dataagentApi.listSkillDocuments()
    await syncSelectionFromDocuments()
  } catch (error) {
    documents.value = []
    detail.value = null
    editorContent.value = ''
    notifyError(error, '加载 Skill 文件列表失败')
  } finally {
    listLoading.value = false
  }
}

const saveDocument = async () => {
  if (!detail.value) return
  let summary = '前端保存'
  try {
    const promptResult = await ElMessageBox.prompt(
      '请输入本次修改说明，保存后会写入版本记录。',
      '保存文件',
      {
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputPlaceholder: '本次修改说明',
        inputValue: summary,
        inputValidator: (value) => String(value || '').trim().length <= 120 || '修改说明最多 120 个字符'
      }
    )
    summary = String(promptResult?.value || '').trim() || '前端保存'
  } catch {
    return
  }

  saveLoading.value = true
  try {
    const payload = await dataagentApi.updateSkillDocument(detail.value.id, {
      content: editorContent.value,
      change_summary: summary
    })
    detail.value = payload
    editorContent.value = payload.current_content || ''
    await loadDocuments()
    ElMessage.success('文件已保存')
  } catch (error) {
    notifyError(error, '保存 Skill 文件失败')
  } finally {
    saveLoading.value = false
  }
}

const resetEditor = () => {
  if (!detail.value) return
  editorContent.value = detail.value.current_content || ''
}

const loadCompare = async () => {
  if (!detail.value) return
  compareLoading.value = true
  try {
    compareResult.value = await dataagentApi.compareSkillDocument(detail.value.id, {
      left_version_id: normalizeCompareVersionId(leftCompareVersionId.value),
      right_version_id: normalizeCompareVersionId(rightCompareVersionId.value)
    })
  } catch (error) {
    compareResult.value = null
    notifyError(error, '加载版本比对失败')
  } finally {
    compareLoading.value = false
  }
}

const openCompareDialog = async (leftVersionId = null) => {
  if (!detail.value) return
  const fallbackHistory = (detail.value.versions || []).find((item) => !item.is_current)
  leftCompareVersionId.value = leftVersionId ?? fallbackHistory?.id ?? CURRENT_COMPARE_VERSION
  rightCompareVersionId.value = CURRENT_COMPARE_VERSION
  compareDialogVisible.value = true
  await loadCompare()
}

const normalizeCompareVersionId = (versionId) => (
  versionId === CURRENT_COMPARE_VERSION ? null : versionId
)

const confirmRollback = async (version) => {
  if (!detail.value || !version?.id || version.is_current) return
  try {
    await ElMessageBox.confirm(
      `确认回滚 ${detail.value.file_name} 到 V${version.version_no} 吗？`,
      '版本回滚',
      {
        type: 'warning',
        confirmButtonText: '确认回滚',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  try {
    const payload = await dataagentApi.rollbackSkillDocument(detail.value.id, version.id)
    detail.value = payload
    editorContent.value = payload.current_content || ''
    await loadDocuments()
    ElMessage.success(`已回滚到 V${version.version_no}`)
  } catch (error) {
    notifyError(error, '回滚版本失败')
    await loadDocuments()
  }
}

const toggleSkillEnabled = async (enabled) => {
  if (!folder.value || Boolean(enabled) === Boolean(skillItem.value?.enabled)) return
  if (!enabled && runtimeSwitchDisabled.value) {
    ElMessage.warning('至少需要保留一个启用 Skill')
    return
  }
  runtimeUpdating.value = true
  try {
    await dataagentApi.updateSkillRuntime(folder.value, { enabled: Boolean(enabled) })
    await loadDocuments()
    ElMessage.success(enabled ? `Skill「${folder.value}」已启用` : `Skill「${folder.value}」已禁用`)
  } catch (error) {
    notifyError(error, '更新 Skill 启停状态失败')
    await loadDocuments()
  } finally {
    runtimeUpdating.value = false
  }
}

const confirmUninstallCurrentSkill = async () => {
  if (!folder.value || skillItem.value?.source !== 'managed') return
  try {
    await ElMessageBox.prompt(
      `请输入 ${folder.value} 确认卸载。`,
      '卸载 Skill',
      {
        type: 'warning',
        confirmButtonText: '确认卸载',
        cancelButtonText: '取消',
        inputPlaceholder: folder.value,
        inputValidator: (value) => String(value || '').trim() === folder.value || `请输入 ${folder.value}`
      }
    )
  } catch {
    return
  }

  uninstallLoading.value = true
  try {
    await dataagentApi.uninstallSkill(folder.value)
    ElMessage.success(`Skill「${folder.value}」已卸载`)
    goBack()
  } catch (error) {
    notifyError(error, '卸载 Skill 失败')
  } finally {
    uninstallLoading.value = false
  }
}

watch(
  () => folder.value,
  async () => {
    await loadDocuments()
  }
)

onMounted(async () => {
  await loadDocuments()
})
</script>

<style scoped>
.skill-detail {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  max-width: 100%;
  overflow-x: hidden;
  box-sizing: border-box;
}

.skill-detail__topbar {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
}

.skill-detail__back {
  border: 0;
  padding: 0;
  background: transparent;
  color: #2563eb;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
}

.skill-detail__back:hover {
  color: #1d4ed8;
}

.skill-detail__slash {
  color: #94a3b8;
}

.skill-detail__folder {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  min-width: 0;
  word-break: break-all;
}

.skill-detail__layout {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
  min-width: 0;
}

.skill-detail__sidebar,
.skill-detail__content {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.skill-panel,
.detail-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border: 1px solid #dbe2ea;
  border-radius: 8px;
  background: #fff;
  min-width: 0;
}

.detail-panel--empty {
  min-height: 420px;
  justify-content: center;
}

.detail-panel__title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.skill-panel__summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.skill-panel__headline {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.skill-panel__meta,
.detail-panel__path {
  font-size: 13px;
  color: #64748b;
  line-height: 1.6;
  word-break: break-all;
}

.skill-panel__runtime {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f8fafc;
}

.skill-panel__runtime-label {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
}

.skill-panel__uninstall {
  width: 100%;
}

.detail-panel__meta-text {
  font-size: 12px;
  color: #64748b;
}

.skill-panel__tree-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  color: #334155;
}

.skill-panel__search {
  width: 100%;
}

.skill-tree {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 540px;
  overflow: auto;
}

.detail-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.detail-panel__heading {
  min-width: 0;
}

.detail-panel__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.detail-panel__editor {
  min-height: 380px;
  height: 440px;
}

.compare-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.compare-toolbar__select {
  width: 260px;
}

.compare-toolbar__vs {
  color: #64748b;
  font-size: 13px;
}

.compare-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.compare-body__stats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.compare-body__editors {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  min-width: 0;
}

.compare-pane {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.compare-pane__title {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
}

.compare-pane__editor {
  height: 260px;
}

.compare-pane__diff {
  height: 220px;
}

@media (max-width: 1200px) {
  .skill-detail__layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .skill-detail {
    padding: 12px;
  }

  .skill-detail__topbar,
  .detail-panel__header,
  .detail-panel__actions,
  .compare-toolbar,
  .compare-body__editors,
  .skill-panel__runtime {
    flex-direction: column;
    align-items: stretch;
  }

  .skill-detail__refresh {
    margin-left: 0;
  }

  .compare-toolbar__select {
    width: 100%;
  }

  .compare-body__editors {
    grid-template-columns: 1fr;
  }

  .detail-panel__actions :deep(.el-button) {
    width: 100%;
  }
}
</style>
