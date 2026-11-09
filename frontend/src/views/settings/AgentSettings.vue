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
          <el-input
            v-model="form[field.key]"
            class="agent-input"
            clearable
            :disabled="loading || saving"
            :placeholder="`请输入${field.label} ID（例如：agent_default）`"
          />
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
import { isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'
import SettingsSection from './components/SettingsSection.vue'
import SettingsRow from './components/SettingsRow.vue'

const FIELDS = [
  { key: 'queryAgentId', label: '智能问数助手', description: '门户「智能问数」菜单页与悬浮窗使用' },
  { key: 'metadataAgentId', label: '智能元数据助手', description: '用于生成表与字段描述' }
]

const loading = ref(false)
const saving = ref(false)
const form = reactive({ queryAgentId: '', metadataAgentId: '' })
const saved = reactive({ queryAgentId: '', metadataAgentId: '' })

const dirty = computed(() => FIELDS.some(({ key }) => form[key] !== saved[key]))

const statusTag = (key) => {
  return saved[key] ? { type: 'success', text: '已配置' } : { type: 'info', text: '未配置' }
}

const alert = computed(() => {
  const unset = FIELDS.filter(({ key }) => !saved[key])
  if (unset.length) {
    return {
      type: 'info',
      text: `尚未配置${unset.map(({ label }) => label).join('、')}，请填入对应的助手 ID（例如：agent_default）并保存`
    }
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
  try {
    const settings = await settingsApi.getAgentSettings().catch(() => ({}))
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
    const payload = Object.fromEntries(FIELDS.map(({ key }) => [key, String(form[key] || '').trim()]))
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

.agent-input {
  width: 320px;
  max-width: 100%;
}
</style>
