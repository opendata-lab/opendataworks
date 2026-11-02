<template>
  <el-dialog
    v-model="operationDetailVisible"
    :title="getOperationDialogTitle(operationDetail)"
    width="640px"
    append-to-body
  >
    <template v-if="operationDetail">
      <div
        class="op-result-banner"
        :class="`is-${String(operationDetail.state || '').toLowerCase()}`"
        data-testid="operation-result-banner"
      >
        <div class="op-result-main">
          <strong
            >状态：{{ getOperationStateMeta(operationDetail.state).label }} ({{
              operationDetail.state
            }})</strong
          >
          <div class="op-id-line">
            操作 ID：<code data-testid="operation-result-id">{{
              operationDetail.operationId || operationDetail.id || '-'
            }}</code>
          </div>
          <p v-if="operationDetail.message" class="op-message-line">
            {{ operationDetail.message }}
          </p>
        </div>
      </div>

      <p v-if="operationDetail.state !== 'SUCCESS'" class="state-note warning-note">
        本次操作未完全成功（状态：{{ operationDetail.state }}），请根据操作 ID
        与下方步骤核对实际数据库状态，系统不会自动重放或伪造回滚。
      </p>

      <el-table
        v-if="operationDetail.steps && operationDetail.steps.length"
        :data="operationDetail.steps"
        border
        style="width: 100%; margin-top: 12px"
      >
        <el-table-column label="步骤 / SQL" min-width="240">
          <template #default="{ row }">
            <code>{{ row.sql || row.step || row.name || row.action || JSON.stringify(row) }}</code>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            {{ row.state || row.status || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="160">
          <template #default="{ row }">
            {{ row.message || row.error || '-' }}
          </template>
        </el-table-column>
      </el-table>
    </template>
    <template #footer>
      <el-button @click="operationDetailVisible = false">关闭</el-button>
      <el-button v-if="activeTab !== 'operations'" type="primary" @click="openOperations">
        查看授权记录
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { toRefs } from 'vue'

const props = defineProps({ model: { type: Object, required: true } })
// The feature composable owns these drafts and operations; this view only renders its contract.
const {
  getOperationStateMeta,
  getOperationDialogTitle,
  operationDetailVisible,
  operationDetail,
  activeTab,
  switchTab,
} = toRefs(props.model)
function openOperations() {
  operationDetailVisible.value = false
  switchTab.value('operations')
}
</script>

<style scoped lang="scss">
@use './permission-shared';

.op-result-banner {
  padding: 12px 14px;
  border-radius: 4px;
  background: #f5f7fa;
  margin-bottom: 12px;
}

.op-result-banner.is-success {
  background: #edf7f0;
  color: #287649;
}

.op-result-banner.is-partial,
.op-result-banner.is-unknown {
  background: #fff6e4;
  color: #8e620d;
}

.op-result-banner.is-failed {
  background: #fff0f0;
  color: #b63737;
}

.op-id-line {
  margin-top: 4px;
  font-size: 12px;
}

.op-message-line {
  margin: 6px 0 0;
  font-size: 13px;
}
</style>
