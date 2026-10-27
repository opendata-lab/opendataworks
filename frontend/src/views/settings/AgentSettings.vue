<template>
  <div v-loading="loading" class="agent-settings">
    <SettingsSection title="智能助手">
      <template #actions>
        <el-button :disabled="loading || saving" :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :loading="saving" :disabled="loading || !dirty" @click="save">保存</el-button>
      </template>

      <el-alert v-if="alert" :type="alert.type" :closable="false" show-icon class="agent-alert">
        <template #title>{{ alert.text }}</template>
      </el-alert>

      <SettingsRow v-for="field in FIELDS" :key="field.key" :label="field.label" :description="field.description">
        <div class="agent-field">
          <el-select
            v-model="form[field.key]"
            class="agent-select"
            clearable
            filterable
            :disabled="loading || !agents.length"
            :placeholder="agents.length ? '请选择助手' : '暂无可用助手'"
          >
            <el-option
              v-for="agent in agents"
              :key="agent.agent_id"
              :label="agent.display_name || agent.agent_id"
              :value="agent.agent_id"
            >
              <div class="agent-option">
                <span>{{ agent.display_name || agent.agent_id }}</span>
                <span class="agent-option-id">{{ agent.agent_id }}</span>
              </div>
            </el-option>
          </el-select>
          <el-tag :type="statusTag(field.key).type" size="small" effect="plain">{{ statusTag(field.key).text }}</el-tag>
        </div>
      </SettingsRow>
    </SettingsSection>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { settingsApi } from '@/api/settings'
import { nl2sqlApi, nl2sqlErrorMessage } from '@/api/nl2sql'
import { isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'
import SettingsSection from './components/SettingsSection.vue'
import SettingsRow from './components/SettingsRow.vue'

const FIELDS = [
  { key: 'queryAgentId', label: '智能问数助手', description: '门户「智能问数」菜单页与悬浮窗使用' },
  { key: 'metadataAgentId', label: '智能元数据助手', description: '用于生成表与字段描述' }
]

const loading = ref(false)
const saving = ref(false)
const agents = ref([])
const agentsError = ref('')
const form = reactive({ queryAgentId: '', metadataAgentId: '' })
const saved = reactive({ queryAgentId: '', metadataAgentId: '' })

const dirty = computed(() => FIELDS.some(({ key }) => form[key] !== saved[key]))

// 已保存的助手可能因为被删除或可见范围收紧而不在清单里，需要显式提示而不是静默失效
const isMissing = (key) =>
  !!saved[key] && agents.value.length > 0 && !agents.value.some((agent) => agent.agent_id === saved[key])

const statusTag = (key) => {
  if (!saved[key]) return { type: 'info', text: '未配置' }
  if (isMissing(key)) return { type: 'danger', text: '助手不可用' }
  return { type: 'success', text: '已生效' }
}

const alert = computed(() => {
  if (agentsError.value) {
    return { type: 'error', text: `获取助手清单失败：${agentsError.value}` }
  }
  if (!agents.value.length) {
    return { type: 'warning', text: '没有可用的助手，请先在智能问数中创建助手，或检查助手的可见范围设置' }
  }
  const missing = FIELDS.filter(({ key }) => isMissing(key))
  if (missing.length) {
    return { type: 'error', text: `${missing.map(({ label }) => label).join('、')}已保存的助手不在当前可用清单中，请重新选择并保存` }
  }
  const unset = FIELDS.filter(({ key }) => !saved[key])
  if (unset.length) {
    return { type: 'info', text: `尚未配置${unset.map(({ label }) => label).join('、')}，对应功能会提示先来此处选择` }
  }
  return null
})

const applySaved = (settings) => {
  for (const { key } of FIELDS) {
    saved[key] = String(settings?.[key] || '')
    form[key] = saved[key]
  }
}

const load = async () => {
  loading.value = true
  agentsError.value = ''
  try {
    const [agentList, settings] = await Promise.all([
      nl2sqlApi.listAgents().catch((error) => {
        agentsError.value = nl2sqlErrorMessage(error, '请稍后重试')
        return []
      }),
      settingsApi.getAgentSettings().catch(() => ({}))
    ])
    agents.value = Array.isArray(agentList) ? agentList.filter((item) => item?.agent_id) : []
    applySaved(settings)
  } finally {
    loading.value = false
  }
}

const save = async () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('保存智能助手设置')
    return
  }
  saving.value = true
  try {
    const payload = Object.fromEntries(FIELDS.map(({ key }) => [key, form[key] || '']))
    applySaved(await settingsApi.updateAgentSettings(payload))
    ElMessage.success('已保存')
  } catch (error) {
    ElMessage.error(error?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.agent-settings {
  min-width: 0;
}

.agent-alert {
  margin: 16px 0 0;
}

.agent-field {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.agent-select {
  width: 320px;
  max-width: 100%;
}

.agent-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.agent-option-id {
  font-size: 12px;
  color: var(--odw-text-secondary);
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
}

</style>
