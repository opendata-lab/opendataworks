<template>
  <section class="agent-studio">
    <header class="agent-studio-head">
      <div>
        <h2>智能体</h2>
      </div>
      <el-button v-if="canManage" type="primary" :icon="Plus" :loading="creating" @click="handleCreate">新建智能体</el-button>
    </header>

    <el-skeleton v-if="loading" :rows="6" animated />

    <div v-else class="agent-grid">
      <article
        v-for="agent in agents"
        :key="agent.agent_id"
        class="agent-card"
      >
        <div class="agent-card-main">
          <div class="agent-card-title-row">
            <h3>{{ agent.name }}</h3>
            <div class="agent-card-tags">
              <el-tag v-if="agent.is_default" size="small" type="info">默认</el-tag>
              <span v-if="agent.is_default || agent.is_builtin" class="agent-built-in-tag">内置</span>
              <el-tag v-if="visibilityTag(agent)" size="small" type="warning">{{ visibilityTag(agent) }}</el-tag>
            </div>
          </div>
          <p>{{ agent.description || '未配置描述' }}</p>
        </div>

        <div class="agent-card-meta">
          <span>{{ agent.skill_folders?.length || 0 }} Skills</span>
          <span>{{ agent.allowed_tools?.length || 0 }} 工具</span>
          <span>{{ agent.mcp_server_ids?.length || 0 }} MCP</span>
          <span>{{ agent.data_scope?.allowed_scopes?.length || 0 }} Schema</span>
        </div>

        <div class="agent-card-actions">
          <el-tooltip content="开启对话" placement="top">
            <el-button :icon="ChatLineRound" circle @click="handleChat(agent)" />
          </el-tooltip>
          <el-tooltip :content="canManage ? '查看编辑' : '查看详情'" placement="top">
            <el-button :icon="canManage ? Edit : View" circle @click="handleDetail(agent)" />
          </el-tooltip>
          <el-tooltip v-if="canManage && !isBuiltinAgent(agent)" content="删除" placement="top">
            <el-button :icon="Delete" circle type="danger" plain @click="handleDelete(agent)" />
          </el-tooltip>
        </div>
      </article>

      <el-empty v-if="!agents.length" description="暂无智能体" />
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatLineRound, Delete, Edit, Plus, View } from '@element-plus/icons-vue'
import { dataagentApi } from '@/api/dataagent'
import { useAuthStore } from '@/stores/auth'
import { withAgentContext } from '@/router/agentContext'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const canManage = computed(() => authStore.isAdmin)
const agents = ref([])
const loading = ref(false)
const creating = ref(false)

const isBuiltinAgent = (agent) => Boolean(agent?.is_builtin || agent?.is_default)

// 全部可见（默认）不加标签，仅受限模式提示可见范围。
const VISIBILITY_TAG_LABELS = { authenticated: '仅登录用户', selected: '指定用户' }
const visibilityTag = (agent) => VISIBILITY_TAG_LABELS[String(agent?.visibility_mode || '')] || ''

const loadAgents = async () => {
  loading.value = true
  try {
    agents.value = await dataagentApi.listAgentProfiles()
  } finally {
    loading.value = false
  }
}

const handleCreate = async () => {
  if (!canManage.value) return
  creating.value = true
  try {
    const created = await dataagentApi.createAgent({
      name: '新智能体',
      description: '',
      system_prompt: '',
      allowed_tools: ['Skill', 'Bash', 'Read', 'LS', 'Glob', 'Grep'],
      mcp_server_ids: [],
      skill_folders: [],
      max_turns: 0,
      env_vars: {},
      data_scope: { allowed_scopes: [] }
    })
    await router.push(withAgentContext({
      name: 'IntelligentQueryAgentDetail',
      params: { agentId: created.agent_id }
    }, route.query))
  } finally {
    creating.value = false
  }
}

const handleDetail = (agent) => {
  router.push(withAgentContext({
    name: 'IntelligentQueryAgentDetail',
    params: { agentId: agent.agent_id }
  }, route.query))
}

const handleChat = (agent) => {
  router.push(withAgentContext({
    path: '/chat',
    query: { agent_id: agent.agent_id }
  }, route.query))
}

const handleDelete = async (agent) => {
  if (!canManage.value) return
  await ElMessageBox.confirm(`确认删除智能体「${agent.name}」？`, '删除智能体', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  })
  await dataagentApi.deleteAgent(agent.agent_id)
  ElMessage.success('已删除')
  await loadAgents()
}

onMounted(loadAgents)
</script>

<style scoped>
.agent-studio {
  min-height: 100%;
}

.agent-studio-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.agent-studio-head h2 {
  margin: 0;
  color: #1f2937;
  font-size: 22px;
  font-weight: 700;
}

.agent-studio-head p {
  margin: 6px 0 0;
  color: #667085;
  font-size: 13px;
}

.agent-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}

.agent-card {
  display: flex;
  min-height: 208px;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  border: 1px solid #dfe7f1;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.035);
}

.agent-card-main {
  flex: 1;
  min-width: 0;
}

.agent-card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.agent-card-tags {
  display: flex;
  flex-shrink: 0;
  gap: 6px;
}

.agent-built-in-tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 7px;
  border: 1px solid #b7e3c2;
  border-radius: 4px;
  background: #f0f9eb;
  color: #2f7d32;
  font-size: 12px;
  line-height: 1;
}

.agent-card h3 {
  margin: 0;
  overflow: hidden;
  color: #1f2937;
  font-size: 17px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-card p {
  display: -webkit-box;
  margin: 10px 0 0;
  overflow: hidden;
  color: #667085;
  font-size: 13px;
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.agent-card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.agent-card-meta span {
  padding: 4px 8px;
  border-radius: 6px;
  background: #f1f5f9;
  color: #475467;
  font-size: 12px;
}

.agent-card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
