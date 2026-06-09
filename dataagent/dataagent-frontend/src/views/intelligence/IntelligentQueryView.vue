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
        <el-menu-item index="skills">
          <el-icon><Collection /></el-icon>
          <span>Skills</span>
        </el-menu-item>
        <el-menu-item index="agents">
          <el-icon><User /></el-icon>
          <span>智能体</span>
        </el-menu-item>
        <el-menu-item index="models">
          <el-icon><Cpu /></el-icon>
          <span>模型管理</span>
        </el-menu-item>
        <el-menu-item index="widget">
          <el-icon><Monitor /></el-icon>
          <span>Widget 接入</span>
        </el-menu-item>
      </el-menu>
    </aside>

    <main class="intelligent-query-content" :class="{ 'is-chat': activeMenu === 'chat-v2' }">
      <SkillDetailView v-if="isSkillDetailRoute" />
      <AgentDetailView v-else-if="isAgentDetailRoute" />
      <AgentStudio v-else-if="activeTab === 'agents'" />
      <SkillStudio v-else-if="activeTab === 'skills'" />
      <DataAgentConfig v-else-if="activeTab === 'models'" />
      <WidgetAccessConfig v-else-if="activeTab === 'widget'" />
      <NL2SqlChatV2 v-else />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Collection, Cpu, MagicStick, Monitor, User } from '@element-plus/icons-vue'
import NL2SqlChatV2 from './NL2SqlChatV2.vue'
import AgentStudio from './AgentStudio.vue'
import AgentDetailView from './AgentDetailView.vue'
import SkillStudio from '../settings/SkillStudio.vue'
import DataAgentConfig from '../settings/DataAgentConfig.vue'
import WidgetAccessConfig from '../settings/WidgetAccessConfig.vue'
import SkillDetailView from '../settings/SkillDetailView.vue'

const route = useRoute()
const router = useRouter()
const validTabs = new Set(['chat-v2', 'skills', 'agents', 'models', 'widget'])

const brandLogo = `${import.meta.env.BASE_URL}opendataworks-icon.svg`

const isSkillDetailRoute = computed(() => (
  route.name === 'IntelligentQuerySkillDetail' || route.path.startsWith('/intelligent-query/skills/')
))

const isAgentDetailRoute = computed(() => (
  route.name === 'IntelligentQueryAgentDetail' || route.path.startsWith('/intelligent-query/agents/')
))

const activeTab = computed(() => {
  if (isSkillDetailRoute.value) {
    return 'skills'
  }
  if (isAgentDetailRoute.value) {
    return 'agents'
  }
  const tab = String(route.query.tab || 'chat-v2')
  return validTabs.has(tab) ? tab : 'chat-v2'
})

const activeMenu = computed(() => activeTab.value)

const handleMenuSelect = (index) => {
  const tab = validTabs.has(index) ? index : 'chat-v2'
  if (tab === activeTab.value && !isSkillDetailRoute.value) {
    return
  }

  router.replace({
    path: '/intelligent-query',
    query: tab === 'chat-v2' ? {} : { tab }
  })

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
