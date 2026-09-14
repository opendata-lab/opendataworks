<template>
  <div class="settings-layout">
    <aside class="settings-sidebar">
      <button type="button" class="settings-back" @click="router.push('/chat')">
        <el-icon><ArrowLeft /></el-icon>
        <span>返回工作区</span>
      </button>

      <el-menu :default-active="activeMenu" class="settings-menu" @select="handleSelect">
        <template v-for="group in visibleGroups" :key="group.title">
          <div class="settings-menu__group">{{ group.title }}</div>
          <el-menu-item v-for="item in group.items" :key="item.index" :index="item.index">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </el-menu-item>
        </template>
      </el-menu>

      <div v-if="authStore.enabled && authStore.currentUser" class="settings-footer">
        <div class="settings-user">
          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="settings-user__trigger">
              <el-icon><User /></el-icon>
              <span class="settings-user__name">{{ authStore.currentUser.display_name }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </aside>

    <main class="settings-content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Collection, Connection, Cpu, DataBoard, Monitor, TrendCharts, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

// Grouped the way an operator thinks about them: what the agent can do, how it
// is reached, and what is measured. A flat list of six made every entry look
// equally routine.
const GROUPS = [
  {
    title: 'Agent 能力',
    adminOnly: false,
    items: [
      { index: 'skills', label: 'Skills', icon: Collection, path: '/settings/skills' },
      { index: 'mcp', label: 'MCP 服务', icon: Connection, path: '/settings/mcp', adminOnly: true },
      { index: 'models', label: '模型管理', icon: Cpu, path: '/settings/models', adminOnly: true }
    ]
  },
  {
    title: '接入',
    adminOnly: true,
    items: [{ index: 'widget', label: 'Widget 接入', icon: Monitor, path: '/settings/widget-access' }]
  },
  {
    title: '评测',
    adminOnly: true,
    items: [
      { index: 'evaluations', label: '评测集', icon: DataBoard, path: '/settings/evaluations' },
      { index: 'eval-results', label: '评测结果', icon: TrendCharts, path: '/settings/evaluation-results' }
    ]
  }
]

const visibleGroups = computed(() =>
  GROUPS.map((group) => ({
    ...group,
    items: group.items.filter((item) => authStore.isAdmin || !(item.adminOnly || group.adminOnly))
  })).filter((group) => group.items.length > 0)
)

const activeMenu = computed(() => String(route.meta?.tab || 'skills'))

const handleSelect = (index) => {
  for (const group of GROUPS) {
    const item = group.items.find((entry) => entry.index === index)
    if (item) {
      router.push(item.path)
      return
    }
  }
}

const handleUserCommand = async (command) => {
  if (command !== 'logout') return
  const agentId = String(route.query?.agent_id || '').trim()
  const redirect = route.fullPath || (agentId ? `/chat?agent_id=${encodeURIComponent(agentId)}` : '/chat')
  await authStore.logout()
  router.push({ path: '/login', query: { redirect } })
}
</script>

<style scoped>
.settings-layout {
  display: flex;
  height: 100%;
  background: #f4f7fb;
}

.settings-sidebar {
  display: flex;
  flex-direction: column;
  width: 208px;
  flex-shrink: 0;
  background: #ffffff;
  border-right: 1px solid #dbe3ef;
}

.settings-back {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 69px;
  padding: 0 16px;
  border: none;
  border-bottom: 1px solid #eef2f8;
  background: transparent;
  font-size: 14px;
  font-weight: 500;
  color: #64748b;
  cursor: pointer;
  box-sizing: border-box;
  transition: color 150ms ease;
}

.settings-back:hover {
  color: #0f172a;
}

.settings-menu {
  flex: 1 1 auto;
  min-height: 0;
  border-right: none;
  padding: 8px 0;
  overflow-y: auto;
}

.settings-menu :deep(.el-menu-item) {
  height: 44px;
  line-height: 44px;
}

/* A label, not an entry: it groups what follows and must not read as clickable. */
.settings-menu__group {
  padding: 16px 20px 6px;
  font-size: 12px;
  color: #94a3b8;
  letter-spacing: 0.04em;
}

.settings-footer {
  margin-top: auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #e2e8f0;
}

.settings-user {
  min-width: 0;
  flex: 1 1 auto;
}

.settings-user__trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #1f2d3d;
  font-size: 14px;
}

.settings-user__name {
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.settings-content {
  flex: 1;
  min-width: 0;
  padding: 24px;
  overflow: auto;
}

@media (max-width: 768px) {
  .settings-layout {
    flex-direction: column;
  }

  .settings-sidebar {
    width: 100%;
    border-right: none;
    border-bottom: 1px solid #dbe3ef;
  }

  .settings-content {
    padding: 16px;
  }
}
</style>
