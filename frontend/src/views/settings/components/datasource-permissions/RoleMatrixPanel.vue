<template>
  <section v-loading="loading" class="tab-panel roles-panel">
    <div class="role-layout">
      <aside class="role-sidebar">
        <div class="role-sidebar-head">
          <span>{{ overview.roles.length }} 个角色</span>
          <el-button
            type="primary"
            link
            size="small"
            data-testid="new-role-btn"
            @click="openCreateRoleDialog"
          >
            新建角色
          </el-button>
        </div>
        <el-input
          v-model="roleSearchKeyword"
          clearable
          size="small"
          placeholder="搜索角色"
          aria-label="搜索角色"
          class="role-search-input"
        />
        <div v-if="filteredRoles.length" class="role-list-items">
          <button
            v-for="r in filteredRoles"
            :key="r.id"
            type="button"
            :class="['role-nav-item', { active: String(selectedRoleId) === String(r.id) }]"
            @click="selectRole(r.id)"
          >
            <div class="role-nav-title">
              <strong>{{ r.name }}</strong>
              <span v-if="r.template" class="template-mini-tag">{{
                getTemplateLabel(r.template)
              }}</span>
            </div>
            <small
              >{{ countRoleMembers(r.id) }} 位成员 · {{ r.nativeName || '待生成原生角色' }}</small
            >
          </button>
        </div>
        <div v-else class="empty-sidebar">暂无角色，请先新建角色</div>
      </aside>

      <div class="role-workspace">
        <template v-if="selectedRole">
          <div class="role-header-row">
            <div>
              <div class="role-title-group">
                <h3>{{ selectedRole.name }}</h3>
                <el-button
                  text
                  size="small"
                  type="primary"
                  @click="openRenameRoleDialog(selectedRole)"
                >
                  改名
                </el-button>
              </div>
              <p class="role-meta-line">
                <span
                  >原生角色：<code>{{ selectedRole.nativeName || '-' }}</code></span
                >
                <span v-if="selectedRole.template"
                  >模板：{{ getTemplateLabel(selectedRole.template) }}</span
                >
                <span
                  >状态：{{
                    selectedRole.status === 'DRAFT'
                      ? '草稿（待预览激活）'
                      : selectedRole.status || 'ACTIVE'
                  }}</span
                >
                <span>版本：v{{ selectedRole.revision ?? 0 }}</span>
              </p>
            </div>
            <div class="role-header-actions">
              <el-button size="small" @click="jumpToRoleMembers(selectedRole.id)">
                管理成员 · {{ countRoleMembers(selectedRole.id) }}
              </el-button>
              <el-button
                size="small"
                type="danger"
                plain
                :disabled="countRoleMembers(selectedRole.id) > 0"
                :title="
                  countRoleMembers(selectedRole.id) > 0
                    ? '该角色仍有成员，请先移除成员后再删除'
                    : ''
                "
                @click="handleDeleteRole(selectedRole)"
              >
                删除角色
              </el-button>
            </div>
          </div>

          <div class="permission-toolbar">
            <div class="resource-type-tabs" role="group" aria-label="资源类型">
              <button
                v-for="rt in availableResourceTypes"
                :key="rt"
                type="button"
                :data-testid="`resource-type-${rt}`"
                :class="['rt-btn', { active: currentResourceType === rt }]"
                @click="switchResourceType(rt)"
              >
                {{ getResourceTypeLabel(rt) }}
              </button>
            </div>
            <el-button
              text
              size="small"
              type="primary"
              @click="capabilityNoteVisible = !capabilityNoteVisible"
            >
              {{ capabilityNoteVisible ? '收起权限说明' : '权限说明' }}
            </el-button>
          </div>

          <div v-if="capabilityNoteVisible" class="capability-notes-panel">
            <table class="compact-note-table">
              <thead>
                <tr>
                  <th>业务动作</th>
                  <th>适用资源</th>
                  <th>底层原生动作</th>
                  <th>能力说明</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="act in normalizedCapabilityActions" :key="act.key">
                  <td>{{ act.label || act.key }}</td>
                  <td>{{ (act.resourceTypes || []).join(' / ') || '-' }}</td>
                  <td>
                    <code>{{ (act.nativeActions || []).join(', ') || '-' }}</code>
                  </td>
                  <td>{{ act.note || act.reason || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="list-toolbar resource-filter-bar">
            <div class="filter-controls">
              <label class="inline-select-label">
                <span>{{ isPostgresEngine ? 'Schema' : '数据库' }}</span>
                <el-select
                  v-model="selectedDatabase"
                  filterable
                  placeholder="选择数据库/Schema"
                  aria-label="选择数据库"
                  class="db-select"
                  @change="handleDatabaseChange"
                >
                  <el-option
                    v-for="db in resourcesState.databases"
                    :key="db"
                    :label="db"
                    :value="db"
                  />
                </el-select>
              </label>
              <el-input
                v-model="resourceSearch"
                clearable
                placeholder="搜索资源名称"
                aria-label="搜索资源"
                class="resource-search-input"
              />
            </div>
            <div class="toolbar-actions">
              <el-button
                v-if="currentResourceRows.length"
                text
                size="small"
                type="primary"
                @click="batchSelectQuery"
              >
                批量勾选查询
              </el-button>
            </div>
          </div>

          <p v-if="currentScopeNote" class="scope-note">{{ currentScopeNote }}</p>

          <el-alert
            v-if="resourcesState.error"
            :title="resourcesState.error"
            type="error"
            :closable="false"
            show-icon
            class="top-alert"
          >
            <el-button text type="primary" @click="loadResources(selectedDatabase)"
              >重试盘点资源</el-button
            >
          </el-alert>

          <div v-loading="resourcesState.loading" class="matrix-table-wrap">
            <table class="permission-matrix-table">
              <thead>
                <tr>
                  <th>资源名称</th>
                  <th v-for="col in matrixActionColumns" :key="col.key">
                    <span :title="col.note || (col.nativeActions || []).join(', ')">
                      {{ col.shortLabel }}
                    </span>
                  </th>
                  <th>细分</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in currentResourceRows" :key="row.rowKey">
                  <td>
                    <span class="resource-name">{{ row.displayName }}</span>
                    <span class="resource-sub">{{ row.subLabel }}</span>
                  </td>
                  <td
                    v-for="col in matrixActionColumns"
                    :key="col.key"
                    :class="{ 'pending-cell': isCellChanged(row, col) }"
                  >
                    <template v-if="isActionSupported(col, row.resourceType)">
                      <input
                        type="checkbox"
                        :data-testid="`grant-check-${row.displayName}-${col.key}`"
                        :checked="isActionChecked(row, col)"
                        :indeterminate.prop="isActionIndeterminate(row, col)"
                        :disabled="isActionDisabledForRole(col)"
                        :aria-label="`${row.displayName} ${col.label}`"
                        :title="getCellTooltip(col)"
                        @change="toggleMatrixAction(row, col, $event.target.checked)"
                      />
                    </template>
                    <span
                      v-else
                      class="unavailable-cell"
                      :title="col.note || col.reason || '当前数据源或资源层级不支持此独立授权动作'"
                    >
                      {{ getUnsupportedBadge(col) }}
                    </span>
                  </td>
                  <td>
                    <el-button
                      v-if="hasRefinableActions(row)"
                      text
                      size="small"
                      type="primary"
                      @click="openRefineDialog(row)"
                    >
                      细分
                    </el-button>
                    <span v-else class="unavailable-cell">—</span>
                  </td>
                </tr>
                <tr v-if="!currentResourceRows.length">
                  <td :colspan="matrixActionColumns.length + 2" class="empty-cell">
                    {{
                      resourcesState.databases.length
                        ? '当前范围下未找到匹配资源'
                        : '尚无可用库表资源，请检查授权管理连接或数据源状态'
                    }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-if="hasDraftChanges" class="sticky-savebar" data-testid="role-savebar">
            <span>已修改角色权限草稿（待预览确认后生效）</span>
            <div class="savebar-actions">
              <el-button size="small" :disabled="previewing || applying" @click="resetRoleDraft">
                重置
              </el-button>
              <el-button
                type="primary"
                size="small"
                data-testid="preview-grants-btn"
                :loading="previewing"
                :disabled="previewing || applying"
                @click="handlePreviewGrants"
              >
                预览并保存
              </el-button>
            </div>
          </div>
        </template>
        <div v-else class="empty-workspace">
          请从左侧选择一个角色，或点击「新建角色」创建权限角色。
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { toRefs } from 'vue'

const props = defineProps({ model: { type: Object, required: true } })
// The feature composable owns these drafts and operations; this view only renders its contract.
const {
  getTemplateLabel,
  loading,
  overview,
  isPostgresEngine,
  countRoleMembers,
  getResourceTypeLabel,
  selectedRoleId,
  roleSearchKeyword,
  currentResourceType,
  selectedDatabase,
  resourceSearch,
  capabilityNoteVisible,
  resourcesState,
  filteredRoles,
  selectedRole,
  availableResourceTypes,
  normalizedCapabilityActions,
  matrixActionColumns,
  isActionSupported,
  getUnsupportedBadge,
  isActionDisabledForRole,
  getCellTooltip,
  currentScopeNote,
  currentResourceRows,
  isActionChecked,
  isActionIndeterminate,
  isCellChanged,
  toggleMatrixAction,
  hasRefinableActions,
  openRefineDialog,
  batchSelectQuery,
  resetRoleDraft,
  hasDraftChanges,
  loadResources,
  selectRole,
  switchResourceType,
  handleDatabaseChange,
  previewing,
  applying,
  openCreateRoleDialog,
  openRenameRoleDialog,
  handleDeleteRole,
  handlePreviewGrants,
  jumpToRoleMembers,
} = toRefs(props.model)
</script>

<style scoped lang="scss">
@use './permission-shared';

.role-layout {
  display: grid;
  grid-template-columns: 210px minmax(0, 1fr);
  min-height: 440px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}

.role-sidebar {
  border-right: 1px solid #e4e7ed;
  padding: 14px 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.role-sidebar-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: #667085;
}

.role-list-items {
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
}

.role-nav-item {
  border: 0;
  background: transparent;
  text-align: left;
  padding: 9px 10px;
  border-radius: 4px;
  cursor: pointer;
}

.role-nav-item:hover {
  background: #f5f8ff;
}

.role-nav-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.role-nav-title strong {
  font-size: 13px;
  font-weight: 500;
}

.template-mini-tag {
  font-size: 11px;
  color: #667085;
  background: #f3f5f8;
  padding: 1px 5px;
  border-radius: 3px;
}

.role-nav-item small {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #667085;
}

.empty-sidebar,
.empty-workspace {
  padding: 36px 16px;
  text-align: center;
  color: #667085;
  font-size: 13px;
}

.role-workspace {
  padding: 16px 18px;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.role-header-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e4e7ed;
}

.role-title-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.role-title-group h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.role-meta-line {
  margin: 4px 0 0;
  font-size: 12px;
  color: #667085;
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

.role-header-actions {
  display: flex;
  gap: 8px;
}

.permission-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 12px;
}

.resource-type-tabs {
  display: flex;
  gap: 4px;
}

.rt-btn {
  border: 1px solid transparent;
  background: transparent;
  color: #667085;
  padding: 4px 12px;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
}

.capability-notes-panel {
  margin: 10px 0;
  overflow-x: auto;
}

.compact-note-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.compact-note-table th,
.compact-note-table td {
  border: 1px solid #e4e7ed;
  padding: 6px 10px;
  text-align: left;
}

.compact-note-table th {
  background: #f7f8fa;
  color: #566275;
}

.resource-filter-bar {
  margin-top: 12px;
  margin-bottom: 10px;
}

.inline-select-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #667085;
}

.db-select {
  width: 160px;
}

.resource-search-input {
  width: 180px;
}

.scope-note {
  font-size: 12px;
  color: #667085;
  margin: 0 0 10px;
}

.matrix-table-wrap {
  overflow-x: auto;
}

.permission-matrix-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.permission-matrix-table th,
.permission-matrix-table td {
  border-bottom: 1px solid #edf0f4;
  padding: 10px 12px;
  text-align: center;
  vertical-align: middle;
}

.permission-matrix-table th {
  background: #f7f8fa;
  color: #566275;
  font-weight: 500;
  white-space: nowrap;
}

.permission-matrix-table th:first-child,
.permission-matrix-table td:first-child {
  text-align: left;
  min-width: 180px;
}

.resource-name {
  font-weight: 500;
  display: block;
}

.resource-sub {
  font-size: 12px;
  color: #667085;
  display: block;
  margin-top: 2px;
}

.pending-cell {
  background: #eef5ff;
}

.unavailable-cell {
  font-size: 12px;
  color: #667085;
  cursor: help;
}

.empty-cell {
  text-align: center !important;
  padding: 32px 12px !important;
  color: #667085;
}

.sticky-savebar {
  position: sticky;
  bottom: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
  padding: 12px 0 4px;
  margin-top: 14px;
}

.savebar-actions {
  display: flex;
  gap: 8px;
}
.role-nav-item.active {
  background: #edf4ff;
  color: #2563c9;
}
.rt-btn.active {
  background: #edf4ff;
  color: #2563c9;
  font-weight: 500;
}
</style>
