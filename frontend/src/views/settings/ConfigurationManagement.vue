<template>
  <div class="configuration-management">
    <div class="page-header">
      <h2>配置管理</h2>
    </div>

    <el-card shadow="never" class="config-tabs-card">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="Dolphin 配置" name="dolphin" lazy>
          <DolphinConfig />
        </el-tab-pane>
        <el-tab-pane label="MinIO 环境" name="minio" lazy>
          <MinioConfigManagement />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DolphinConfig from './DolphinConfig.vue'
import MinioConfigManagement from './MinioConfigManagement.vue'

const route = useRoute()
const router = useRouter()
const availableTabs = new Set(['dolphin', 'minio'])
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
.configuration-management {
  padding: 20px;
  height: 100%;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow-x: hidden;
  box-sizing: border-box;
}

.page-header {
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: #1a1a1a;
}

.config-tabs-card :deep(.el-card__body) {
  padding: 16px;
}

.config-tabs-card {
  min-width: 0;
}

.config-tabs-card :deep(.el-tabs),
.config-tabs-card :deep(.el-tab-pane) {
  min-width: 0;
}

.config-tabs-card :deep(.el-tabs__nav-wrap) {
  overflow-x: auto;
}

@media (max-width: 768px) {
  .configuration-management {
    padding: 12px;
  }

  .page-header {
    margin-bottom: 14px;
  }

  .page-header h2 {
    font-size: 22px;
  }

  .config-tabs-card :deep(.el-card__body) {
    padding: 12px;
  }
}
</style>
