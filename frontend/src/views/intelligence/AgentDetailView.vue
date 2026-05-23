<template>
  <section class="agent-detail">
    <header class="agent-detail-head">
      <div>
        <el-button text @click="goBack">
          <el-icon style="margin-right: 4px"><ArrowLeft /></el-icon>
          返回智能体
        </el-button>
        <h2>{{ form.name || '智能体详情' }}</h2>
        <p class="agent-detail-workdir">{{ form.resolved_workdir || '托管工作目录将在保存后生成' }}</p>
      </div>
      <div class="agent-detail-actions">
        <el-button :icon="ChatLineRound" @click="openChat">开启对话</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </div>
    </header>

    <el-skeleton v-if="loading" :rows="10" animated />

    <div v-else class="agent-detail-body">
      <!-- Left tab navigation -->
      <nav class="agent-tab-nav">
        <ul>
          <li
            v-for="tab in tabs"
            :key="tab.key"
            class="agent-tab-item"
            :class="{ active: activeTab === tab.key }"
            @click="activeTab = tab.key"
          >
            {{ tab.label }}
          </li>
        </ul>
      </nav>

      <!-- Right content panel -->
      <div class="agent-tab-panel">
        <el-form label-position="top">
          <!-- 基础信息 -->
          <transition name="tab-fade" mode="out-in">
            <section v-if="activeTab === 'basic'" key="basic" class="agent-panel-section">
              <h3>基础信息</h3>
              <el-form-item label="名称">
                <el-input v-model="form.name" maxlength="128" show-word-limit placeholder="请输入智能体名称" />
              </el-form-item>
              <el-form-item label="描述">
                <el-input v-model="form.description" type="textarea" :rows="4" placeholder="请输入描述信息" />
              </el-form-item>
            </section>

            <!-- 提示词设置 -->
            <section v-else-if="activeTab === 'prompt'" key="prompt" class="agent-panel-section">
              <h3>提示词设置</h3>
              <el-form-item label="系统提示词">
                <el-input
                  v-model="form.system_prompt"
                  type="textarea"
                  :rows="14"
                  placeholder="请输入系统提示词"
                />
              </el-form-item>
            </section>

            <!-- 权限模式 -->
            <section v-else-if="activeTab === 'permission'" key="permission" class="agent-panel-section">
              <h3>权限模式</h3>
              <div class="permission-card-list">
                <div
                  v-for="mode in capabilities.permission_modes"
                  :key="mode"
                  class="permission-card"
                  :class="{ selected: form.permission_mode === mode }"
                  @click="form.permission_mode = mode"
                >
                  <div class="permission-card-header">
                    <span class="permission-card-name">{{ permissionModeLabel(mode) }}</span>
                    <el-icon v-if="form.permission_mode === mode" class="permission-card-check"><CircleCheck /></el-icon>
                  </div>
                  <p class="permission-card-desc">{{ permissionModeDesc(mode) }}</p>
                </div>
              </div>
            </section>

            <!-- 工具 -->
            <section v-else-if="activeTab === 'tools'" key="tools" class="agent-panel-section">
              <h3>预授权工具</h3>
              <div class="tool-card-list">
                <div v-for="tool in capabilities.tools" :key="tool" class="tool-card">
                  <div class="tool-card-header">
                    <span class="tool-card-name">{{ tool }}</span>
                    <el-switch
                      :model-value="form.allowed_tools.includes(tool)"
                      @change="(val) => toggleTool(tool, val)"
                    />
                  </div>
                </div>
              </div>
              <el-divider v-if="capabilities.mcp_servers.length" />
              <template v-if="capabilities.mcp_servers.length">
                <h3>MCP 服务</h3>
                <el-checkbox-group v-model="form.mcp_server_ids">
                  <div v-for="server in capabilities.mcp_servers" :key="server.id" class="tool-card">
                    <el-checkbox :label="server.id">{{ server.name }}</el-checkbox>
                  </div>
                </el-checkbox-group>
              </template>
            </section>

            <!-- Skills -->
            <section v-else-if="activeTab === 'skills'" key="skills" class="agent-panel-section">
              <h3>Skills</h3>
              <div class="skill-card-list">
                <div v-for="skill in capabilities.skills" :key="skill.folder" class="skill-card">
                  <div class="skill-card-header">
                    <span class="skill-card-name">{{ skill.folder }}</span>
                    <el-switch
                      :model-value="form.skill_folders.includes(skill.folder)"
                      @change="(val) => toggleSkill(skill.folder, val)"
                    />
                  </div>
                </div>
              </div>
              <el-empty v-if="!capabilities.skills.length" description="暂无可用 Skill" :image-size="80" />
            </section>

            <!-- 数据范围 -->
            <section v-else-if="activeTab === 'scope'" key="scope" class="agent-panel-section">
              <h3>数据范围</h3>
              <el-form-item label="允许访问的 Schema">
                <el-select
                  v-model="scopeSelection"
                  multiple
                  filterable
                  collapse-tags
                  collapse-tags-tooltip
                  placeholder="选择数据源 Schema"
                  style="width: 100%"
                >
                  <el-option
                    v-for="option in dataScopeOptions"
                    :key="scopeKey(option)"
                    :label="scopeLabel(option)"
                    :value="scopeKey(option)"
                  />
                </el-select>
              </el-form-item>
              <div v-if="!scopeSelection.length" class="scope-empty">无可访问数据范围</div>
              <div v-else class="scope-list">
                <span v-for="key in scopeSelection" :key="key">{{ scopeLabel(scopeOptionByKey[key]) }}</span>
              </div>
            </section>

            <!-- 高级设置 -->
            <section v-else-if="activeTab === 'advanced'" key="advanced" class="agent-panel-section">
              <h3>高级设置</h3>
              <el-form-item label="会话轮次数上限">
                <el-input-number v-model="form.max_turns" :min="0" :max="200" />
              </el-form-item>
              <el-form-item label="环境变量 JSON">
                <el-input v-model="envVarsText" type="textarea" :rows="8" placeholder="{}" />
              </el-form-item>
            </section>
          </transition>
        </el-form>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ChatLineRound, CircleCheck } from '@element-plus/icons-vue'
