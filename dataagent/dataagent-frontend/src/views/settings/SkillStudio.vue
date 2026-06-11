<template>
  <div class="skill-studio">
    <div class="skill-studio__toolbar">
      <div>
        <div class="skill-studio__title">Skill 列表</div>
        <div class="skill-studio__subtitle">{{ enabledSummary }}</div>
      </div>
      <div class="skill-studio__actions">
        <el-input
          v-model="searchKeyword"
          clearable
          placeholder="搜索 Skill 名称或文件路径"
          class="skill-studio__search"
        />
        <el-upload
          accept=".zip,application/zip"
          :show-file-list="false"
          :disabled="importLoading"
          :before-upload="beforeSkillUpload"
          :http-request="handleSkillUpload"
        >
          <el-button type="primary" :loading="importLoading">导入 Skill</el-button>
        </el-upload>
      </div>
    </div>

    <div v-loading="listLoading" class="skill-grid">
      <div
        v-for="skill in filteredSkills"
        :key="skill.folder"
        class="skill-card"
      >
        <div class="skill-card__header">
          <div class="skill-card__heading">
            <div class="skill-card__title">{{ skill.folder }}</div>
            <div class="skill-card__path">{{ skill.folder }}/{{ skill.primaryPath || skill.primaryFileName }}</div>
          </div>
          <div class="skill-card__switch">
            <span class="skill-card__switch-label">启用</span>
            <el-switch
              :model-value="skill.enabled"
              :loading="runtimeUpdatingFolder === skill.folder"
              :disabled="isOnlyEnabledSkill(skill)"
              :title="isOnlyEnabledSkill(skill) ? '至少保留一个启用 Skill' : ''"
              @update:model-value="setSkillEnabled(skill, $event)"
            />
          </div>
        </div>

        <div class="skill-card__tags">
          <el-tag size="small" effect="plain">{{ sourceLabel(skill.source) }}</el-tag>
          <el-tag size="small" :type="skill.enabled ? 'success' : 'info'">
            {{ skill.enabled ? '已启用' : '未启用' }}
          </el-tag>
          <el-tag size="small" effect="plain">{{ skill.documentCount }} 个文件</el-tag>
        </div>

        <div class="skill-card__footer">
          <el-button text type="primary" @click="openSkillDetail(skill.folder)">查看详情</el-button>
          <el-button
            v-if="skill.source === 'managed'"
            text
            type="danger"
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
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dataagentApi } from '@/api/dataagent'
import { buildSkillItems, sourceLabel } from './skillAdminShared'

const router = useRouter()

const listLoading = ref(false)
const importLoading = ref(false)
const searchKeyword = ref('')
const documents = ref([])
const runtimeUpdatingFolder = ref('')

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
    return (item.documents || []).some((document) => {
      return String(document.relative_path || '').toLowerCase().includes(keyword)
    })
  })
})

const enabledSummary = computed(() => {
  return `已启用 ${enabledSkillCount.value} / 共 ${skillItems.value.length}`
})

const emptyDescription = computed(() => (
  String(searchKeyword.value || '').trim()
    ? '没有匹配的 Skill'
    : '当前目录还没有可管理的 Skill'
))

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
  router.push({
    name: 'IntelligentQuerySkillDetail',
    params: { folder }
  })
}

const setSkillEnabled = async (skill, enabled) => {
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

const beforeSkillUpload = (file) => {
  const fileName = String(file?.name || '').toLowerCase()
  if (!fileName.endsWith('.zip')) {
    ElMessage.error('请上传 ZIP 格式的 Skill 包')
    return false
  }
  return true
}

const handleSkillUpload = async ({ file }) => {
  if (!file) return
  importLoading.value = true
  try {
    const payload = await dataagentApi.importSkill(file)
    await loadDocuments()
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

const confirmUninstallSkill = async (skill) => {
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

.skill-studio__subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: #64748b;
  line-height: 1.6;
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
  width: 280px;
}

.skill-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 260px), 300px));
  justify-content: start;
  gap: 14px;
  min-width: 0;
}

.skill-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border: 1px solid #dbe2ea;
  border-radius: 8px;
  background: #fff;
  min-width: 0;
}

.skill-card__header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.skill-card__heading {
  min-width: 0;
}

.skill-card__title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.skill-card__path {
  margin-top: 6px;
  font-size: 12px;
  color: #64748b;
  word-break: break-all;
}

.skill-card__switch {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

.skill-card__switch-label {
  font-size: 12px;
  color: #64748b;
}

.skill-card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.skill-card__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: auto;
}

@media (max-width: 768px) {
  .skill-studio__toolbar,
  .skill-studio__actions,
  .skill-card__header {
    flex-direction: column;
    align-items: stretch;
  }

  .skill-studio__search {
    width: 100%;
  }

  .skill-grid {
    grid-template-columns: 1fr;
  }

  .skill-studio__actions :deep(.el-upload),
  .skill-studio__actions :deep(.el-upload .el-button) {
    width: 100%;
  }

  .skill-card__switch {
    align-items: flex-start;
  }
}
</style>
