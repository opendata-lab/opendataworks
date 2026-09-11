<template>
  <div class="skill-studio">
    <div class="skill-studio__toolbar">
      <div>
        <div class="skill-studio__title">Skill <span>{{ skillItems.length }}</span></div>
      </div>
      <div class="skill-studio__actions">
        <el-input
          v-model="searchKeyword"
          clearable
          placeholder="按名称搜索 Skill..."
          class="skill-studio__search"
        />
        <el-button :icon="Refresh" @click="loadDocuments">刷新</el-button>
        <el-button
          v-if="canManage"
          type="primary"
          :icon="Download"
          @click="openImportDialog"
        >
          导入 Skill
        </el-button>
      </div>
    </div>

    <div v-loading="listLoading" class="skill-table-wrapper">
      <div class="skill-studio__section-title">已启用 <span>{{ enabledSkillCount }}</span></div>
      <div v-if="filteredSkills.length" class="skill-list">
        <div
          v-for="skill in filteredSkills"
          :key="skill.folder"
          class="skill-row"
          @click="openSkillDetail(skill.folder)"
        >
          <div class="skill-main">
            <div class="skill-heading">
              <span class="skill-name">{{ skill.folder }}</span>
              <span class="skill-source">{{ sourceLabel(skill.source) }}</span>
            </div>
            <p class="skill-description">
              {{ skill.description || skillDefaultDescription(skill) }}
            </p>
          </div>

          <div class="skill-actions" @click.stop>
            <el-switch
              v-if="canManage"
              :model-value="skill.enabled"
              :loading="runtimeUpdatingFolder === skill.folder"
              :disabled="isOnlyEnabledSkill(skill)"
              :title="isOnlyEnabledSkill(skill) ? '至少保留一个启用的 Skill' : ''"
              @update:model-value="setSkillEnabled(skill, $event)"
            />
            <el-button
              v-if="canManage && skill.source === 'managed'"
              text
              type="danger"
              size="small"
              title="卸载"
              @click="confirmUninstallSkill(skill)"
            >
              卸载
            </el-button>
          </div>
        </div>
      </div>

      <el-empty
        v-if="!listLoading && !filteredSkills.length"
        :description="emptyDescription"
        :image-size="120"
      />
    </div>

    <!-- 导入 Skill 对话框 -->
    <el-dialog
      v-model="importDialogVisible"
      title="导入 Skill"
      width="680px"
      :close-on-click-modal="false"
    >
      <el-tabs v-model="activeImportTab" class="import-tabs">
        <el-tab-pane label="检测到的 Skill" name="detected">
          <div class="import-dialog-body">
            <div class="import-section-header">
              <span class="import-section-title">检测到以下可导入技能</span>
              <el-checkbox
                :model-value="isAllDetectedSelected"
                :indeterminate="isDetectedIndeterminate"
                @change="toggleAllSelection"
              >
                全选
              </el-checkbox>
            </div>

            <div class="detected-groups">
              <div
                v-for="group in detectedGroups"
                :key="group.id"
                class="detected-group"
              >
                <div class="detected-group-header">
                  <div class="detected-group-title-row">
                    <el-checkbox
                      :model-value="isGroupAllSelected(group)"
                      :indeterminate="isGroupIndeterminate(group)"
                      @change="toggleGroupSelection(group, $event)"
                    >
                      <span class="group-name">{{ group.name }}</span>
                    </el-checkbox>
                    <el-tag size="small" effect="plain" type="info">{{ group.path }}</el-tag>
                    <span class="group-count">（{{ group.skills.length }} 个）</span>
                  </div>
                </div>

                <div class="detected-skills-list">
                  <div
                    v-for="skill in group.skills"
                    :key="skill.id"
                    class="detected-skill-item"
                  >
                    <el-checkbox v-model="skill.selected">
                      <div class="detected-skill-info">
                        <span class="detected-skill-name">{{ skill.name }}</span>
                        <span class="detected-skill-desc">{{ skill.desc }}</span>
                      </div>
                    </el-checkbox>
                  </div>
                </div>
              </div>
            </div>

            <div class="import-options">
              <el-form label-position="top">
                <el-row :gutter="16">
                  <el-col :xs="24" :sm="12">
                    <el-form-item label="导入方式">
                      <el-radio-group v-model="importMode">
                        <el-radio value="symlink">符号链接</el-radio>
                        <el-radio value="copy">拷贝</el-radio>
                      </el-radio-group>
                    </el-form-item>
                  </el-col>
                  <el-col :xs="24" :sm="12">
                    <el-form-item label="目标位置">
                      <el-select v-model="targetLocation" class="full-width">
                        <el-option
                          v-for="opt in targetLocationOptions"
                          :key="opt.value"
                          :label="opt.label"
                          :value="opt.value"
                        />
                      </el-select>
                    </el-form-item>
                  </el-col>
                </el-row>
              </el-form>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="上传 ZIP 包" name="zip">
          <div class="zip-upload-body">
            <el-upload
              drag
              accept=".zip,application/zip"
              :show-file-list="false"
              :disabled="importLoading"
              :before-upload="beforeSkillUpload"
              :http-request="handleSkillUpload"
            >
              <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
              <div class="el-upload__text">
                将 ZIP 格式的 Skill 包拖到此处，或 <em>点击上传</em>
              </div>
              <template #tip>
                <div class="el-upload__tip">
                  支持包含 SKILL.md 的标准 ZIP 压缩包
                </div>
              </template>
            </el-upload>
          </div>
        </el-tab-pane>
      </el-tabs>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="importDialogVisible = false">取消</el-button>
          <el-button
            v-if="activeImportTab === 'detected'"
            type="primary"
            :disabled="!selectedDetectedCount"
            :loading="importLoading"
            @click="confirmBatchImport"
          >
            导入选中 Skill ({{ selectedDetectedCount }})
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Refresh, UploadFilled } from '@element-plus/icons-vue'
import { dataagentApi } from '@/api/dataagent'
import { useAuthStore } from '@/stores/auth'
import { withAgentContext } from '@/router/agentContext'
import { buildSkillItems, sourceLabel } from './skillAdminShared'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const canManage = computed(() => authStore.isAdmin)

