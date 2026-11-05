<template>
  <section class="tab-panel operations-panel">
    <div class="list-toolbar">
      <div class="filter-controls">
        <el-input
          v-model="opFilters.keyword"
          clearable
          placeholder="搜索操作ID、说明、对象"
          aria-label="搜索操作记录"
          class="op-search-input"
          data-testid="operations-filter-keyword"
        />
        <el-select
          v-model="opFilters.target"
          placeholder="全部对象"
          aria-label="按对象筛选"
          class="op-filter-select"
          data-testid="operations-filter-target"
        >
          <el-option label="全部对象" value="all" />
          <el-option-group v-if="overview?.roles?.length" label="角色">
            <el-option
              v-for="r in overview.roles"
              :key="'role-' + r.id"
              :label="`角色：${r.name}`"
              :value="'role:' + r.id"
            />
          </el-option-group>
          <el-option-group v-if="overview?.members?.length" label="成员">
            <el-option
              v-for="m in overview.members"
              :key="'member-' + m.subjectId"
              :label="`${m.subjectType === 'SERVICE' ? '服务账号' : '用户'}：${m.name || m.username || m.subjectId}`"
              :value="'member:' + m.subjectId"
            />
          </el-option-group>
        </el-select>
        <el-select
          v-model="opFilters.state"
          placeholder="全部状态"
          aria-label="按状态筛选"
          class="op-state-select"
          data-testid="operations-filter-state"
        >
          <el-option label="全部状态" value="all" />
          <el-option label="已生效" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
          <el-option label="部分成功" value="PARTIAL" />
          <el-option label="待核对" value="UNKNOWN" />
          <el-option label="执行中" value="APPLYING" />
        </el-select>
      </div>
      <div class="toolbar-actions">
        <el-button size="small" :loading="operationsState.loading" @click="loadOperations">
          刷新记录
        </el-button>
      </div>
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
      :data="filteredOperations"
      border
      empty-text="暂无授权操作记录"
      style="width: 100%"
    >
      <el-table-column label="操作 ID / 时间" min-width="190">
        <template #default="{ row }">
          <code class="op-id-code">{{ row.id || row.operationId }}</code>
          <div class="sub-cell-text">{{ formatDateTime(row.createdAt) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="对象" min-width="160">
        <template #default="{ row }">
          <span v-if="row.roleId">角色：{{ getRoleName(row.roleId) }}</span>
          <span v-else-if="row.subjectId"
            >{{ row.subjectType === 'SERVICE' ? '服务账号' : '用户' }}：{{
              getPlatformUserName(row.subjectId)
            }}</span
          >
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作人" width="120">
        <template #default="{ row }">{{ getPlatformUserName(row.operatorId) || '-' }}</template>
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

    <div class="table-footer">
      <span>共 {{ filteredOperations.length }} 条记录</span>
    </div>
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
  getPlatformUserName,
  operationsState,
  loadOperations,
  openOperationDetail,
  opFilters,
  filteredOperations,
  overview,
} = toRefs(props.model)
</script>

<style scoped lang="scss">
@use './permission-shared';

.op-search-input {
  width: 210px;
}

.op-filter-select {
  width: 170px;
}

.op-state-select {
  width: 130px;
}

.op-id-code {
  font-size: 12px;
}

.table-footer {
  padding-top: 12px;
  font-size: 12px;
  color: #667085;
}
</style>
