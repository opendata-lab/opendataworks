<template>
  <div class="data-integration">
    <div class="page-header">
      <h2>数据集成</h2>
    </div>

    <el-tabs v-model="activeTab" class="integration-tabs">
      <el-tab-pane label="同步任务" name="sync">
        <el-card shadow="always" :body-style="{ padding: '20px' }">
          <TaskTable node-type="DATAX" />
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="数据源管理" name="datasource">
        <DataSourceManagement />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import TaskTable from '@/views/tasks/TaskTable.vue'
import DataSourceManagement from '@/views/settings/DataSourceManagement.vue'

const activeTab = ref('sync')
</script>

<style scoped>
.data-integration {
  padding: 20px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.page-header {
  margin-bottom: 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-header h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: #1a1a1a;
}

.integration-tabs {
  flex: 1;
}

.integration-tabs :deep(.el-card) {
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
</style>