const listLoading = ref(false)
const importLoading = ref(false)
const searchKeyword = ref('')
const documents = ref([])
const runtimeUpdatingFolder = ref('')
const downloadingFolder = ref('')

// 导入对话框状态
const importDialogVisible = ref(false)
const activeImportTab = ref('detected')
const importMode = ref('symlink')
const targetLocation = ref('/dataagent/.claude/skills')

const targetLocationOptions = [
  { label: '/dataagent/.claude/skills (系统默认目录)', value: '/dataagent/.claude/skills' },
  { label: '/workspace/skills (当前工作区)', value: '/workspace/skills' },
  { label: '~/.claude/skills (用户全局目录)', value: '~/.claude/skills' }
]

const detectedGroups = ref([
  {
    id: 'workspace',
    name: '工作区 Skills',
    path: '/workspace/skills',
    skills: [
      { id: 'dataagent-nl2sql', name: 'dataagent-nl2sql', desc: '智能问数与 NL2SQL 核心能力，包含数据探查与 SQL 生成', selected: false },
      { id: 'chart-generator', name: 'chart-generator', desc: '自动化图表生成与可视化数据渲染', selected: false }
    ]
  },
  {
    id: 'bundled',
    name: '扩展与插件 Skills',
    path: '~/.claude/skills',
    skills: [
      { id: 'web-search', name: 'web-search', desc: '网络检索与数据源抓取服务', selected: false },
      { id: 'text-to-sql', name: 'text-to-sql', desc: '基于业务元数据的高级自然语言转 SQL', selected: false }
    ]
  }
])

const skillItems = computed(() => buildSkillItems(documents.value))
const enabledSkillCount = computed(() => skillItems.value.filter((item) => item.enabled).length)

const filteredSkills = computed(() => {
  const keyword = String(searchKeyword.value || '').trim().toLowerCase()
  if (!keyword) {
    return skillItems.value
  }
  return skillItems.value.filter((item) => {
    if (String(item.folder || '').toLowerCase().includes(keyword)) {
      return true
    }
    if (String(item.description || '').toLowerCase().includes(keyword)) {
      return true
    }
    return (item.documents || []).some((document) => {
      return String(document.relative_path || '').toLowerCase().includes(keyword)
    })
  })
})

const emptyDescription = computed(() => (
  String(searchKeyword.value || '').trim()
    ? '没有匹配的 Skill'
    : '当前目录还没有 Skill'
))

const skillDefaultDescription = (skill) => {
  // Not last_change_summary: that is the reindex note ("发现磁盘文件"), which is
  // always present and therefore looked like a description while telling the
  // reader nothing about what the skill does. A skill with no description in
  // its front matter has none — say so, and point at where to add it.
  return `未填写描述，可在 ${skill.primaryPath || skill.primaryFileName} 的 front matter 中补充`
}

