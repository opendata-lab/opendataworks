<template>
  <div class="settings-page">
    <div class="settings-body">
      <nav class="settings-nav" aria-label="配置分组">
        <button
          v-for="item in navItems"
          :key="item.key"
          type="button"
          class="settings-nav-item"
          :class="{ 'is-active': activeTab === item.key }"
          :aria-current="activeTab === item.key ? 'page' : undefined"
          @click="activeTab = item.key"
        >
          <el-icon class="settings-nav-icon"><component :is="item.icon" /></el-icon>
          <span class="settings-nav-label">{{ item.label }}</span>
        </button>
      </nav>

      <section class="settings-content">
        <DolphinConfig v-if="activeTab === 'dolphin'" />
        <MinioConfigManagement v-else-if="activeTab === 'minio'" />
        <AgentSettings v-else-if="activeTab === 'agent'" />
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Cpu, FolderOpened, MagicStick } from '@element-plus/icons-vue'
import DolphinConfig from './DolphinConfig.vue'
import MinioConfigManagement from './MinioConfigManagement.vue'
import AgentSettings from './AgentSettings.vue'

const route = useRoute()
const router = useRouter()

// 分组导航：左侧竖向排列，新增分组时在此登记即可
const navItems = [
  { key: 'dolphin', label: 'Dolphin 配置', icon: Cpu },
  { key: 'minio', label: 'MinIO 环境', icon: FolderOpened },
  { key: 'agent', label: '智能助手', icon: MagicStick }
]

const availableTabs = new Set(navItems.map((item) => item.key))
const legacyTabMap = {
  dataagent: true,
  skills: true
}
const activeTab = ref(availableTabs.has(route.query.tab) ? route.query.tab : 'dolphin')

const redirectLegacyTab = (tab) => {
  if (!legacyTabMap[tab]) {
    return false
  }
  router.replace({
    path: '/intelligent-query',
    query: {}
  })
  return true
}

watch(
  () => route.query.tab,
  (tab) => {
    if (redirectLegacyTab(tab)) {
      return
    }
    if (availableTabs.has(tab)) {
      activeTab.value = tab
    }
  },
  { immediate: true }
)

watch(activeTab, (tab) => {
  router.replace({
    path: route.path,
    query: {
      ...route.query,
      tab
    }
  })
})
</script>

<style scoped>
.settings-page {
  padding: 20px 24px 24px;
  height: 100%;
  display: flex;
  flex-direction: column;
  min-width: 0;
  box-sizing: border-box;
  overflow-x: hidden;
}

.settings-body {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: grid;
  grid-template-columns: 216px minmax(0, 1fr);
  gap: 24px;
  align-items: start;
}

.settings-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  position: sticky;
  top: 0;
}

.settings-nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px 12px;
  border: 1px solid transparent;
  border-radius: var(--odw-radius-md);
  background: transparent;
  text-align: left;
  cursor: pointer;
  color: var(--odw-text-primary);
  transition: background-color var(--odw-transition), border-color var(--odw-transition),
    color var(--odw-transition);
}

.settings-nav-item:hover {
  background: rgba(44, 82, 130, 0.06);
}

.settings-nav-item.is-active {
  background: var(--odw-bg-card);
  border-color: var(--odw-border);
  color: var(--odw-primary);
  box-shadow: 0 1px 2px rgba(44, 82, 130, 0.06);
}

.settings-nav-icon {
  font-size: 16px;
  color: var(--odw-text-secondary);
  flex: none;
}

.settings-nav-item.is-active .settings-nav-icon {
  color: var(--odw-primary);
}

.settings-nav-label {
  font-size: 14px;
  font-weight: 500;
  line-height: 1.4;
}

.settings-content {
  min-width: 0;
  padding: 20px 24px 24px;
  background: var(--odw-bg-card);
  border: 1px solid var(--odw-border);
  border-radius: var(--odw-radius-md);
}

/* 分组内部保留各自的最大宽度约束时不再居中，避免内容偏离左侧栅格 */
.settings-content :deep(.dolphin-config),
.settings-content :deep(.minio-config) {
  max-width: none;
  margin: 0;
}

@media (max-width: 900px) {
  .settings-body {
    grid-template-columns: minmax(0, 1fr);
    gap: 16px;
  }

  .settings-nav {
    position: static;
    flex-direction: row;
    flex-wrap: wrap;
    gap: 8px;
  }

  .settings-nav-item {
    width: auto;
  }
}

@media (max-width: 768px) {
  .settings-page {
    padding: 12px;
  }

}
</style>
