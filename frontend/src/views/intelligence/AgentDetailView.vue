<template>
  <section class="agent-detail">
    <header class="agent-detail-head">
      <div>
        <el-button text @click="goBack">返回智能体</el-button>
        <h2>{{ form.name || '智能体详情' }}</h2>
        <p>{{ form.resolved_workdir || '托管工作目录将在保存后生成' }}</p>
      </div>
      <div class="agent-detail-actions">
        <el-button :icon="ChatLineRound" @click="openChat">开启对话</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </div>
    </header>

    <el-skeleton v-if="loading" :rows="10" animated />

    <el-form v-else label-position="top" class="agent-form">
      <section class="agent-section">
        <h3>基础信息</h3>
        <el-form-item label="名称">
          <el-input v-model="form.name" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="系统提示词">
          <el-input v-model="form.system_prompt" type="textarea" :rows="8" />
        </el-form-item>
      </section>

      <section class="agent-section">
        <h3>能力范围</h3>
        <el-form-item label="权限模式">
          <el-select v-model="form.permission_mode">
            <el-option
              v-for="mode in capabilities.permission_modes"
              :key="mode"
              :label="mode"
              :value="mode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="允许工具">
          <el-checkbox-group v-model="form.allowed_tools">
            <el-checkbox-button
              v-for="tool in capabilities.tools"
              :key="tool"
              :label="tool"
            />
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="允许 MCP 服务">
          <el-checkbox-group v-model="form.mcp_server_ids">
            <el-checkbox
              v-for="server in capabilities.mcp_servers"
              :key="server.id"
              :label="server.id"
            >
              {{ server.name }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="Skills">
          <el-checkbox-group v-model="form.skill_folders" class="agent-skill-list">
            <el-checkbox
              v-for="skill in capabilities.skills"
              :key="skill.folder"
              :label="skill.folder"
            >
              {{ skill.folder }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </section>

      <section class="agent-section">
        <h3>高级设置</h3>
        <el-form-item label="会话轮次数上限">
          <el-input-number v-model="form.max_turns" :min="0" :max="200" />
        </el-form-item>
        <el-form-item label="环境变量 JSON">
          <el-input v-model="envVarsText" type="textarea" :rows="6" />
        </el-form-item>
      </section>
    </el-form>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatLineRound } from '@element-plus/icons-vue'
import { dataagentApi } from '@/api/dataagent'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const envVarsText = ref('{}')

const capabilities = reactive({
  tools: [],
  mcp_servers: [],
  skills: [],
  permission_modes: ['inherit', 'default', 'bypassPermissions']
})

const form = reactive({
  agent_id: '',
  name: '',
  description: '',
  resolved_workdir: '',
  system_prompt: '',
  permission_mode: 'inherit',
  allowed_tools: [],
  mcp_server_ids: [],
  skill_folders: [],
  max_turns: 0,
  env_vars: {},
  is_default: false
})

const agentId = computed(() => String(route.params.agentId || ''))

const applyAgent = (agent) => {
  Object.assign(form, {
    agent_id: String(agent?.agent_id || ''),
    name: String(agent?.name || ''),
    description: String(agent?.description || ''),
    resolved_workdir: String(agent?.resolved_workdir || ''),
    system_prompt: String(agent?.system_prompt || ''),
    permission_mode: String(agent?.permission_mode || 'inherit'),
    allowed_tools: Array.isArray(agent?.allowed_tools) ? [...agent.allowed_tools] : [],
    mcp_server_ids: Array.isArray(agent?.mcp_server_ids) ? [...agent.mcp_server_ids] : [],
    skill_folders: Array.isArray(agent?.skill_folders) ? [...agent.skill_folders] : [],
    max_turns: Number(agent?.max_turns || 0),
    env_vars: agent?.env_vars && typeof agent.env_vars === 'object' ? { ...agent.env_vars } : {},
    is_default: Boolean(agent?.is_default)
  })
  envVarsText.value = JSON.stringify(form.env_vars || {}, null, 2)
}

const loadDetail = async () => {
  loading.value = true
  try {
    const [agent, caps] = await Promise.all([
      dataagentApi.getAgent(agentId.value),
      dataagentApi.getAgentCapabilities()
    ])
    Object.assign(capabilities, {
      tools: caps?.tools || [],
      mcp_servers: caps?.mcp_servers || [],
      skills: caps?.skills || [],
      permission_modes: caps?.permission_modes || capabilities.permission_modes
    })
    applyAgent(agent)
  } finally {
    loading.value = false
  }
}

const buildPayload = () => {
  let envVars = {}
  try {
    envVars = JSON.parse(envVarsText.value || '{}')
  } catch (_error) {
    throw new Error('环境变量必须是合法 JSON')
  }
  if (!envVars || Array.isArray(envVars) || typeof envVars !== 'object') {
    throw new Error('环境变量必须是 JSON 对象')
  }
  return {
    name: form.name,
    description: form.description,
    system_prompt: form.system_prompt,
    permission_mode: form.permission_mode,
    allowed_tools: [...form.allowed_tools],
    mcp_server_ids: [...form.mcp_server_ids],
    skill_folders: [...form.skill_folders],
    max_turns: Number(form.max_turns || 0),
    env_vars: envVars
  }
}

const handleSave = async () => {
  saving.value = true
  try {
    const saved = await dataagentApi.updateAgent(form.agent_id, buildPayload())
    applyAgent(saved)
    ElMessage.success('已保存')
  } catch (error) {
    ElMessage.error(String(error?.message || '保存失败'))
  } finally {
    saving.value = false
  }
}

const goBack = () => {
  router.push({ path: '/intelligent-query', query: { tab: 'agents' } })
}

const openChat = () => {
  router.push({ path: '/intelligent-query', query: { agent_id: form.agent_id } })
}

onMounted(loadDetail)
</script>

<style scoped>
.agent-detail {
  min-height: 100%;
}

.agent-detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.agent-detail-head h2 {
  margin: 8px 0 0;
  color: #1f2937;
  font-size: 22px;
}

.agent-detail-head p {
  margin: 6px 0 0;
  color: #98a2b3;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.agent-detail-actions {
  display: flex;
  gap: 8px;
}

.agent-form {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(320px, 0.9fr);
  gap: 16px;
}

.agent-section {
  padding: 18px;
  border: 1px solid #dfe7f1;
  border-radius: 8px;
  background: #ffffff;
}

.agent-section:first-child {
  grid-row: span 2;
}

.agent-section h3 {
  margin: 0 0 14px;
  color: #1f2937;
  font-size: 16px;
}

.agent-skill-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 6px 12px;
}

@media (max-width: 960px) {
  .agent-form {
    grid-template-columns: 1fr;
  }
}
</style>