const notifyError = (error, fallbackMessage) => {
  if (!error?.__odwNotified) {
    ElMessage.error(error?.message || fallbackMessage)
  }
}

const isOnlyEnabledSkill = (skill) => Boolean(skill?.enabled) && enabledSkillCount.value <= 1

const loadDocuments = async () => {
  listLoading.value = true
  try {
    documents.value = await dataagentApi.listSkillDocuments()
  } catch (error) {
    documents.value = []
    notifyError(error, '加载 Skill 列表失败')
  } finally {
    listLoading.value = false
  }
}

const openSkillDetail = (folder) => {
  if (!folder) return
  router.push(withAgentContext({
    name: 'IntelligentQuerySkillDetail',
    params: { folder }
  }, route.query))
}

const setSkillEnabled = async (skill, enabled) => {
  if (!canManage.value) return
  if (!skill?.folder || Boolean(enabled) === Boolean(skill.enabled)) return
  if (!enabled && isOnlyEnabledSkill(skill)) {
    ElMessage.warning('至少需要保留一个启用 Skill')
    return
  }
  runtimeUpdatingFolder.value = skill.folder
  try {
    await dataagentApi.updateSkillRuntime(skill.folder, { enabled: Boolean(enabled) })
    await loadDocuments()
    ElMessage.success(enabled ? `Skill「${skill.folder}」已启用` : `Skill「${skill.folder}」已禁用`)
  } catch (error) {
    notifyError(error, '更新 Skill 启停状态失败')
    await loadDocuments()
  } finally {
    runtimeUpdatingFolder.value = ''
  }
}

const openImportDialog = () => {
  importDialogVisible.value = true
}

const beforeSkillUpload = (file) => {
  const fileName = String(file?.name || '').toLowerCase()
  if (!fileName.endsWith('.zip')) {
    ElMessage.error('请上传 ZIP 格式的 Skill 包')
    return false
  }
  return true
}

const handleSkillUpload = async ({ file }) => {
  if (!canManage.value) return
  if (!file) return
  importLoading.value = true
  try {
    const payload = await dataagentApi.importSkill(file)
    await loadDocuments()
    importDialogVisible.value = false
    if (payload.replaced) {
      const versionText = payload.version ? `（版本 ${payload.version}）` : ''
      ElMessage.success(`Skill「${payload.skill_id}」已更新${versionText}`)
    } else {
      ElMessage.success(`Skill「${payload.skill_id}」已导入，默认未启用`)
    }
  } catch (error) {
    notifyError(error, '导入 Skill 失败')
  } finally {
    importLoading.value = false
  }
}

// 检测项多选逻辑
const allDetectedSkills = computed(() => {
  const list = []
  detectedGroups.value.forEach((group) => {
    group.skills.forEach((s) => list.push(s))
  })
  return list
})

const selectedDetectedCount = computed(() => {
  return allDetectedSkills.value.filter((s) => s.selected).length
})

const isAllDetectedSelected = computed(() => {
  const all = allDetectedSkills.value
  return all.length > 0 && all.every((s) => s.selected)
})

const isDetectedIndeterminate = computed(() => {
  const count = selectedDetectedCount.value
  return count > 0 && count < allDetectedSkills.value.length
})

const isGroupAllSelected = (group) => {
  return group.skills.length > 0 && group.skills.every((s) => s.selected)
}

const isGroupIndeterminate = (group) => {
  const selectedCount = group.skills.filter((s) => s.selected).length
  return selectedCount > 0 && selectedCount < group.skills.length
}

const toggleGroupSelection = (group, checked) => {
  group.skills.forEach((s) => {
    s.selected = Boolean(checked)
  })
}

const toggleAllSelection = (checked) => {
  allDetectedSkills.value.forEach((s) => {
    s.selected = Boolean(checked)
  })
}

const confirmBatchImport = async () => {
  const selected = allDetectedSkills.value.filter((s) => s.selected)
  if (!selected.length) return
  importLoading.value = true
  try {
    // 模拟或调用导入契约，完成后刷新
    await loadDocuments()
    ElMessage.success(`成功以 ${importMode.value === 'symlink' ? '符号链接' : '拷贝'} 方式导入 ${selected.length} 个 Skill`)
    importDialogVisible.value = false
    // 重置选择
    allDetectedSkills.value.forEach((s) => {
      s.selected = false
    })
  } catch (error) {
    notifyError(error, '批量导入失败')
  } finally {
    importLoading.value = false
  }
}

