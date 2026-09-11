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

      <div v-if="authStore.enabled && authStore.currentUser" class="settings-user">
        <el-icon><User /></el-icon>
        <span class="settings-user__name">{{ authStore.currentUser.display_name }}</span>
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
</script>

<style scoped>
.settings-layout {
  display: flex;
  height: 100%;
  background: #f8fafc;
}

.settings-sidebar {
  display: flex;
  flex-direction: column;
  width: 220px;
  flex-shrink: 0;
  background: #ffffff;
  border-right: 1px solid #e2e8f0;
}

.settings-back {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 18px 20px;
  border: none;
  background: transparent;
  font-size: 14px;
  color: #64748b;
  cursor: pointer;
}

.settings-back:hover {
  color: #0f172a;
}

.settings-menu {
  flex: 1;
  border-right: none;
}

/* A label, not an entry: it groups what follows and must not read as clickable. */
.settings-menu__group {
  padding: 16px 20px 6px;
  font-size: 12px;
  color: #94a3b8;
  letter-spacing: 0.04em;
}

.settings-user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid #e2e8f0;
  font-size: 13px;
  color: #475569;
}

.settings-user__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-content {
  flex: 1;
  min-width: 0;
  overflow: auto;
}
</style>
