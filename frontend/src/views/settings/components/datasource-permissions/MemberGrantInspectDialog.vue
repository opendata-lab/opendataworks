<template>
  <el-dialog
    v-model="inspectDialogVisible"
    :title="dialogTitle"
    width="900px"
    append-to-body
    class="inspect-dialog"
  >
    <template #header>
      <div class="dialog-title-text">{{ dialogTitle }}</div>
    </template>
    <div v-loading="inspectState.loading" class="inspect-dialog-body">
      <div class="dialog-source-strip">
        <span class="strip-item">
          数据源：<strong>{{ activeClusterName }}</strong>
        </span>
        <span class="strip-item">
          成员状态：
          <el-tag :type="memberStatusMeta.tagType" size="small">
            {{ memberStatusMeta.label }}
          </el-tag>
        </span>
        <span class="strip-item">
          绑定账号：<code>{{ accountUsername }}</code>
          <el-tag v-if="isManagedAccount" size="small" effect="plain" class="account-badge">
            平台自动创建
          </el-tag>
        </span>
        <span v-if="inspectState.data?.nativeIdentity" class="strip-item">
          原生身份：<code>{{ inspectState.data.nativeIdentity }}</code>
        </span>
      </div>

      <el-alert
        v-if="inspectState.error"
        :title="inspectState.error"
        type="error"
        :closable="false"
        show-icon
        class="top-alert"
      />

      <!-- Diagnosis Issues Banner -->
      <div v-if="diagnosisIssues?.length" class="diagnosis-issues-card">
        <div class="issues-card-header">
          <el-icon class="issues-icon"><WarningFilled /></el-icon>
          <span class="issues-title">诊断发现 {{ diagnosisIssues.length }} 项阻断或差异原因：</span>
        </div>
        <div class="issues-list">
          <div
            v-for="(issue, idx) in diagnosisIssues"
            :key="idx"
            class="issue-row"
            data-testid="diagnosis-issue-item"
          >
            <div class="issue-main">
              <span class="issue-index">{{ idx + 1 }}.</span>
              <template v-if="issue.type === 'connection'">
                <span class="issue-desc">数据库连接或账号异常：{{ issue.message }}</span>
              </template>
              <template v-else-if="issue.type === 'operation'">
                <span class="issue-desc">
                  最近一次操作失败 (操作ID #{{ issue.operation.operationId || issue.operation.id }} · {{ formatDateTime(issue.operation.createdAt) }})：{{ issue.operation.message || issue.operation.state }}
                </span>
                <div class="issue-actions">
                  <el-button
                    size="small"
                    text
                    type="primary"
                    data-testid="view-op-steps-btn"
                    @click="openOperationDetail ? openOperationDetail(issue.operation) : null"
                  >
                    查看失败步骤详情
                  </el-button>
                  <el-button
                    size="small"
                    type="primary"
                    :loading="reapplying"
                    data-testid="reapply-roles-btn"
                    @click="handleReapplyRoles"
                  >
                    重新下发成员角色
                  </el-button>
                </div>
              </template>
              <template v-else-if="issue.type === 'draft'">
                <span class="issue-desc">
                  所属角色「{{ issue.role.name }}」处于 [草稿（待预览激活）]，其配置的 {{ issue.grantCount }} 项权限尚未在数据库生效
                </span>
                <div class="issue-actions">
                  <el-button
                    size="small"
                    type="primary"
                    data-testid="goto-role-btn"
                    @click="goToRole(issue.role.id)"
                  >
                    去角色页预览并激活
                  </el-button>
                </div>
              </template>
            </div>
          </div>
        </div>
      </div>

      <div class="inspect-toolbar">
        <el-input
          v-model="filterKeyword"
          clearable
          placeholder="搜索库名、表名或权限 (如 sales.orders)"
          class="inspect-search-input"
          data-testid="inspect-search-input"
        />
      </div>

      <h4 class="inspect-section-title">平台期望权限（来自所属角色）</h4>
      <el-table
        :data="filteredExpected"
        border
        empty-text="所属角色未配置资源"
        style="width: 100%"
        class="diagnosis-table"
        data-testid="expected-grants-table"
      >
        <el-table-column prop="scope" label="资源" min-width="200" />
        <el-table-column prop="actionsText" label="权限" min-width="180" />
        <el-table-column label="来源角色" min-width="160">
          <template #default="{ row }">
            {{ row.roleName }}
            <el-tag v-if="row.isDraft" size="small" type="warning">草稿未生效</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <h4 class="inspect-section-title">数据库实际回读</h4>
      <el-table
        :data="filteredNative"
        border
        :empty-text="inspectState.data?.connectionError ? '未能连接数据库，见上方原因' : '未回读到授权'"
        style="width: 100%"
        class="diagnosis-table"
        data-testid="native-grants-table"
      >
        <el-table-column prop="scope" label="回读记录" min-width="320" show-overflow-tooltip />
        <el-table-column prop="actionsText" label="权限" min-width="120" />
        <el-table-column prop="sourceText" label="来源" min-width="120" />
      </el-table>

      <p
        v-if="inspectState.data?.notice"
        class="state-note warning-note"
        data-testid="inspect-notice"
      >
        {{ inspectState.data.notice }}
      </p>
    </div>
    <template #footer>
      <el-button @click="inspectDialogVisible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, toRefs } from 'vue'
import { WarningFilled } from '@element-plus/icons-vue'

const props = defineProps({ model: { type: Object, required: true } })

const {
  activeClusterName,
  getMemberDisplayName,
  getMemberStatus,
  formatDateTime,
  inspectDialogVisible,
  inspectTarget,
  inspectState,
  diagnosisIssues,
  expectedGrantRows,
  normalizedInspectGrants,
  reapplying,
  handleReapplyRoles,
  openOperationDetail,
  navigateToRole,
} = toRefs(props.model)

const filterKeyword = ref('')

const dialogTitle = computed(() => {
  const memberName = inspectTarget.value && getMemberDisplayName.value
    ? getMemberDisplayName.value(inspectTarget.value)
    : ''
  const cluster = activeClusterName.value ? ` · ${activeClusterName.value}` : ''
  return `${memberName}${cluster} · 权限诊断与实际权限`
})

const memberStatusMeta = computed(() => {
  if (inspectTarget.value && getMemberStatus.value) {
    return getMemberStatus.value(inspectTarget.value)
  }
  const s = inspectState.value?.data?.memberStatus
  if (s === 'ACTIVE') return { label: '正常', tagType: 'success' }
  if (s === 'ERROR') return { label: '状态异常', tagType: 'danger' }
  if (s === 'APPLYING') return { label: '执行中', tagType: 'warning' }
  if (s === 'DISABLED') return { label: '已停用', tagType: 'danger' }
  return { label: '待配置', tagType: 'warning' }
})

const accountUsername = computed(() => {
  return (
    inspectState.value?.data?.username ||
    inspectState.value?.data?.account?.username ||
    inspectTarget.value?.account?.username ||
    '未绑定'
  )
})

const isManagedAccount = computed(() => {
  return Boolean(
    inspectState.value?.data?.account?.managed ||
    inspectTarget.value?.account?.managed
  )
})

const matches = (row, fields) => {
  const kw = String(filterKeyword.value || '').trim().toLowerCase()
  return !kw || fields.some((f) => String(row[f] || '').toLowerCase().includes(kw))
}
const filteredExpected = computed(() =>
  (expectedGrantRows.value || []).filter((r) => matches(r, ['scope', 'actionsText', 'roleName']))
)
const filteredNative = computed(() =>
  (normalizedInspectGrants.value || []).filter((r) => matches(r, ['scope', 'actionsText', 'sourceText']))
)

function goToRole(roleId) {
  inspectDialogVisible.value = false
  if (typeof navigateToRole?.value === 'function') {
    navigateToRole.value(roleId)
  } else if (typeof props.model?.navigateToRole === 'function') {
    props.model.navigateToRole(roleId)
  }
}
</script>

<style scoped lang="scss">
@use './permission-shared';

.dialog-source-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
  padding: 10px 14px;
  background-color: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 13px;
  margin-bottom: 14px;

  .strip-item {
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }

  .account-badge {
    margin-left: 4px;
  }
}

.diagnosis-issues-card {
  border: 1px solid var(--el-color-warning-light-5);
  background-color: var(--el-color-warning-light-9);
  border-radius: 4px;
  padding: 12px 14px;
  margin-bottom: 14px;

  .issues-card-header {
    display: flex;
    align-items: center;
    gap: 6px;
    font-weight: 600;
    font-size: 13px;
    color: var(--el-color-warning-dark-2);
    margin-bottom: 8px;

    .issues-icon {
      font-size: 16px;
    }
  }

  .issues-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .issue-row {
    font-size: 13px;
    color: var(--el-text-color-primary);

    .issue-main {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }

    .issue-index {
      font-weight: 500;
    }

    .issue-actions {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      margin-left: 8px;
    }
  }
}

.inspect-section-title {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.inspect-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 12px;

  .inspect-search-input {
    max-width: 380px;
  }
}

.resource-scope-text {
  font-weight: 500;
}

.diagnosis-table {
  margin-bottom: 14px;
}
</style>