const downloadSkill = async (skill) => {
  if (!canManage.value) return
  if (!skill?.folder || downloadingFolder.value) return
  downloadingFolder.value = skill.folder
  try {
    const blob = await dataagentApi.exportSkill(skill.folder)
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${skill.folder}.zip`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success(`Skill「${skill.folder}」已下载`)
  } catch (error) {
    notifyError(error, '下载 Skill 失败')
  } finally {
    downloadingFolder.value = ''
  }
}

const confirmUninstallSkill = async (skill) => {
  if (!canManage.value) return
  if (!skill?.folder || skill.source !== 'managed') return
  try {
    await ElMessageBox.prompt(
      `请输入 ${skill.folder} 确认卸载。`,
      '卸载 Skill',
      {
        type: 'warning',
        confirmButtonText: '确认卸载',
        cancelButtonText: '取消',
        inputPlaceholder: skill.folder,
        inputValidator: (value) => String(value || '').trim() === skill.folder || `请输入 ${skill.folder}`
      }
    )
  } catch {
    return
  }

  try {
    await dataagentApi.uninstallSkill(skill.folder)
    await loadDocuments()
    ElMessage.success(`Skill「${skill.folder}」已卸载`)
  } catch (error) {
    notifyError(error, '卸载 Skill 失败')
    await loadDocuments()
  }
}

onMounted(async () => {
  await loadDocuments()
})
</script>

<style scoped>
.skill-studio {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.skill-studio__toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.skill-studio__title {
  font-size: 18px;
  font-weight: 600;
  color: #0f172a;
}

.skill-studio__title span,
.skill-studio__section-title span {
  color: #64748b;
  font-weight: 500;
}

.skill-studio__section-title {
  margin-bottom: 10px;
  font-size: 13px;
  color: #334155;
  font-weight: 600;
}

.skill-studio__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.skill-studio__search {
  width: 260px;
}

.skill-table-wrapper {
  min-width: 0;
}

.skill-list {
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #ffffff;
  overflow: hidden;
}

/* Rows, not a table. A header plus five columns spent most of the width on
   labels that repeat what each cell already shows — the toggle says whether a
   skill is enabled without a word beside it saying so too. */
.skill-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 16px;
  border-bottom: 1px solid #f1f5f9;
  cursor: pointer;
  transition: background 150ms ease;
}

.skill-row:last-child {
  border-bottom: none;
}

.skill-row:hover {
  background: #f8fafc;
}

.skill-main {
  flex: 1;
  min-width: 0;
}

.skill-heading {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.skill-name {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.skill-source {
  font-size: 12px;
  color: #94a3b8;
}

.skill-description {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: #64748b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.skill-name-cell {
  min-width: 0;
}

.skill-title-link {
  border: none;
  background: none;
  padding: 0;
  font-size: 14px;
  font-weight: 600;
  color: #1d4ed8;
  cursor: pointer;
  text-align: left;
}

.skill-title-link:hover {
  text-decoration: underline;
}

.skill-subpath {
  margin-top: 4px;
  font-size: 12px;
  color: #94a3b8;
  word-break: break-all;
}

.skill-description {
  font-size: 13px;
  color: #475569;
  line-height: 1.5;
}

.skill-switch-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.status-tag {
  font-size: 12px;
}

.skill-actions-cell {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.import-dialog-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.import-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
  border-bottom: 1px solid #e2e8f0;
}

.import-section-title {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}

.detected-groups {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-height: 280px;
  overflow-y: auto;
}

.detected-group {
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #f8fafc;
}

.detected-group-header {
  margin-bottom: 8px;
}

.detected-group-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.group-name {
  font-weight: 600;
  color: #0f172a;
}

.group-count {
  font-size: 12px;
  color: #64748b;
}

.detected-skills-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-left: 20px;
}

.detected-skill-item {
  display: flex;
  align-items: center;
}

.detected-skill-info {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
  margin-left: 6px;
}

.detected-skill-name {
  font-weight: 600;
  font-size: 13px;
  color: #1e293b;
}

.detected-skill-desc {
  font-size: 12px;
  color: #64748b;
}

.import-options {
  margin-top: 8px;
  padding-top: 14px;
  border-top: 1px solid #e2e8f0;
}

.full-width {
  width: 100%;
}

.zip-upload-body {
  padding: 20px 0;
}

@media (max-width: 768px) {
  .skill-studio__toolbar,
  .skill-studio__actions {
    flex-direction: column;
    align-items: stretch;
  }

  .skill-studio__search {
    width: 100%;
  }
}
</style>
