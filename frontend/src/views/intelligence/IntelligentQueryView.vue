<template>
  <div class="intelligent-query-view">
    <aside class="intelligent-query-sidebar">
      <el-menu
        :default-active="activeMenu"
        class="intelligent-query-menu"
        @select="handleMenuSelect"
      >
        <el-menu-item index="chat">
          <el-icon><ChatDotRound /></el-icon>
          <span>Agent问答</span>
        </el-menu-item>
        <el-menu-item index="skills">
          <el-icon><Collection /></el-icon>
          <span>Skills</span>
        </el-menu-item>
        <el-menu-item index="agents">
          <el-icon><User /></el-icon>
          <span>智能体</span>
        </el-menu-item>
        <el-menu-item index="chat-v2">
          <el-icon><MagicStick /></el-icon>
          <span>Chat V2</span>
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

    <main class="intelligent-query-content" :class="{ 'is-chat': activeMenu === 'chat' || activeMenu === 'chat-v2' }">
      <SkillDetailView v-if="isSkillDetailRoute" />
      <AgentDetailView v-else-if="isAgentDetailRoute" />
      <AgentStudio v-else-if="activeTab === 'agents'" />
      <SkillStudio v-else-if="activeTab === 'skills'" />
      <DataAgentConfig v-else-if="activeTab === 'models'" />
      <WidgetAccessConfig v-else-if="activeTab === 'widget'" />
      <NL2SqlChatV2 v-else-if="activeTab === 'chat-v2'" />
      <NL2SqlChat v-else />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Collection, Cpu, MagicStick, Monitor, User } from '@element-plus/icons-vue'
import NL2SqlChat from './NL2SqlChat.vue'
import NL2SqlChatV2 from './NL2SqlChatV2.vue'
import AgentStudio from './AgentStudio.vue'
import AgentDetailView from './AgentDetailView.vue'
import SkillStudio from '../settings/SkillStudio.vue'
import DataAgentConfig from '../settings/DataAgentConfig.vue'
import WidgetAccessConfig from '../settings/WidgetAccessConfig.vue'
import SkillDetailView from '../settings/SkillDetailView.vue'

const route = useRoute()
const router = useRouter()
const validTabs = new Set(['chat', 'chat-v2', 'skills', 'agents', 'models', 'widget'])

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
  const tab = String(route.query.tab || 'chat')
  return validTabs.has(tab) ? tab : 'chat'
})

const activeMenu = computed(() => activeTab.value)

const handleMenuSelect = (index) => {
  const tab = validTabs.has(index) ? index : 'chat'
  if (tab === activeTab.value && !isSkillDetailRoute.value) {
    return
  }

  router.replace({
    path: '/intelligent-query',
    query: tab === 'chat' ? {} : { tab }
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
  border-right: 1px solid #dbe3ef;
  background: #ffffff;
  overflow: hidden;
}

.intelligent-query-menu {
  height: 100%;
  border-right: none;
  padding: 12px 0;
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

  .intelligent-query-menu {
    display: flex;
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
