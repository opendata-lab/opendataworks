<template>
  <section v-loading="loading" class="tab-panel members-panel">
    <div class="list-toolbar">
      <div class="filter-controls">
        <el-input
          v-model="memberFilters.keyword"
          clearable
          placeholder="搜索成员、数据库账号"
          aria-label="搜索成员"
          class="member-search-input"
        />
        <el-select
          v-model="memberFilters.roleId"
          placeholder="全部角色"
          aria-label="按角色筛选"
          class="member-filter-select"
        >
          <el-option label="全部角色" value="all" />
          <el-option
            v-for="r in overview.roles"
            :key="r.id"
            :label="r.name"
            :value="String(r.id)"
          />
        </el-select>
        <el-select
          v-model="memberFilters.status"
          placeholder="全部状态"
          aria-label="按状态筛选"
          class="member-filter-select"
        >
          <el-option label="全部状态" value="all" />
          <el-option label="正常" value="ready" />
          <el-option label="状态异常" value="error" />
          <el-option label="执行中" value="applying" />
          <el-option label="待配置" value="pending" />
          <el-option label="已停用" value="disabled" />
        </el-select>
      </div>
      <div class="toolbar-actions">
        <el-button data-testid="authorize-agent-btn" @click="openAuthorizeDialog('service')">
          授权服务账号
        </el-button>
        <el-button
          type="primary"
          data-testid="authorize-user-btn"
          @click="openAuthorizeDialog('existing')"
        >
          授权用户
        </el-button>
      </div>
    </div>

    <el-table
      :data="filteredMembers"
      border
      empty-text="当前数据源尚无已授权成员，点击右上角「授权用户」添加"
      style="width: 100%"
    >
      <el-table-column label="成员" min-width="170">
        <template #default="{ row }">
          <span class="member-name">{{ getMemberDisplayName(row) }}</span>
          <el-tag
            v-if="row.subjectType === 'SERVICE'"
            size="small"
            type="info"
            class="subject-badge"
          >
            服务账号
          </el-tag>
          <span v-else-if="row.subjectId" class="member-sub-id">#{{ row.subjectId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="角色" min-width="240">
        <template #default="{ row }">
          <div v-if="row.roleIds && row.roleIds.length" class="role-tags-wrap">
            <span
              v-for="rid in row.roleIds"
              :key="rid"
              class="clickable-tag"
              data-testid="member-role-tag"
              @click="handleRoleClick(rid)"
            >
              <el-tag
                size="small"
                effect="plain"
                class="role-tag-item"
              >
                {{ getRoleName(rid) }}
              </el-tag>
            </span>
          </div>
          <span v-else class="muted-text">未分配</span>
        </template>
      </el-table-column>
      <el-table-column label="数据库账号" min-width="160">
        <template #default="{ row }">
          <template v-if="row.account?.managed">
            <span class="managed-account-name" :title="row.account.username">{{ row.account.username }}</span>
            <el-tag size="small" effect="plain">平台自动创建</el-tag>
          </template>
          <el-button
            v-else
            link
            type="primary"
            data-testid="bind-account-link"
            @click="openAccountDialog(row)"
          >
            {{
              row.account?.configured && row.account?.username ? row.account.username : '绑定账号'
            }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <span
            :class="{ 'clickable-status-tag': getMemberStatus(row).key === 'error' }"
            data-testid="member-status-tag"
            @click="getMemberStatus(row).key === 'error' ? openInspectDialog(row) : null"
          >
            <el-tag
              :type="getMemberStatus(row).tagType"
              size="small"
            >
              {{ getMemberStatus(row).label }}
            </el-tag>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button
            type="primary"
            text
            size="small"
            data-testid="assign-role-btn"
            @click="openRoleAssignDialog(row)"
          >
            分配角色
          </el-button>
          <el-button v-if="!row.account?.managed" type="primary" text size="small" @click="openAccountDialog(row)">
            账号
          </el-button>
          <el-button
            type="primary"
            text
            size="small"
            data-testid="inspect-member-btn"
            @click="openInspectDialog(row)"
          >
            权限诊断
          </el-button>
          <el-button type="danger" text size="small" @click="handleRemoveMember(row)">
            移除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="table-footer">
      <span>共 {{ filteredMembers.length }} 位成员</span>
    </div>
  </section>
</template>

<script setup>
import { toRefs } from 'vue'

const props = defineProps({ model: { type: Object, required: true } })
// The feature composable owns these drafts and operations; this view only renders its contract.
const {
  loading,
  overview,
  getRoleName,
  getMemberDisplayName,
  getMemberStatus,
  memberFilters,
  filteredMembers,
  openAuthorizeDialog,
  openRoleAssignDialog,
  openAccountDialog,
  handleRemoveMember,
  openInspectDialog,
  navigateToRole,
} = toRefs(props.model)

function handleRoleClick(rid) {
  if (typeof navigateToRole?.value === 'function') {
    navigateToRole.value(rid)
  } else if (typeof props.model?.navigateToRole === 'function') {
    props.model.navigateToRole(rid)
  }
}
</script>

<style scoped lang="scss">
@use './permission-shared';

.clickable-tag {
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s;

  &:hover {
    color: var(--el-color-primary);
    border-color: var(--el-color-primary-light-5);
  }
}

.clickable-status-tag {
  cursor: pointer;
}

.member-search-input {
  width: 210px;
}

.member-filter-select {
  width: 140px;
}

.member-name {
  font-weight: 500;
}

.subject-badge {
  margin-left: 8px;
}

.member-sub-id {
  font-size: 12px;
  color: #667085;
  margin-left: 6px;
}

.role-tags-wrap {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.table-footer {
  padding-top: 12px;
  font-size: 12px;
  color: #667085;
}
.managed-account-name {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
