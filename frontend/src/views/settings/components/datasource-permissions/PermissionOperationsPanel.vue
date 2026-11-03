<template>
  <section class="tab-panel operations-panel">
    <div class="list-toolbar">
      <span class="sub-title">展示当前数据源角色授权、原生回读与成员变更记录</span>
      <el-button size="small" :loading="operationsState.loading" @click="loadOperations">
        刷新记录
      </el-button>
    </div>

    <el-alert
      v-if="operationsState.error"
      :title="operationsState.error"
      type="error"
      :closable="false"
      show-icon
      class="top-alert"
    >
      <el-button text type="primary" @click="loadOperations">重试</el-button>
    </el-alert>

    <el-table
      v-else
      v-loading="operationsState.loading"
      :data="operationsState.list"
      border
      empty-text="暂无授权操作记录"
      style="width: 100%"
    >
      <el-table-column label="操作 ID / 时间" min-width="190">
        <template #default="{ row }">
          <code class="op-id-code">{{ row.id }}</code>
          <div class="sub-cell-text">{{ formatDateTime(row.createdAt) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="对象" min-width="160">
        <template #default="{ row }">
          <span v-if="row.roleId">角色：{{ getRoleName(row.roleId) }}</span>
          <span v-else-if="row.subjectId">{{ row.subjectType }}：{{ row.subjectId }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作人" width="120">
        <template #default="{ row }">{{ row.operatorId || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="getOperationStateMeta(row.state).tagType" size="small">
            {{ getOperationStateMeta(row.state).label }} ({{ row.state }})
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="结果说明" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.message || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" text size="small" @click="openOperationDetail(row)">
            查看步骤
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup>
import { toRefs } from 'vue'

const props = defineProps({ model: { type: Object, required: true } })
// The feature composable owns these drafts and operations; this view only renders its contract.
const {
  formatDateTime,
  getOperationStateMeta,
  getRoleName,
  operationsState,
  loadOperations,
  openOperationDetail,
} = toRefs(props.model)
</script>

<style scoped lang="scss">
@use './permission-shared';

.op-id-code {
  font-size: 12px;
}
</style>
