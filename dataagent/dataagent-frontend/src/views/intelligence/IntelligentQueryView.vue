<template>
  <div class="intelligent-query-view">
    <aside class="intelligent-query-sidebar">
      <div class="intelligent-query-brand">
        <img
          class="intelligent-query-brand__logo"
          :src="brandLogo"
          alt="DataAgent"
        />
        <span class="intelligent-query-brand__title">DataAgent</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        class="intelligent-query-menu"
        @select="handleMenuSelect"
      >
        <el-menu-item index="chat-v2">
          <el-icon><MagicStick /></el-icon>
          <span>Chat</span>
        </el-menu-item>
        <el-menu-item index="agents">
          <el-icon><User /></el-icon>
          <span>智能体</span>
        </el-menu-item>
      </el-menu>
      <div class="intelligent-query-footer">
        <div v-if="authStore.enabled && authStore.currentUser" class="intelligent-query-user">
          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="intelligent-query-user__trigger">
              <el-icon><User /></el-icon>
              <span class="intelligent-query-user__name">{{ authStore.currentUser.display_name }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <!-- Configuration lives behind this, in its own view. It is set up once
             and rarely revisited, so it does not belong beside the work. -->
        <button
          type="button"
          class="intelligent-query-settings-btn"
          title="设置"
          aria-label="设置"
          @click="router.push('/settings')"
        >
          <el-icon><Setting /></el-icon>
        </button>
      </div>
    </aside>

    <main class="intelligent-query-content" :class="{ 'is-chat': activeMenu === 'chat-v2' }">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { MagicStick, Setting, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { withAgentContext } from '@/router/agentContext'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const handleUserCommand = async (command) => {
  if (command !== 'logout') return
  const agentId = String(route.query?.agent_id || '').trim()
  const redirect = route.fullPath || (agentId ? `/chat?agent_id=${encodeURIComponent(agentId)}` : '/chat')
  await authStore.logout()
  router.push({ path: '/login', query: { redirect } })
}

// Each menu entry maps directly to a user-facing page route.
const MENU_TO_PATH = {
  'chat-v2': '/chat',
  skills: '/skills',
  agents: '/agents',
  models: '/models',
  mcp: '/mcp',
  widget: '/widget-access',
  evaluations: '/evaluations',
  'eval-results': '/evaluation-results'
}

const brandLogo = `${import.meta.env.BASE_URL}opendataworks-icon.svg`

// The active menu follows the matched route's meta.tab, so it stays correct on
// direct navigation and on refresh of detail routes (e.g. skill/agent detail).
const activeMenu = computed(() => {
  const tab = String(route.meta?.tab || '')
  return MENU_TO_PATH[tab] ? tab : 'chat-v2'
})

const handleMenuSelect = (index) => {
  const path = MENU_TO_PATH[index] || MENU_TO_PATH['chat-v2']
  if (route.path === path) {
    return
  }
  router.push(withAgentContext({ path }, route.query))
}
</script>

<style scoped>
.intelligent-query-view {
  height: 100%;
  min-height: 0;
  display: grid;
  grid-template-columns: 208px minmax(0, 1fr);
  background: #f4f7fb;
  overflow: hidden;
}

.intelligent-query-sidebar {
  min-width: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #dbe3ef;
  background: #ffffff;
  overflow: hidden;
}

.intelligent-query-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px;
  border-bottom: 1px solid #eef2f8;
}

.intelligent-query-brand__logo {
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  border-radius: 8px;
}

.intelligent-query-brand__title {
  min-width: 0;
  font-size: 17px;
  font-weight: 600;
  color: #1f2d3d;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.intelligent-query-menu {
  flex: 1 1 auto;
  min-height: 0;
  border-right: none;
  padding: 8px 0;
}

.intelligent-query-menu :deep(.el-menu-item) {
  height: 44px;
  line-height: 44px;
}

.intelligent-query-footer {
  margin-top: auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #e2e8f0;
}

.intelligent-query-settings-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  transition: background 150ms ease, color 150ms ease;
}

.intelligent-query-settings-btn:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.intelligent-query-user {
  flex: 0 0 auto;
  padding: 12px 16px;
  border-top: 1px solid #eef2f8;
}

.intelligent-query-user__trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #1f2d3d;
  font-size: 14px;
}

.intelligent-query-user__name {
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.intelligent-query-content {
  min-width: 0;
  min-height: 0;
  height: 100%;
  padding: 16px;
  overflow: auto;
  box-sizing: border-box;
}

.intelligent-query-content.is-chat {
  overflow: hidden;
}

@media (max-width: 768px) {
  .intelligent-query-view {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(0, 1fr);
  }

  .intelligent-query-sidebar {
    border-right: none;
    border-bottom: 1px solid #dbe3ef;
  }

  .intelligent-query-brand {
    display: none;
  }

  .intelligent-query-menu {
    display: flex;
    flex: 0 0 auto;
    height: auto;
    padding: 6px;
    overflow-x: auto;
  }

  .intelligent-query-menu :deep(.el-menu-item) {
    flex: 0 0 auto;
    height: 38px;
    line-height: 38px;
  }

  .intelligent-query-content {
    padding: 12px;
  }
}
</style>