import { dataagentApi } from '@/api/dataagent'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const envVarsText = ref('{}')
const dataScopeOptions = ref([])
const scopeSelection = ref([])
const activeTab = ref('basic')

const tabs = [
  { key: 'basic', label: '基础信息' },
  { key: 'prompt', label: '提示词设置' },
  { key: 'permission', label: '权限模式' },
  { key: 'tools', label: '工具' },
  { key: 'skills', label: 'Skills' },
  { key: 'scope', label: '数据范围' },
  { key: 'advanced', label: '高级设置' }
]

const permissionModeLabels = {
  inherit: '继承模式',
  default: '默认模式',
  bypassPermissions: '全自动模式'
}
const permissionModeDescs = {
  inherit: '继承父级或平台默认权限配置。',
  default: '可自由读取文件，编辑或执行命令前会询问。',
  bypassPermissions: '可执行任何操作，无需询问。请谨慎使用。'
}
const permissionModeLabel = (mode) => permissionModeLabels[mode] || mode
const permissionModeDesc = (mode) => permissionModeDescs[mode] || ''

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
  data_scope: { allowed_scopes: [] },
  is_default: false
})

const agentId = computed(() => String(route.params.agentId || ''))
const scopeOptionByKey = computed(() => {
  const map = {}
  for (const option of dataScopeOptions.value) {
    map[scopeKey(option)] = option
  }
  for (const item of form.data_scope?.allowed_scopes || []) {
    map[scopeKey(item)] = map[scopeKey(item)] || item
  }
  return map
})

const scopeKey = (scope) => `${scope?.cluster_id ?? 'platform'}::${scope?.database || ''}`
const scopeLabel = (scope) => {
  if (!scope) return ''
  const cluster = scope.cluster_name || (scope.cluster_id == null ? 'platform-mysql' : `cluster_id=${scope.cluster_id}`)
  const source = scope.source_type || '-'
  return `${cluster} / ${source} / ${scope.database || ''}`
}

const toggleTool = (tool, val) => {
  if (val) {
    if (!form.allowed_tools.includes(tool)) form.allowed_tools.push(tool)
  } else {
    form.allowed_tools = form.allowed_tools.filter((t) => t !== tool)
  }
}

const toggleSkill = (folder, val) => {
  if (val) {
    if (!form.skill_folders.includes(folder)) form.skill_folders.push(folder)
  } else {
    form.skill_folders = form.skill_folders.filter((f) => f !== folder)
  }
}

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
    data_scope: agent?.data_scope && typeof agent.data_scope === 'object'
      ? { allowed_scopes: Array.isArray(agent.data_scope.allowed_scopes) ? [...agent.data_scope.allowed_scopes] : [] }
      : { allowed_scopes: [] },
    is_default: Boolean(agent?.is_default)
  })
  envVarsText.value = JSON.stringify(form.env_vars || {}, null, 2)
  scopeSelection.value = (form.data_scope.allowed_scopes || []).map(scopeKey)
}

const loadDetail = async () => {
  loading.value = true
  try {
    const [agent, caps, scopeOptions] = await Promise.all([
      dataagentApi.getAgent(agentId.value),
      dataagentApi.getAgentCapabilities(),
      dataagentApi.listDataScopeOptions()
    ])
    Object.assign(capabilities, {
      tools: caps?.tools || [],
      mcp_servers: caps?.mcp_servers || [],
      skills: caps?.skills || [],
      permission_modes: caps?.permission_modes || capabilities.permission_modes
    })
    dataScopeOptions.value = Array.isArray(scopeOptions) ? scopeOptions : []
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
    env_vars: envVars,
    data_scope: {
      allowed_scopes: scopeSelection.value
        .map((key) => scopeOptionByKey.value[key])
        .filter(Boolean)
        .map((scope) => ({
          cluster_id: scope.cluster_id ?? null,
          source_type: scope.source_type || '',
          database: scope.database || ''
        }))
    }
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
  display: flex;
  flex-direction: column;
}

/* ── Header ── */
.agent-detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
  flex-shrink: 0;
}

.agent-detail-head h2 {
  margin: 8px 0 0;
  color: #1f2937;
  font-size: 22px;
  font-weight: 700;
}

.agent-detail-workdir {
  margin: 6px 0 0;
  color: #98a2b3;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.agent-detail-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

/* ── Body (left nav + right panel) ── */
.agent-detail-body {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  flex: 1;
  min-height: 0;
  border: 1px solid #e5e9f0;
  border-radius: 10px;
  background: #ffffff;
  overflow: hidden;
}

/* ── Left Tab Nav ── */
.agent-tab-nav {
  border-right: 1px solid #e5e9f0;
  background: #fafbfc;
  padding: 8px 0;
  overflow-y: auto;
}

.agent-tab-nav ul {
  list-style: none;
  margin: 0;
  padding: 0;
}

.agent-tab-item {
  position: relative;
  padding: 14px 20px;
  font-size: 14px;
  color: #475467;
  cursor: pointer;
  transition: all 0.2s ease;
  border-left: 3px solid transparent;
  user-select: none;
}

.agent-tab-item:hover {
  background: #f0f2f5;
  color: #1f2937;
}

.agent-tab-item.active {
  background: #eef2f8;
  color: #1f2937;
  font-weight: 600;
  border-left-color: #409eff;
}

/* ── Right Panel ── */
.agent-tab-panel {
  padding: 28px 32px;
  overflow-y: auto;
  min-height: 0;
}

.agent-panel-section h3 {
  margin: 0 0 20px;
  color: #1f2937;
  font-size: 17px;
  font-weight: 600;
}

/* ── Tab fade transition ── */
.tab-fade-enter-active,
.tab-fade-leave-active {
  transition: opacity 0.18s ease;
}

.tab-fade-enter-from,
.tab-fade-leave-to {
  opacity: 0;
}

/* ── Permission Cards ── */
.permission-card-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.permission-card {
  padding: 16px 20px;
  border: 1px solid #e5e9f0;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.permission-card:hover {
  border-color: #c0c8d4;
  background: #fafbfc;
}

.permission-card.selected {
  border-color: #67c23a;
  background: #f0f9eb;
}

.permission-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.permission-card-name {
  font-size: 15px;
  font-weight: 600;
  color: #1f2937;
}

.permission-card-check {
  color: #67c23a;
  font-size: 20px;
}

.permission-card-desc {
  margin: 6px 0 0;
  color: #667085;
  font-size: 13px;
  line-height: 1.5;
}

/* ── Tool Cards ── */
.tool-card-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.tool-card {
  padding: 14px 18px;
  border: 1px solid #e5e9f0;
  border-radius: 8px;
  transition: border-color 0.2s ease;
}

.tool-card:hover {
  border-color: #c0c8d4;
}

.tool-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tool-card-name {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
}

/* ── Skill Cards ── */
.skill-card-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.skill-card {
  padding: 14px 18px;
  border: 1px solid #e5e9f0;
  border-radius: 8px;
  transition: border-color 0.2s ease;
}

.skill-card:hover {
  border-color: #c0c8d4;
}

.skill-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.skill-card-name {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
}

/* ── Data Scope ── */
.scope-empty {
  margin-top: 8px;
  color: #b42318;
  font-size: 13px;
}

.scope-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.scope-list span {
  padding: 4px 10px;
  border-radius: 6px;
  background: #eef4ff;
  color: #344054;
  font-size: 12px;
}

/* ── Responsive ── */
@media (max-width: 768px) {
  .agent-detail-body {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(0, 1fr);
  }

  .agent-tab-nav {
    border-right: none;
    border-bottom: 1px solid #e5e9f0;
    padding: 0;
    overflow-x: auto;
    overflow-y: hidden;
  }

  .agent-tab-nav ul {
    display: flex;
    white-space: nowrap;
    padding: 0 8px;
  }

  .agent-tab-item {
    flex: 0 0 auto;
    padding: 12px 16px;
    border-left: none;
    border-bottom: 3px solid transparent;
  }

  .agent-tab-item.active {
    border-left-color: transparent;
    border-bottom-color: #409eff;
  }

  .agent-tab-panel {
    padding: 20px 16px;
  }
}
</style>
