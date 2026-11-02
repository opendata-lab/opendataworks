<template>
  <el-drawer
    v-model="drawerVisible"
    :title="`${activeClusterName || '数据源'} · 权限配置`"
    size="1040px"
    destroy-on-close
    class="permission-center-drawer"
  >
    <template #header>
      <div class="drawer-header-bar">
        <div class="header-left">
          <span class="drawer-title">数据权限配置</span>
          <el-tag v-if="currentEngine" size="small" type="info">{{ currentEngine }}</el-tag>
          <el-select
            v-if="clusters && clusters.length > 1"
            :model-value="activeSourceId"
            size="small"
            class="source-switcher"
            aria-label="切换数据源"
            @change="handleSourceSwitch"
          >
            <el-option
              v-for="item in clusters"
              :key="item.id"
              :label="`${item.clusterName} (${item.sourceType || ''})`"
              :value="item.id"
            />
          </el-select>
          <span v-else-if="activeClusterName" class="current-source-label">{{ activeClusterName }}</span>
        </div>
        <div class="header-actions">
          <el-button
            size="small"
            data-testid="open-management-btn"
            @click="openManagementDialog"
          >
            授权管理连接
            <span class="mgmt-status-dot" :class="{ 'is-configured': overview.management?.configured }" />
          </el-button>
          <el-button size="small" :loading="loading" @click="reloadCurrentSource">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="permission-center-body">
      <el-alert
        v-if="loadError"
        :title="loadError"
        type="error"
        :closable="false"
        show-icon
        class="top-alert"
      >
        <el-button text type="primary" @click="reloadCurrentSource">重新加载</el-button>
      </el-alert>

      <div class="center-tabs" role="tablist" aria-label="权限管理视角">
        <button
          type="button"
          role="tab"
          data-testid="tab-members"
          :class="['center-tab-btn', { active: activeTab === 'members' }]"
          :aria-selected="activeTab === 'members'"
          @click="switchTab('members')"
        >
          成员 ({{ overview.members.length }})
        </button>
        <button
          type="button"
          role="tab"
          data-testid="tab-roles"
          :class="['center-tab-btn', { active: activeTab === 'roles' }]"
          :aria-selected="activeTab === 'roles'"
          @click="switchTab('roles')"
        >
          角色 ({{ overview.roles.length }})
        </button>
        <button
          type="button"
          role="tab"
          data-testid="tab-operations"
          :class="['center-tab-btn', { active: activeTab === 'operations' }]"
          :aria-selected="activeTab === 'operations'"
          @click="switchTab('operations')"
        >
          授权记录
        </button>
      </div>

      <!-- Tab 1: 成员 (Default) -->
      <section v-if="activeTab === 'members'" v-loading="loading" class="tab-panel members-panel">
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
              <el-option label="待配置" value="pending" />
              <el-option label="已停用" value="disabled" />
            </el-select>
          </div>
          <div class="toolbar-actions">
            <el-button data-testid="authorize-agent-btn" @click="openAuthorizeDialog('service')">
              授权服务账号
            </el-button>
            <el-button type="primary" data-testid="authorize-user-btn" @click="openAuthorizeDialog('existing')">
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
                <el-tag
                  v-for="rid in row.roleIds"
                  :key="rid"
                  size="small"
                  effect="plain"
                  class="role-tag-item"
                >
                  {{ getRoleName(rid) }}
                </el-tag>
              </div>
              <span v-else class="muted-text">未分配</span>
            </template>
          </el-table-column>
          <el-table-column label="数据库账号" min-width="160">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                data-testid="bind-account-link"
                @click="openAccountDialog(row)"
              >
                {{ row.account?.configured && row.account?.username ? row.account.username : '绑定账号' }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="getMemberStatus(row).tagType" size="small">
                {{ getMemberStatus(row).label }}
              </el-tag>
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
              <el-button
                type="primary"
                text
                size="small"
                @click="openAccountDialog(row)"
              >
                账号
              </el-button>
              <el-button
                type="primary"
                text
                size="small"
                data-testid="inspect-member-btn"
                @click="openInspectDialog(row)"
              >
                实际权限
              </el-button>
              <el-button
                type="danger"
                text
                size="small"
                @click="handleRemoveMember(row)"
              >
                移除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="table-footer">
          <span>共 {{ filteredMembers.length }} 位成员</span>
        </div>
      </section>

      <!-- Tab 2: 角色 -->
      <section v-else-if="activeTab === 'roles'" v-loading="loading" class="tab-panel roles-panel">
        <div class="role-layout">
          <aside class="role-sidebar">
            <div class="role-sidebar-head">
              <span>{{ overview.roles.length }} 个角色</span>
              <el-button type="primary" link size="small" data-testid="new-role-btn" @click="openCreateRoleDialog">
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
                  <span v-if="r.template" class="template-mini-tag">{{ getTemplateLabel(r.template) }}</span>
                </div>
                <small>{{ countRoleMembers(r.id) }} 位成员 · {{ r.nativeName || '待生成原生角色' }}</small>
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
                    <el-button text size="small" type="primary" @click="openRenameRoleDialog(selectedRole)">
                      改名
                    </el-button>
                  </div>
                  <p class="role-meta-line">
                    <span>原生角色：<code>{{ selectedRole.nativeName || '-' }}</code></span>
                    <span v-if="selectedRole.template">模板：{{ getTemplateLabel(selectedRole.template) }}</span>
                    <span>状态：{{ selectedRole.status === 'DRAFT' ? '草稿（待预览激活）' : selectedRole.status || 'ACTIVE' }}</span>
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
                    :title="countRoleMembers(selectedRole.id) > 0 ? '该角色仍有成员，请先移除成员后再删除' : ''"
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
                <el-button text size="small" type="primary" @click="capabilityNoteVisible = !capabilityNoteVisible">
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
                      <td><code>{{ (act.nativeActions || []).join(', ') || '-' }}</code></td>
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
                <el-button text type="primary" @click="loadResources(selectedDatabase)">重试盘点资源</el-button>
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
                        {{ resourcesState.databases.length ? '当前范围下未找到匹配资源' : '尚无可用库表资源，请检查授权管理连接或数据源状态' }}
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

      <!-- Tab 3: 授权记录 -->
      <section v-else-if="activeTab === 'operations'" class="tab-panel operations-panel">
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
    </div>

    <!-- 弹窗 1: 授权用户 / 新建用户 / 授权服务账号 -->
    <el-dialog
      v-model="authorizeDialogVisible"
      title="授权成员"
      width="480px"
      append-to-body
      @closed="clearAuthorizeDialog"
    >
      <div class="dialog-source-strip">
        <span>数据源：<strong>{{ activeClusterName }}</strong></span>
      </div>

      <el-radio-group v-model="authorizeForm.mode" class="mode-radio-group" @change="authorizeError = ''">
        <el-radio value="existing" label="existing">已有用户</el-radio>
        <el-radio value="new" label="new">新建用户</el-radio>
        <el-radio value="service" label="service">服务账号</el-radio>
      </el-radio-group>

      <el-form label-width="100px" @submit.prevent="submitAuthorize">
        <template v-if="authorizeForm.mode === 'existing'">
          <el-form-item label="平台用户" required>
            <el-select
              v-model="authorizeForm.userId"
              filterable
              placeholder="请选择平台用户"
              style="width: 100%"
              aria-label="选择已有用户"
            >
              <el-option
                v-for="u in platformUsers.filter(u => u.accountType !== 'SERVICE')"
                :key="u.id"
                :label="`${u.nickname || u.username}（${u.username}）${u.enabled === false ? ' [已停用]' : ''}`"
                :value="String(u.id)"
              />
            </el-select>
          </el-form-item>
        </template>

        <template v-else-if="authorizeForm.mode === 'new'">
          <el-form-item label="用户名" required>
            <el-input
              v-model="authorizeForm.username"
              autocomplete="off"
              placeholder="用于登录平台"
              aria-label="新建平台用户名"
            />
          </el-form-item>
          <el-form-item label="姓名" required>
            <el-input
              v-model="authorizeForm.nickname"
              placeholder="输入姓名"
              aria-label="新建用户姓名"
            />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input
              v-model="authorizeForm.email"
              placeholder="选填"
              aria-label="新建用户邮箱"
            />
          </el-form-item>
          <el-form-item label="初始登录密码" required>
            <el-input
              v-model="authorizeForm.password"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="至少 8 位，最多 72 字节初始登录密码"
              aria-label="新建用户初始密码"
            />
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="服务账号" required>
            <el-select v-model="authorizeForm.serviceAccountId" filterable placeholder="选择平台服务账号" aria-label="选择服务账号" style="width:100%">
              <el-option v-for="u in platformUsers.filter(u => u.accountType === 'SERVICE')" :key="u.id" :value="String(u.id)" :label="u.nickname || u.username" :disabled="u.enabled === false" />
            </el-select>
            <p v-if="!platformUsers.some(u => u.accountType === 'SERVICE')" class="sub-cell-text">暂无服务账号，请先在管理中心的账号管理中创建。</p>
          </el-form-item>
        </template>

        <el-form-item label="分配角色" required>
          <div class="role-checkbox-list" role="group" aria-label="授权角色列表">
            <label
              v-for="r in overview.roles"
              :key="r.id"
              :class="['role-check-item', { disabled: authorizeForm.mode === 'service' && isWriteRole(r) }]"
            >
              <input
                type="checkbox"
                :data-testid="`auth-role-${r.id}`"
                :checked="authorizeForm.roleIds.includes(r.id)"
                :disabled="authorizeForm.mode === 'service' && isWriteRole(r)"
                @change="toggleAuthorizeRole(r.id, $event.target.checked)"
              />
              <div class="role-check-meta">
                <strong>{{ r.name }}</strong>
                <small>
                  {{ authorizeForm.mode === 'service' && isWriteRole(r) ? '服务账号仅可分配只读角色' : summarizeRoleGrants(r) }}
                </small>
              </div>
            </label>
            <div v-if="!overview.roles.length" class="muted-text">
              当前数据源尚无角色，请先切到「角色」页签新建角色
            </div>
          </div>
        </el-form-item>
      </el-form>

      <p v-if="authorizeError" role="alert" class="form-error">{{ authorizeError }}</p>

      <template #footer>
        <el-button :disabled="authorizing" @click="authorizeDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          data-testid="confirm-authorize-btn"
          :loading="authorizing"
          :disabled="authorizing"
          @click="submitAuthorize"
        >
          {{ authorizeForm.mode === 'new' ? '创建并授权' : '确认授权' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 弹窗 2: 独立紧凑弹窗 · 分配角色 -->
    <el-dialog
      v-model="roleAssignDialogVisible"
      title="分配角色"
      width="460px"
      append-to-body
    >
      <div v-if="roleAssignTarget" class="dialog-source-strip">
        <strong>{{ getMemberDisplayName(roleAssignTarget) }}</strong>
        <span>{{ activeClusterName }}</span>
      </div>
      <div class="role-checkbox-list" role="group" aria-label="可分配角色">
        <label
          v-for="r in overview.roles"
          :key="r.id"
          :class="['role-check-item', { disabled: roleAssignTarget?.subjectType === 'SERVICE' && isWriteRole(r) }]"
        >
          <input
            type="checkbox"
            :data-testid="`member-role-check-${r.id}`"
            :checked="roleAssignForm.roleIds.includes(r.id)"
            :disabled="roleAssignTarget?.subjectType === 'SERVICE' && isWriteRole(r)"
            @change="toggleMemberRole(r.id, $event.target.checked)"
          />
          <div class="role-check-meta">
            <strong>{{ r.name }}</strong>
            <small>
              {{ roleAssignTarget?.subjectType === 'SERVICE' && isWriteRole(r) ? '服务账号仅可分配只读角色' : summarizeRoleGrants(r) }}
            </small>
          </div>
        </label>
      </div>
      <div class="picker-count">已选 {{ roleAssignForm.roleIds.length }} 个角色</div>
      <p v-if="roleAssignError" role="alert" class="form-error">{{ roleAssignError }}</p>
      <template #footer>
        <el-button :disabled="roleAssignSaving" @click="roleAssignDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          data-testid="save-member-roles-btn"
          :loading="roleAssignSaving"
          :disabled="roleAssignSaving"
          @click="submitRoleAssign"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 弹窗 3: 独立紧凑弹窗 · 绑定数据库账号 -->
    <el-dialog
      v-model="accountDialogVisible"
      title="绑定数据库账号"
      width="440px"
      append-to-body
      @closed="clearAccountDialog"
    >
      <div v-if="accountTarget" class="dialog-source-strip">
        <strong>{{ getMemberDisplayName(accountTarget) }}</strong>
        <span>{{ activeClusterName }}</span>
      </div>
      <el-form label-width="96px" @submit.prevent="submitAccountBind">
        <el-form-item label="数据库账号" required>
          <el-input
            v-model="accountForm.username"
            autocomplete="off"
            placeholder="输入数据库登录账号"
            aria-label="成员数据库账号"
          />
        </el-form-item>
        <el-form-item label="数据库密码" :required="!isKeepingSameMemberAccount">
          <el-input
            v-model="accountForm.password"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="isKeepingSameMemberAccount ? '留空保留原密码' : '输入数据库密码'"
            aria-label="成员数据库密码"
          />
        </el-form-item>
      </el-form>
      <p class="field-hint">
        每个成员在当前数据源绑定唯一登录账号，连接时自动激活其所属平台角色集合。
      </p>
      <p v-if="accountError" role="alert" class="form-error">{{ accountError }}</p>
      <template #footer>
        <el-button :disabled="accountSaving" @click="accountDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          data-testid="save-member-account-btn"
          :loading="accountSaving"
          :disabled="accountSaving"
          @click="submitAccountBind"
        >
          测试并保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 弹窗 4: 账号实际权限 Inspect -->
    <el-dialog
      v-model="inspectDialogVisible"
      :title="`${inspectTarget ? getMemberDisplayName(inspectTarget) : ''} · 账号实际权限`"
      width="680px"
      append-to-body
    >
      <div v-loading="inspectState.loading">
        <div class="dialog-source-strip">
          <span>数据源：<strong>{{ activeClusterName }}</strong></span>
          <span>绑定账号：<code>{{ inspectState.data?.username || inspectTarget?.account?.username || '未绑定' }}</code></span>
          <span v-if="inspectState.data?.nativeIdentity">
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

        <p v-if="inspectState.data?.notice" class="state-note warning-note" data-testid="inspect-notice">
          {{ inspectState.data.notice }}
        </p>

        <el-table
          v-if="!inspectState.error"
          :data="normalizedInspectGrants"
          border
          empty-text="未回读到原生权限或尚未绑定账号"
          style="width: 100%"
        >
          <el-table-column prop="scope" label="资源范围 / 对象" min-width="220" />
          <el-table-column prop="actionsText" label="原生权限" min-width="200" />
          <el-table-column prop="sourceText" label="来源" min-width="140" />
        </el-table>
      </div>
      <template #footer>
        <el-button @click="inspectDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 弹窗 5: 管理员高级管理连接配置 -->
    <el-dialog
      v-model="managementDialogVisible"
      title="授权管理连接配置"
      width="460px"
      append-to-body
      @closed="clearManagementDialog"
    >
      <p class="field-hint">
        用于当前数据源执行角色创建、GRANT / REVOKE 与原生权限回读。不配置时默认复用数据源连接账号；绝不作为业务查询回退。
      </p>
      <el-form label-width="100px" @submit.prevent="submitManagementAccount">
        <el-form-item label="管理账号" required>
          <el-input
            v-model="managementForm.username"
            autocomplete="off"
            placeholder="输入具备 GRANT 权限的数据库账号"
            aria-label="授权管理账号"
          />
        </el-form-item>
        <el-form-item label="管理密码" :required="!isKeepingSameManagementAccount">
          <el-input
            v-model="managementForm.password"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="isKeepingSameManagementAccount ? '留空保留原密码' : '输入管理账号密码'"
            aria-label="授权管理密码"
          />
        </el-form-item>
      </el-form>
      <p v-if="managementError" role="alert" class="form-error">{{ managementError }}</p>
      <template #footer>
        <el-button :disabled="managementSaving" @click="managementDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          data-testid="save-management-btn"
          :loading="managementSaving"
          :disabled="managementSaving"
          @click="submitManagementAccount"
        >
          测试并保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 弹窗 6: 新建角色 / 角色改名 -->
    <el-dialog
      v-model="createRoleDialogVisible"
      title="新建角色"
      width="460px"
      append-to-body
    >
      <el-form label-width="96px" @submit.prevent="submitCreateRole">
        <el-form-item label="角色名称" required>
          <el-input
            v-model="createRoleForm.name"
            placeholder="例如：销售分析员"
            aria-label="角色名称"
          />
        </el-form-item>
        <el-form-item label="权限模板" required>
          <el-select
            v-model="createRoleForm.template"
            style="width: 100%"
            aria-label="权限模板"
          >
            <el-option
              v-for="tpl in ROLE_TEMPLATES"
              :key="tpl.value"
              :label="tpl.label"
              :value="tpl.value"
            />
          </el-select>
          <div class="field-hint">{{ currentTemplateDescription }}</div>
        </el-form-item>
      </el-form>
      <p class="field-hint">
        角色映射数据库无登录 ROLE，不保存数据库账号密码；创建后请在资源矩阵中勾选范围并预览生效。
      </p>
      <p v-if="createRoleError" role="alert" class="form-error">{{ createRoleError }}</p>
      <template #footer>
        <el-button :disabled="creatingRole" @click="createRoleDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          data-testid="confirm-create-role-btn"
          :loading="creatingRole"
          :disabled="creatingRole"
          @click="submitCreateRole"
        >
          创建
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="renameRoleDialogVisible"
      title="修改角色名称"
      width="420px"
      append-to-body
    >
      <el-form label-width="90px" @submit.prevent="submitRenameRole">
        <el-form-item label="角色名称" required>
          <el-input v-model="renameRoleForm.name" placeholder="输入新的角色显示名" />
        </el-form-item>
      </el-form>
      <p v-if="renameRoleError" role="alert" class="form-error">{{ renameRoleError }}</p>
      <template #footer>
        <el-button :disabled="renamingRole" @click="renameRoleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="renamingRole" :disabled="renamingRole" @click="submitRenameRole">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 弹窗 7: 原生动作细分 -->
    <el-dialog
      v-model="refineDialogVisible"
      :title="`权限细分 · ${refineTarget?.displayName || ''}`"
      width="480px"
      append-to-body
    >
      <template v-if="refineTarget">
        <div class="dialog-source-strip">
          <code>{{ refineTarget.subLabel }}</code>
        </div>
        <div v-for="col in getRefinableColumns(refineTarget)" :key="col.key" class="refine-group">
          <div class="refine-group-head">
            <strong>{{ col.label || col.key }}</strong>
            <span v-if="col.note" class="muted-text">{{ col.note }}</span>
          </div>
          <div class="native-checkbox-grid">
            <label
              v-for="na in col.nativeActions"
              :key="na"
              class="native-option-label"
            >
              <input
                type="checkbox"
                :checked="isNativeSubActionChecked(refineTarget, col, na)"
                :disabled="isActionDisabledForRole(col)"
                @change="toggleNativeSubAction(refineTarget, col, na, $event.target.checked)"
              />
              <code>{{ na }}</code>
            </label>
          </div>
        </div>
      </template>
      <template #footer>
        <el-button type="primary" @click="refineDialogVisible = false">完成</el-button>
      </template>
    </el-dialog>

    <!-- 弹窗 8: 权限变更预览确认 (Preview -> Apply) -->
    <el-dialog
      v-model="previewDialogVisible"
      title="确认权限变更"
      width="680px"
      append-to-body
    >
      <template v-if="previewResult">
        <div class="preview-summary-bar">
          <div>
            <strong>{{ selectedRole?.name }}</strong>
            <span class="muted-text">
              影响 {{ previewResult.memberCount ?? countRoleMembers(selectedRole?.id) }} 位成员
            </span>
          </div>
          <span v-if="previewResult.expiresAt" class="muted-text">
            预览有效期至：{{ formatDateTime(previewResult.expiresAt) }}
          </span>
        </div>

        <div v-if="previewResult.warnings && previewResult.warnings.length" class="warnings-block" data-testid="preview-warnings">
          <p
            v-for="(w, idx) in previewResult.warnings"
            :key="idx"
            class="state-note warning-note"
          >
            {{ w }}
          </p>
        </div>

        <div class="sql-preview-section">
          <span class="section-mini-label">待执行原生 SQL ({{ (previewResult.sql || []).length }} 条)</span>
          <pre class="native-sql" data-testid="preview-sql">{{ (previewResult.sql || []).join('\n') || '-- 无 SQL 变更' }}</pre>
        </div>
      </template>
      <template #footer>
        <el-button :disabled="applying" @click="previewDialogVisible = false">返回修改</el-button>
        <el-button
          type="primary"
          data-testid="apply-grants-btn"
          :loading="applying"
          :disabled="applying"
          @click="handleApplyGrants"
        >
          确认执行
        </el-button>
      </template>
    </el-dialog>

    <!-- 弹窗 9: 授权操作执行结果 / 步骤详情 -->
    <el-dialog
      v-model="operationDetailVisible"
      :title="getOperationDialogTitle(operationDetail)"
      width="640px"
      append-to-body
    >
      <template v-if="operationDetail">
        <div class="op-result-banner" :class="`is-${String(operationDetail.state || '').toLowerCase()}`" data-testid="operation-result-banner">
          <div class="op-result-main">
            <strong>状态：{{ getOperationStateMeta(operationDetail.state).label }} ({{ operationDetail.state }})</strong>
            <div class="op-id-line">
              操作 ID：<code data-testid="operation-result-id">{{ operationDetail.operationId || operationDetail.id || '-' }}</code>
            </div>
            <p v-if="operationDetail.message" class="op-message-line">{{ operationDetail.message }}</p>
          </div>
        </div>

        <p v-if="operationDetail.state !== 'SUCCESS'" class="state-note warning-note">
          本次操作未完全成功（状态：{{ operationDetail.state }}），请根据操作 ID 与下方步骤核对实际数据库状态，系统不会自动重放或伪造回滚。
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
        <el-button
          v-if="activeTab !== 'operations'"
          type="primary"
          @click="operationDetailVisible = false; switchTab('operations')"
        >
          查看授权记录
        </el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { permissionsApi } from '@/api/permissions'
import { usersApi } from '@/api/users'
import { validateNewUserDraft } from '@/utils/userValidation'

const props = defineProps({
  modelValue: { type: Boolean, default: true },
  cluster: { type: Object, default: null },
  clusters: { type: Array, default: () => [] }
})

const emit = defineEmits(['update:modelValue', 'source-change'])

const drawerVisible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const ROLE_TEMPLATES = [
  { value: 'READONLY', label: '只读', actions: ['QUERY', 'SHOW_VIEW'], desc: '查询数据；按资源适用范围查看视图定义' },
  { value: 'READWRITE', label: '读写', actions: ['QUERY', 'WRITE', 'SHOW_VIEW'], desc: '查询及新增、更新、删除数据，不包含 DDL' },
  { value: 'ADMIN', label: '数据管理员', actions: ['QUERY', 'WRITE', 'CREATE', 'ALTER', 'DROP', 'SHOW_VIEW'], desc: '数据维护及底层可授予的对象管理，不包含 Grant 或超级用户' },
  { value: 'CUSTOM', label: '自定义', actions: [], desc: '空白角色，创建后自由选择资源与动作' }
]

const DEFAULT_ACTIONS = [
  { key: 'QUERY', label: '查询数据', shortLabel: '查询', resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'], nativeActions: ['SELECT'] },
  { key: 'WRITE', label: '写入数据', shortLabel: '写入', resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'], nativeActions: ['INSERT', 'UPDATE', 'DELETE'] },
  { key: 'CREATE', label: '创建对象', shortLabel: '创建对象', resourceTypes: ['SCHEMA'], nativeActions: ['CREATE'] },
  { key: 'ALTER', label: '修改结构', shortLabel: '修改结构', resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'], nativeActions: ['ALTER'] },
  { key: 'DROP', label: '删除对象', shortLabel: '删除对象', resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'], nativeActions: ['DROP'] },
  { key: 'SHOW_VIEW', label: '查看视图定义', shortLabel: '查看定义', resourceTypes: ['SCHEMA', 'VIEW'], nativeActions: ['SHOW VIEW'] }
]

const READONLY_ACTION_KEYS = new Set([
  'QUERY',
  'SHOW_VIEW',
  'DEFINITION',
  'ACCESS',
  'SELECT',
  'Select_priv',
  'USAGE',
  'CONNECT',
  'SHOW VIEW',
  'Show_view_priv'
])

const activeSourceId = ref(props.cluster?.id ?? null)
const activeTab = ref('members')
const loading = ref(false)
const loadError = ref('')

const overview = reactive({
  sourceId: null,
  engine: '',
  roles: [],
  members: [],
  management: { username: '', configured: false },
  capabilities: { resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'], actions: [] }
})

const platformUsers = ref([])

// 成员筛选
const memberFilters = reactive({
  keyword: '',
  roleId: 'all',
  status: 'all'
})

// 角色管理状态
const selectedRoleId = ref(null)
const roleSearchKeyword = ref('')
const currentResourceType = ref('SCHEMA')
const selectedDatabase = ref('')
const resourceSearch = ref('')
const capabilityNoteVisible = ref(false)

// 资源盘点状态
const resourcesState = reactive({
  loading: false,
  error: '',
  databases: [],
  objects: []
})

// 角色权限草稿: key = `${resourceType}|${database}|${tableName || ''}` -> string[] of actions
const roleDraftMap = ref({})

// 授权记录状态
const operationsState = reactive({
  loading: false,
  error: '',
  list: []
})

// 弹窗状态：授权成员
const authorizeDialogVisible = ref(false)
const authorizing = ref(false)
const authorizeError = ref('')
const authorizeForm = reactive({
  mode: 'existing',
  userId: '',
  username: '',
  nickname: '',
  email: '',
  password: '',
  serviceAccountId: '',
  roleIds: []
})

// 弹窗状态：分配角色
const roleAssignDialogVisible = ref(false)
const roleAssignSaving = ref(false)
const roleAssignError = ref('')
const roleAssignTarget = ref(null)
const roleAssignForm = reactive({
  roleIds: []
})

// 弹窗状态：绑定数据库账号
const accountDialogVisible = ref(false)
const accountSaving = ref(false)
const accountError = ref('')
const accountTarget = ref(null)
const accountForm = reactive({
  username: '',
  password: ''
})

// 弹窗状态：Inspect
const inspectDialogVisible = ref(false)
const inspectTarget = ref(null)
const inspectState = reactive({
  loading: false,
  error: '',
  data: null
})

// 弹窗状态：管理连接
const managementDialogVisible = ref(false)
const managementSaving = ref(false)
const managementError = ref('')
const managementForm = reactive({
  username: '',
  password: ''
})

// 弹窗状态：新建/改名角色
const createRoleDialogVisible = ref(false)
const creatingRole = ref(false)
const createRoleError = ref('')
const createRoleForm = reactive({
  name: '',
  template: 'READONLY'
})

const renameRoleDialogVisible = ref(false)
const renamingRole = ref(false)
const renameRoleError = ref('')
const renameRoleTarget = ref(null)
const renameRoleForm = reactive({
  name: ''
})

// 弹窗状态：动作细分
const refineDialogVisible = ref(false)
const refineTarget = ref(null)

// 弹窗状态：预览与执行结果
const previewing = ref(false)
const applying = ref(false)
const previewDialogVisible = ref(false)
const previewResult = ref(null)

const operationDetailVisible = ref(false)
const operationDetail = ref(null)

// 请求隔离序号
let sourceLoadSeq = 0
let resourceLoadSeq = 0
let opsLoadSeq = 0

const activeClusterObj = computed(() => {
  if (props.clusters?.length && activeSourceId.value != null) {
    const found = props.clusters.find((c) => String(c.id) === String(activeSourceId.value))
    if (found) return found
  }
  return props.cluster
})

const activeClusterName = computed(() => activeClusterObj.value?.clusterName || '')
const currentEngine = computed(() => overview.engine || activeClusterObj.value?.sourceType || '')
const isPostgresEngine = computed(() => String(currentEngine.value).toUpperCase().includes('POSTGRES'))

const formatDateTime = (val) => {
  if (!val) return '-'
  return dayjs(val).format('YYYY-MM-DD HH:mm:ss')
}

const getTemplateLabel = (tpl) => {
  const item = ROLE_TEMPLATES.find((t) => t.value === String(tpl).toUpperCase())
  return item ? item.label : tpl
}

const currentTemplateDescription = computed(() => {
  const item = ROLE_TEMPLATES.find((t) => t.value === createRoleForm.template)
  return item?.desc || ''
})

const getRoleName = (roleId) => {
  const found = overview.roles.find((r) => String(r.id) === String(roleId))
  return found ? found.name : String(roleId)
}

const countRoleMembers = (roleId) => {
  if (!roleId) return 0
  return overview.members.filter((m) =>
    (m.roleIds || []).some((id) => String(id) === String(roleId))
  ).length
}

const getMemberDisplayName = (member) => {
  if (!member) return ''
  if (member.name) return member.name
  if (member.subjectType === 'USER') {
    const u = platformUsers.value.find((item) => String(item.id) === String(member.subjectId))
    if (u) return u.nickname || u.username
  }
  return String(member.subjectId || '')
}

const getMemberStatus = (member) => {
  if (!member) return { key: 'pending', label: '待配置', tagType: 'warning' }
  const upperStatus = String(member.status || '').toUpperCase()
  if (member.enabled === false || upperStatus === 'DISABLED') {
    return { key: 'disabled', label: '已停用', tagType: 'danger' }
  }
  if (upperStatus === 'ERROR') {
    return { key: 'pending', label: '状态异常', tagType: 'danger' }
  }
  const hasRoles = Array.isArray(member.roleIds) && member.roleIds.length > 0
  const hasAccount = Boolean(member.account?.configured && member.account?.username)
  if (hasRoles && hasAccount && !['PENDING', 'NEEDS_ACCOUNT'].includes(upperStatus)) {
    return { key: 'ready', label: '正常', tagType: 'success' }
  }
  if (!hasRoles) {
    return { key: 'pending', label: '待分配角色', tagType: 'warning' }
  }
  return { key: 'pending', label: '待绑定账号', tagType: 'warning' }
}

const filteredMembers = computed(() => {
  const kw = String(memberFilters.keyword ?? '').trim().toLowerCase()
  return overview.members.filter((m) => {
    if (
      memberFilters.roleId !== 'all' &&
      !(m.roleIds || []).some((id) => String(id) === String(memberFilters.roleId))
    ) {
      return false
    }
    const st = getMemberStatus(m)
    if (memberFilters.status !== 'all' && st.key !== memberFilters.status) {
      return false
    }
    if (!kw) return true
    const haystack = [
      getMemberDisplayName(m),
      m.subjectId,
      m.account?.username,
      ...(m.roleIds || []).map(getRoleName)
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return haystack.includes(kw)
  })
})

const filteredRoles = computed(() => {
  const kw = String(roleSearchKeyword.value ?? '').trim().toLowerCase()
  if (!kw) return overview.roles
  return overview.roles.filter((r) =>
    [r.name, r.nativeName].filter(Boolean).join(' ').toLowerCase().includes(kw)
  )
})

const selectedRole = computed(() =>
  overview.roles.find((r) => String(r.id) === String(selectedRoleId.value)) || null
)

const isRoleAssignedToService = computed(() => {
  if (!selectedRole.value) return false
  return overview.members.some(
    (m) =>
      m.subjectType === 'SERVICE' &&
      (m.roleIds || []).some((id) => String(id) === String(selectedRole.value.id))
  )
})

const isWriteRole = (role) => {
  if (!role) return false
  const tpl = String(role.template || '').toUpperCase()
  if (tpl === 'READWRITE' || tpl === 'ADMIN') return true
  return (role.grants || []).some((g) =>
    (g.actions || []).some((act) => !READONLY_ACTION_KEYS.has(act))
  )
}

const summarizeRoleGrants = (role) => {
  if (!role) return ''
  const grants = role.grants || []
  if (!grants.length) {
    return role.template ? `${getTemplateLabel(role.template)}模板 · 待配置资源` : '尚未配置资源权限'
  }
  const byDb = new Map()
  for (const g of grants) {
    const db = g.database || '*'
    if (!byDb.has(db)) byDb.set(db, new Set())
    for (const a of g.actions || []) byDb.get(db).add(a)
  }
  return [...byDb.entries()]
    .map(([db, acts]) => `${db} (${[...acts].map(actionLabel).join('、')})`)
    .join('；')
}

const actionLabel = (key) =>
  (overview.capabilities?.actions || DEFAULT_ACTIONS).find((action) => action.key === key)?.label || key

const availableResourceTypes = computed(() => {
  const rts = overview.capabilities?.resourceTypes
  return Array.isArray(rts) && rts.length ? rts : ['SCHEMA', 'TABLE', 'VIEW']
})

const getResourceTypeLabel = (rt) => {
  if (rt === 'SCHEMA') return overview.capabilities?.schemaLabel || (isPostgresEngine.value ? 'Schema' : '数据库')
  if (rt === 'TABLE') return '表'
  if (rt === 'VIEW') return '视图'
  return rt
}

const SUB_DML_KEYS = new Set(['INSERT', 'UPDATE', 'DELETE'])

const normalizedCapabilityActions = computed(() => {
  const raw = overview.capabilities?.actions
  const list = Array.isArray(raw) && raw.length ? raw : DEFAULT_ACTIONS
  return list.map((a) => ({
    ...a,
    shortLabel:
      a.label === '查询数据'
        ? '查询'
        : a.label === '写入数据'
          ? '写入'
          : a.label === '查看视图定义'
            ? '查看定义'
            : a.label || a.key
  }))
})

const matrixActionColumns = computed(() => {
  const hasCompositeWrite = normalizedCapabilityActions.value.some((a) => a.key === 'WRITE')
  return normalizedCapabilityActions.value.filter(
    (col) =>
      !(hasCompositeWrite && SUB_DML_KEYS.has(col.key)) &&
      (!col.resourceTypes ||
        col.resourceTypes.includes(currentResourceType.value) ||
        Boolean(col.note || col.reason))
  )
})

const isActionSupported = (col, resourceType = currentResourceType.value) => {
  if (col.supported === false) return false
  if (Array.isArray(col.resourceTypes) && col.resourceTypes.length > 0) {
    return col.resourceTypes.includes(resourceType)
  }
  return true
}

const getUnsupportedBadge = (col) => {
  const note = String(col.note || col.reason || '')
  if (note.includes('所有权') || col.mode === 'OWNER') return '所有者权限'
  if (note.includes('可见') || col.mode === 'VISIBLE') return '随资源可见'
  return '—'
}

const isSelectedRoleReadonlyLocked = computed(
  () =>
    isRoleAssignedToService.value ||
    String(selectedRole.value?.template || '').toUpperCase() === 'READONLY'
)

const isActionDisabledForRole = (col) => {
  if (!isSelectedRoleReadonlyLocked.value) return false
  return !READONLY_ACTION_KEYS.has(col.key)
}

const getCellTooltip = (col) => {
  if (isActionDisabledForRole(col)) {
    return isRoleAssignedToService.value
      ? '该角色已分配给服务账号成员，仅允许查询/只读权限'
      : '只读模板角色仅允许查询与查看视图定义'
  }
  const parts = []
  if (col.nativeActions?.length) parts.push(`原生权限: ${col.nativeActions.join(', ')}`)
  if (col.note) parts.push(col.note)
  return parts.join(' · ')
}

const currentScopeNote = computed(() => {
  const notes = []
  if (overview.capabilities?.note) {
    notes.push(overview.capabilities.note)
  }
  for (const a of matrixActionColumns.value) {
    if (
      (!a.resourceTypes || a.resourceTypes.includes(currentResourceType.value)) &&
      a.note
    ) {
      notes.push(`${a.label || a.key}：${a.note}`)
    }
  }
  if (notes.length) return [...new Set(notes)].join('；')
  if (currentResourceType.value === 'SCHEMA') {
    return isPostgresEngine.value
      ? 'PostgreSQL Schema 权限与子表读写权限独立控制，不会自动授予未来新建对象。'
      : '数据库级授权覆盖该库下当前与后续新增对象。'
  }
  return ''
})

const makeGrantKey = (resourceType, database, tableName = '') =>
  `${resourceType}|${database || ''}|${resourceType === 'SCHEMA' ? '' : tableName || ''}`

const currentResourceRows = computed(() => {
  const rt = currentResourceType.value
  const kw = String(resourceSearch.value ?? '').trim().toLowerCase()
  const db = selectedDatabase.value
  let rows = []

  if (rt === 'SCHEMA') {
    const schemaObjs = (resourcesState.objects || []).filter((o) => o.resourceType === 'SCHEMA')
    const dbs = resourcesState.databases?.length
      ? resourcesState.databases
      : schemaObjs.map((o) => o.database).filter(Boolean)
    const filteredDbs = db ? dbs.filter((d) => d === db) : dbs
    rows = filteredDbs.map((d) => ({
      rowKey: makeGrantKey('SCHEMA', d, ''),
      resourceType: 'SCHEMA',
      database: d,
      tableName: '',
      displayName: d,
      subLabel: isPostgresEngine.value ? `SCHEMA "${d}"` : `${d}.*`
    }))
  } else {
    const objs = (resourcesState.objects || []).filter(
      (o) => o.resourceType === rt && (!db || o.database === db)
    )
    rows = objs.map((o) => ({
      rowKey: makeGrantKey(rt, o.database, o.tableName),
      resourceType: rt,
      database: o.database,
      tableName: o.tableName,
      displayName: o.tableName,
      subLabel: o.comment ? `${o.database}.${o.tableName} · ${o.comment}` : `${o.database}.${o.tableName}`
    }))
  }

  if (!kw) return rows
  return rows.filter((r) =>
    `${r.displayName} ${r.subLabel}`.toLowerCase().includes(kw)
  )
})

const buildGrantsMapFromRole = (role) => {
  const map = {}
  for (const g of role?.grants || []) {
    const key = makeGrantKey(g.resourceType, g.database, g.tableName)
    map[key] = [...new Set(g.actions || [])]
  }
  return map
}

function syncDraftFromSelectedRole() {
  roleDraftMap.value = buildGrantsMapFromRole(selectedRole.value)
}

const getRowDraftActions = (row) => roleDraftMap.value[row.rowKey] || []

const getRowBaseActions = (row) => {
  const baseMap = buildGrantsMapFromRole(selectedRole.value)
  return baseMap[row.rowKey] || []
}

const isActionChecked = (row, col) => {
  const acts = getRowDraftActions(row)
  if (acts.includes(col.key)) return true
  if (Array.isArray(col.nativeActions) && col.nativeActions.length > 0) {
    return col.nativeActions.every((na) => acts.includes(na))
  }
  return false
}

const isActionIndeterminate = (row, col) => {
  const acts = getRowDraftActions(row)
  if (acts.includes(col.key)) return false
  if (Array.isArray(col.nativeActions) && col.nativeActions.length > 1) {
    const count = col.nativeActions.filter((na) => acts.includes(na)).length
    return count > 0 && count < col.nativeActions.length
  }
  return false
}

const isCellChanged = (row, col) => {
  const draftActs = getRowDraftActions(row)
  const baseActs = getRowBaseActions(row)
  const keysToCompare = [col.key, ...(col.nativeActions || [])]
  return keysToCompare.some((k) => draftActs.includes(k) !== baseActs.includes(k))
}

function toggleMatrixAction(row, col, checked) {
  if (!isActionSupported(col, row.resourceType) || isActionDisabledForRole(col)) return
  const current = new Set(getRowDraftActions(row))
  const subActions = col.nativeActions || []
  if (checked) {
    subActions.forEach((na) => current.delete(na))
    current.add(col.key)
  } else {
    current.delete(col.key)
    subActions.forEach((na) => current.delete(na))
  }
  roleDraftMap.value = {
    ...roleDraftMap.value,
    [row.rowKey]: [...current]
  }
}

const getRefinableColumns = (row) =>
  matrixActionColumns.value.filter(
    (col) => isActionSupported(col, row.resourceType) && Array.isArray(col.nativeActions) && col.nativeActions.length > 1
  )

const hasRefinableActions = (row) => getRefinableColumns(row).length > 0

const isNativeSubActionChecked = (row, col, nativeAct) => {
  const acts = getRowDraftActions(row)
  return acts.includes(col.key) || acts.includes(nativeAct)
}

function toggleNativeSubAction(row, col, nativeAct, checked) {
  if (isActionDisabledForRole(col)) return
  const current = new Set(getRowDraftActions(row))
  const subActions = col.nativeActions || []
  if (current.has(col.key)) {
    current.delete(col.key)
    subActions.forEach((na) => current.add(na))
  }
  if (checked) {
    current.add(nativeAct)
  } else {
    current.delete(nativeAct)
  }
  if (subActions.length > 0 && subActions.every((na) => current.has(na))) {
    subActions.forEach((na) => current.delete(na))
    current.add(col.key)
  }
  roleDraftMap.value = {
    ...roleDraftMap.value,
    [row.rowKey]: [...current]
  }
}

function openRefineDialog(row) {
  refineTarget.value = row
  refineDialogVisible.value = true
}

function batchSelectQuery() {
  const queryCol = matrixActionColumns.value.find((c) => c.key === 'QUERY')
  if (!queryCol) return
  for (const row of currentResourceRows.value) {
    if (isActionSupported(queryCol, row.resourceType)) {
      toggleMatrixAction(row, queryCol, true)
    }
  }
}

function resetRoleDraft() {
  syncDraftFromSelectedRole()
}

const serializeGrantMap = (map) => {
  const entries = Object.entries(map || {})
    .map(([k, acts]) => [k, [...new Set(acts || [])].sort().join(',')])
    .filter(([, v]) => v.length > 0)
    .sort(([a], [b]) => a.localeCompare(b))
  return JSON.stringify(entries)
}

const hasDraftChanges = computed(() => {
  if (!selectedRole.value) return false
  if (String(selectedRole.value.status || '').toUpperCase() === 'DRAFT') return true
  const baseMap = buildGrantsMapFromRole(selectedRole.value)
  return serializeGrantMap(roleDraftMap.value) !== serializeGrantMap(baseMap)
})

const buildDraftGrantsPayload = () => {
  const list = []
  for (const [key, actions] of Object.entries(roleDraftMap.value)) {
    const cleanActions = [...new Set(actions || [])].filter(Boolean)
    if (!cleanActions.length) continue
    const [resourceType, database, tableName] = key.split('|')
    list.push({
      resourceType,
      database,
      tableName: resourceType === 'SCHEMA' ? '' : tableName || '',
      actions: cleanActions
    })
  }
  return list
}

// 数据加载与切源隔离
async function loadAllForSource(sourceId) {
  if (!sourceId) return
  const seq = ++sourceLoadSeq
  loading.value = true
  loadError.value = ''

  try {
    const [overviewData, userList] = await Promise.all([
      permissionsApi.getOverview(sourceId),
      usersApi.list()
    ])
    if (seq !== sourceLoadSeq || String(activeSourceId.value) !== String(sourceId)) {
      return
    }

    overview.sourceId = overviewData?.sourceId ?? sourceId
    overview.engine = overviewData?.engine || activeClusterObj.value?.sourceType || ''
    overview.roles = Array.isArray(overviewData?.roles) ? overviewData.roles : []
    overview.members = Array.isArray(overviewData?.members) ? overviewData.members : []
    overview.management = overviewData?.management || { username: '', configured: false }
    overview.capabilities = overviewData?.capabilities || {
      resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'],
      actions: []
    }

    platformUsers.value = Array.isArray(userList) ? userList : []

    if (
      !selectedRoleId.value ||
      !overview.roles.some((r) => String(r.id) === String(selectedRoleId.value))
    ) {
      selectedRoleId.value = overview.roles[0]?.id ?? null
    }
    syncDraftFromSelectedRole()

    const firstGrant = selectedRole.value?.grants?.[0]
    if (firstGrant) currentResourceType.value = firstGrant.resourceType
    await loadResources(firstGrant?.database || selectedDatabase.value, sourceId)
  } catch (error) {
    if (seq !== sourceLoadSeq || String(activeSourceId.value) !== String(sourceId)) {
      return
    }
    loadError.value = error?.message || '加载数据源权限配置失败'
  } finally {
    if (seq === sourceLoadSeq && String(activeSourceId.value) === String(sourceId)) {
      loading.value = false
    }
  }
}

async function loadResources(database = selectedDatabase.value, targetSourceId = activeSourceId.value) {
  if (!targetSourceId) return
  const seq = ++resourceLoadSeq
  resourcesState.loading = true
  resourcesState.error = ''
  try {
    const res = await permissionsApi.listResources(targetSourceId, database ? { database } : {})
    if (seq !== resourceLoadSeq || String(activeSourceId.value) !== String(targetSourceId)) {
      return
    }
    const dbs = Array.isArray(res?.databases) ? res.databases : []
    resourcesState.databases = dbs
    resourcesState.objects = Array.isArray(res?.objects) ? res.objects : []
    if (database) selectedDatabase.value = database
    else if (dbs.length > 0) {
      const preferred = dbs.includes(selectedDatabase.value) ? selectedDatabase.value : dbs[0]
      selectedDatabase.value = preferred
      await loadResources(preferred, targetSourceId)
    }
  } catch (error) {
    if (seq !== resourceLoadSeq || String(activeSourceId.value) !== String(targetSourceId)) {
      return
    }
    resourcesState.error = error?.message || '盘点数据源库表资源失败'
  } finally {
    if (seq === resourceLoadSeq && String(activeSourceId.value) === String(targetSourceId)) {
      resourcesState.loading = false
    }
  }
}

async function loadOperations(targetSourceId = activeSourceId.value) {
  if (!targetSourceId) return
  const seq = ++opsLoadSeq
  operationsState.loading = true
  operationsState.error = ''
  try {
    const res = await permissionsApi.listOperations(targetSourceId)
    if (seq !== opsLoadSeq || String(activeSourceId.value) !== String(targetSourceId)) {
      return
    }
    operationsState.list = Array.isArray(res) ? res : []
  } catch (error) {
    if (seq !== opsLoadSeq || String(activeSourceId.value) !== String(targetSourceId)) {
      return
    }
    operationsState.error = error?.message || '加载授权记录失败'
  } finally {
    if (seq === opsLoadSeq && String(activeSourceId.value) === String(targetSourceId)) {
      operationsState.loading = false
    }
  }
}

function reloadCurrentSource() {
  if (!activeSourceId.value) return
  loadAllForSource(activeSourceId.value)
  if (activeTab.value === 'operations') {
    loadOperations(activeSourceId.value)
  }
}

function handleSourceSwitch(newSourceId) {
  if (String(newSourceId) === String(activeSourceId.value)) return
  activeSourceId.value = newSourceId
  activeTab.value = 'members'
  selectedRoleId.value = null
  selectedDatabase.value = ''
  resourceSearch.value = ''
  memberFilters.keyword = ''
  memberFilters.roleId = 'all'
  memberFilters.status = 'all'
  previewDialogVisible.value = false
  emit('source-change', newSourceId)
  loadAllForSource(newSourceId)
}

function switchTab(tab) {
  activeTab.value = tab
  if (tab === 'operations') {
    loadOperations(activeSourceId.value)
  }
}

function selectRole(roleId) {
  selectedRoleId.value = roleId
  syncDraftFromSelectedRole()
  const firstGrant = selectedRole.value?.grants?.[0]
  if (firstGrant) currentResourceType.value = firstGrant.resourceType
  loadResources(firstGrant?.database || selectedDatabase.value, activeSourceId.value)
}

function switchResourceType(rt) {
  currentResourceType.value = rt
  resourceSearch.value = ''
}

function handleDatabaseChange(db) {
  selectedDatabase.value = db
  loadResources(db, activeSourceId.value)
}

function jumpToRoleMembers(roleId) {
  memberFilters.roleId = String(roleId)
  activeTab.value = 'members'
}

// 授权成员（已有用户 / 新建用户 / 服务账号）
function clearAuthorizeDialog() {
  authorizeForm.password = ''
  authorizeError.value = ''
}

function openAuthorizeDialog(defaultMode = 'existing') {
  authorizeForm.mode = defaultMode
  authorizeForm.userId = ''
  authorizeForm.username = ''
  authorizeForm.nickname = ''
  authorizeForm.email = ''
  authorizeForm.password = ''
  authorizeForm.serviceAccountId = ''
  authorizeForm.roleIds = []
  authorizeError.value = ''
  authorizeDialogVisible.value = true
}

function toggleAuthorizeRole(roleId, checked) {
  const set = new Set(authorizeForm.roleIds)
  if (checked) set.add(roleId)
  else set.delete(roleId)
  authorizeForm.roleIds = [...set]
}

async function submitAuthorize() {
  if (authorizing.value) return
  authorizeError.value = ''

  if (!authorizeForm.roleIds.length) {
    authorizeError.value = '请至少选择一个角色'
    return
  }

  if (authorizeForm.mode === 'service') {
    const serviceAccountId = String(authorizeForm.serviceAccountId ?? '').trim()
    if (!serviceAccountId) {
      authorizeError.value = '请选择平台服务账号'
      return
    }
    const account = platformUsers.value.find(u => String(u.id) === serviceAccountId && u.accountType === 'SERVICE')
    if (!account || account.enabled === false) { authorizeError.value = '请选择启用的平台服务账号'; return }
    if (authorizeForm.roleIds.some((rid) => isWriteRole(overview.roles.find((r) => r.id === rid)))) {
      authorizeError.value = '服务账号只能分配只读角色'
      return
    }

    authorizing.value = true
    try {
      const existingMember = overview.members.find(
        (m) => m.subjectType === 'SERVICE' && String(m.subjectId) === serviceAccountId
      )
      await permissionsApi.assignMemberRoles(activeSourceId.value, 'SERVICE', serviceAccountId, {
        roleIds: authorizeForm.roleIds,
        expectedRevision: existingMember?.revision ?? 0
      })
      authorizeDialogVisible.value = false
      ElMessage.success('服务账号角色已保存')
      await loadAllForSource(activeSourceId.value)
    } catch (error) {
      authorizeError.value = error?.message || '服务账号授权失败'
    } finally {
      authorizing.value = false
    }
    return
  }

  if (authorizeForm.mode === 'existing') {
    const userId = String(authorizeForm.userId ?? '').trim()
    if (!userId) {
      authorizeError.value = '请选择平台用户'
      return
    }
    const targetUser = platformUsers.value.find((u) => String(u.id) === userId)
    if (!targetUser || targetUser.accountType === 'SERVICE' || targetUser.enabled === false) {
      authorizeError.value = '请选择启用的普通用户'
      return
    }

    authorizing.value = true
    try {
      const existingMember = overview.members.find(
        (m) => m.subjectType === 'USER' && String(m.subjectId) === userId
      )
      await permissionsApi.assignMemberRoles(activeSourceId.value, 'USER', userId, {
        roleIds: authorizeForm.roleIds,
        expectedRevision: existingMember?.revision ?? 0
      })
      authorizeDialogVisible.value = false
      ElMessage.success('用户授权已保存')
      await loadAllForSource(activeSourceId.value)
    } catch (error) {
      authorizeError.value = error?.message || '用户授权失败'
    } finally {
      authorizing.value = false
    }
    return
  }

  // 新建用户并分配角色（两步：先调用用户 API，再分配角色；后一步失败必须明确说明已创建用户）
  const check = validateNewUserDraft(authorizeForm, platformUsers.value)
  if (!check.valid) {
    authorizeError.value = check.message
    return
  }

  authorizing.value = true
  let createdUser = null
  try {
    createdUser = await usersApi.create({
      username: authorizeForm.username.trim(),
      nickname: authorizeForm.nickname.trim(),
      email: authorizeForm.email.trim(),
      password: authorizeForm.password,
      role: 'user',
      enabled: true
    })
    authorizeForm.password = ''
    if (createdUser && createdUser.id !== undefined) {
      platformUsers.value = [createdUser, ...platformUsers.value]
    }
  } catch (error) {
    authorizeError.value = error?.message || '创建平台用户失败'
    authorizing.value = false
    return
  }

  try {
    await permissionsApi.assignMemberRoles(activeSourceId.value, 'USER', createdUser.id, {
      roleIds: authorizeForm.roleIds,
      expectedRevision: 0
    })
    authorizeDialogVisible.value = false
    ElMessage.success('用户已创建并完成角色授权')
    await loadAllForSource(activeSourceId.value)
  } catch (error) {
    // 后一步失败：明确说明平台用户已创建，并切换为已有用户模式避免重复创建
    authorizeForm.mode = 'existing'
    authorizeForm.userId = String(createdUser.id)
    const msg = `已创建平台用户「${createdUser.nickname || createdUser.username}」（${createdUser.username}），但分配数据源角色失败：${error?.message || '请求失败'}。已为您切换至已有用户模式，可直接重试确认授权。`
    authorizeError.value = msg
    ElMessage.warning(msg)
  } finally {
    authorizing.value = false
  }
}

// 分配角色（独立紧凑弹窗）
function openRoleAssignDialog(member) {
  roleAssignTarget.value = member
  roleAssignForm.roleIds = [...(member.roleIds || [])]
  roleAssignError.value = ''
  roleAssignDialogVisible.value = true
}

function toggleMemberRole(roleId, checked) {
  const set = new Set(roleAssignForm.roleIds)
  if (checked) set.add(roleId)
  else set.delete(roleId)
  roleAssignForm.roleIds = [...set]
}

async function submitRoleAssign() {
  if (roleAssignSaving.value || !roleAssignTarget.value) return
  roleAssignError.value = ''
  const target = roleAssignTarget.value

  if (
    target.subjectType === 'SERVICE' &&
    roleAssignForm.roleIds.some((rid) => isWriteRole(overview.roles.find((r) => r.id === rid)))
  ) {
    roleAssignError.value = '服务账号只能分配只读角色'
    return
  }

  roleAssignSaving.value = true
  try {
    await permissionsApi.assignMemberRoles(
      activeSourceId.value,
      target.subjectType,
      target.subjectId,
      {
        roleIds: roleAssignForm.roleIds,
        expectedRevision: target.revision ?? 0
      }
    )
    roleAssignDialogVisible.value = false
    ElMessage.success('角色分配已保存')
    await loadAllForSource(activeSourceId.value)
  } catch (error) {
    roleAssignError.value = error?.message || '保存角色分配失败'
  } finally {
    roleAssignSaving.value = false
  }
}

// 绑定数据库账号（独立紧凑弹窗）
const isKeepingSameMemberAccount = computed(() => {
  const orig = accountTarget.value?.account
  if (!orig?.configured || !orig?.username) return false
  return accountForm.username.trim() === orig.username
})

function clearAccountDialog() {
  accountForm.password = ''
  accountError.value = ''
}

function openAccountDialog(member) {
  accountTarget.value = member
  accountForm.username = member.account?.username || ''
  accountForm.password = ''
  accountError.value = ''
  accountDialogVisible.value = true
}

async function submitAccountBind() {
  if (accountSaving.value || !accountTarget.value) return
  accountError.value = ''
  const username = accountForm.username.trim()
  const password = accountForm.password

  if (!username) {
    accountError.value = '请输入数据库账号'
    return
  }
  if (!password && !isKeepingSameMemberAccount.value) {
    accountError.value = '首次绑定或更换账号时，请输入密码'
    return
  }

  accountSaving.value = true
  try {
    const target = accountTarget.value
    await permissionsApi.bindMemberAccount(
      activeSourceId.value,
      target.subjectType,
      target.subjectId,
      {
        username,
        password: password || '',
        expectedRevision: target.revision ?? 0
      }
    )
    accountForm.password = ''
    accountDialogVisible.value = false
    ElMessage.success('数据库账号已绑定')
    await loadAllForSource(activeSourceId.value)
  } catch (error) {
    accountError.value = error?.message || '绑定数据库账号失败'
  } finally {
    accountSaving.value = false
  }
}

async function handleRemoveMember(member) {
  try {
    await ElMessageBox.confirm(
      `确认移除成员「${getMemberDisplayName(member)}」吗？将撤销其托管角色并删除该数据源账号映射（不删除平台用户与数据库账号）。`,
      '移除成员',
      {
        type: 'warning',
        confirmButtonText: '确认移除',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  await permissionsApi.removeMember(activeSourceId.value, member.subjectType, member.subjectId)
  ElMessage.success('成员已移除')
  await loadAllForSource(activeSourceId.value)
}

// 账号权限 Inspect
const normalizedInspectGrants = computed(() => {
  const raw = inspectState.data?.grants
  if (!Array.isArray(raw)) return []
  return raw.map((item) => {
    if (typeof item === 'string') {
      return { scope: item, actionsText: '-', sourceText: '原生回读' }
    }
    const scope =
      item.scope ||
      item.object ||
      [item.database, item.tableName || (item.resourceType === 'SCHEMA' ? '*' : '')]
        .filter(Boolean)
        .join('.') ||
      item.resourceType ||
      '-'
    const actionsText = Array.isArray(item.actions)
      ? item.actions.join(', ')
      : item.privilege || item.action || '-'
    const sourceText = item.source || item.roleName || item.grantedBy || '数据库原生'
    return { scope, actionsText, sourceText }
  })
})

async function openInspectDialog(member) {
  inspectTarget.value = member
  inspectState.loading = true
  inspectState.error = ''
  inspectState.data = null
  inspectDialogVisible.value = true
  try {
    inspectState.data = await permissionsApi.inspectMember(
      activeSourceId.value,
      member.subjectType,
      member.subjectId
    )
  } catch (error) {
    inspectState.error = error?.message || '核查数据库实际权限失败'
  } finally {
    inspectState.loading = false
  }
}

// 管理员高级管理连接
const isKeepingSameManagementAccount = computed(() => {
  const mgmt = overview.management
  if (!mgmt?.configured || !mgmt?.username) return false
  return managementForm.username.trim() === mgmt.username
})

function clearManagementDialog() {
  managementForm.password = ''
  managementError.value = ''
}

function openManagementDialog() {
  managementForm.username = overview.management?.username || ''
  managementForm.password = ''
  managementError.value = ''
  managementDialogVisible.value = true
}

async function submitManagementAccount() {
  if (managementSaving.value) return
  managementError.value = ''
  const username = managementForm.username.trim()
  const password = managementForm.password

  if (!username) {
    managementError.value = '请输入授权管理账号'
    return
  }
  if (!password && !isKeepingSameManagementAccount.value) {
    managementError.value = '首次配置或更换管理账号时，请输入密码'
    return
  }

  managementSaving.value = true
  try {
    const res = await permissionsApi.saveManagementAccount(activeSourceId.value, {
      username,
      password: password || ''
    })
    overview.management = res || { username, configured: true }
    managementForm.password = ''
    managementDialogVisible.value = false
    ElMessage.success('授权管理连接已保存')
    await loadResources(selectedDatabase.value, activeSourceId.value)
  } catch (error) {
    managementError.value = error?.message || '保存授权管理连接失败'
  } finally {
    managementSaving.value = false
  }
}

// 角色新建 / 改名 / 删除
function openCreateRoleDialog() {
  createRoleForm.name = ''
  createRoleForm.template = 'READONLY'
  createRoleError.value = ''
  createRoleDialogVisible.value = true
}

async function submitCreateRole() {
  if (creatingRole.value) return
  createRoleError.value = ''
  const name = createRoleForm.name.trim()
  if (!name) {
    createRoleError.value = '请输入角色名称'
    return
  }
  if (overview.roles.some((r) => r.name === name)) {
    createRoleError.value = '角色名称已存在，请使用其他名称'
    return
  }

  creatingRole.value = true
  try {
    const created = await permissionsApi.createRole(activeSourceId.value, {
      name,
      template: createRoleForm.template
    })
    createRoleDialogVisible.value = false
    await loadAllForSource(activeSourceId.value)
    if (created?.id !== undefined) {
      selectedRoleId.value = created.id
      syncDraftFromSelectedRole()
      // 若后端新建模板角色尚未预置资源行，且当前已选中某库，则按模板生成本地草稿供管理员直接预览确认
      const tpl = ROLE_TEMPLATES.find((t) => t.value === createRoleForm.template)
      if (tpl?.actions?.length && selectedDatabase.value && !Object.keys(roleDraftMap.value).length) {
        const schemaKey = makeGrantKey('SCHEMA', selectedDatabase.value, '')
        const supportedKeys = tpl.actions.filter((actKey) => {
          const col = normalizedCapabilityActions.value.find((c) => c.key === actKey)
          return col && isActionSupported(col, 'SCHEMA')
        })
        if (supportedKeys.length) {
          roleDraftMap.value = { [schemaKey]: supportedKeys }
        }
      }
    }
    ElMessage.success('角色已创建，请确认资源范围并预览保存')
  } catch (error) {
    createRoleError.value = error?.message || '创建角色失败'
  } finally {
    creatingRole.value = false
  }
}

function openRenameRoleDialog(role) {
  renameRoleTarget.value = role
  renameRoleForm.name = role.name || ''
  renameRoleError.value = ''
  renameRoleDialogVisible.value = true
}

async function submitRenameRole() {
  if (renamingRole.value || !renameRoleTarget.value) return
  renameRoleError.value = ''
  const name = renameRoleForm.name.trim()
  if (!name) {
    renameRoleError.value = '请输入角色名称'
    return
  }

  renamingRole.value = true
  try {
    await permissionsApi.updateRole(activeSourceId.value, renameRoleTarget.value.id, { name })
    renameRoleDialogVisible.value = false
    ElMessage.success('角色名称已更新')
    await loadAllForSource(activeSourceId.value)
  } catch (error) {
    renameRoleError.value = error?.message || '修改角色名称失败'
  } finally {
    renamingRole.value = false
  }
}

async function handleDeleteRole(role) {
  if (countRoleMembers(role.id) > 0) {
    ElMessage.warning('请先移除该角色下的成员后再删除')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认删除角色「${role.name}」吗？将即时删除数据库托管原生角色（不删除成员登录账号）。`,
      '删除角色',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  const result = await permissionsApi.deleteRole(activeSourceId.value, role.id)
  if (result?.state !== 'SUCCESS') {
    operationDetail.value = result || { state: 'UNKNOWN', message: '未获取到角色删除结果' }
    operationDetailVisible.value = true
    ElMessage.error(`角色删除未完成，操作 ID：${result?.operationId || result?.id || '未知'}，请核对授权记录`)
    await loadAllForSource(activeSourceId.value)
    return
  }
  ElMessage.success('角色已删除')
  if (String(selectedRoleId.value) === String(role.id)) {
    selectedRoleId.value = null
  }
  await loadAllForSource(activeSourceId.value)
}

// 权限预览与确认执行 (Preview -> Apply)
async function handlePreviewGrants() {
  if (previewing.value || !selectedRole.value) return
  previewing.value = true
  try {
    const payload = {
      expectedRevision: selectedRole.value.revision ?? 0,
      grants: buildDraftGrantsPayload()
    }
    const res = await permissionsApi.previewRoleGrants(
      activeSourceId.value,
      selectedRole.value.id,
      payload
    )
    previewResult.value = res || { previewId: '', sql: [], warnings: [], memberCount: 0 }
    previewDialogVisible.value = true
  } finally {
    previewing.value = false
  }
}

async function handleApplyGrants() {
  if (applying.value || !selectedRole.value || !previewResult.value?.previewId) return
  applying.value = true
  try {
    const res = await permissionsApi.applyRoleGrants(
      activeSourceId.value,
      selectedRole.value.id,
      { previewId: previewResult.value.previewId }
    )
    previewDialogVisible.value = false
    const state = String(res?.state || 'UNKNOWN').toUpperCase()
    const opId = res?.operationId || res?.id || '-'

    if (state === 'SUCCESS') {
      ElMessage.success(res?.message || '权限变更已生效')
    } else {
      const statusMsg = `权限执行未完全成功（状态：${state}，操作ID：${opId}）${res?.message ? `：${res.message}` : ''}`
      ElMessage.error(statusMsg)
      operationDetail.value = {
        ...res,
        operationId: opId,
        state
      }
      operationDetailVisible.value = true
    }

    await loadAllForSource(activeSourceId.value)
  } finally {
    applying.value = false
  }
}

const getOperationStateMeta = (state) => {
  const s = String(state || '').toUpperCase()
  if (s === 'SUCCESS' || s === 'SUCCEEDED') return { label: '已生效', tagType: 'success' }
  if (s === 'PARTIAL') return { label: '部分成功', tagType: 'warning' }
  if (s === 'FAILED') return { label: '失败', tagType: 'danger' }
  if (s === 'UNKNOWN') return { label: '待核对', tagType: 'warning' }
  return { label: s || '未知', tagType: 'info' }
}

const getOperationDialogTitle = (op) => {
  const s = String(op?.state || '').toUpperCase()
  if (s === 'SUCCESS') return '授权已生效'
  if (s === 'PARTIAL') return '部分成功，请核对操作结果'
  if (s === 'FAILED') return '授权执行失败'
  return '授权执行状态待核对'
}

function openOperationDetail(row) {
  operationDetail.value = {
    ...row,
    operationId: row.operationId || row.id
  }
  operationDetailVisible.value = true
}

watch(
  () => [props.modelValue, props.cluster?.id],
  ([visible, clusterId]) => {
    if (!visible) return
    if (clusterId !== undefined && clusterId !== null) {
      const switched = String(activeSourceId.value) !== String(clusterId)
      activeSourceId.value = clusterId
      if (switched) {
        activeTab.value = 'members'
        selectedRoleId.value = null
        selectedDatabase.value = ''
      }
      loadAllForSource(clusterId)
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.drawer-header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.drawer-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--odw-text-primary, #1a202c);
}

.current-source-label {
  font-size: 13px;
  color: #4b5563;
}

.source-switcher {
  width: 220px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mgmt-status-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #9ca3af;
  margin-left: 6px;
}

.mgmt-status-dot.is-configured {
  background: #287649;
}

.permission-center-body {
  display: flex;
  flex-direction: column;
  min-height: 480px;
}

.top-alert {
  margin-bottom: 14px;
}

.center-tabs {
  display: flex;
  gap: 28px;
  border-bottom: 1px solid #e4e7ed;
  margin-bottom: 16px;
}

.center-tab-btn {
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  padding: 10px 2px;
  font-size: 13px;
  color: #667085;
  cursor: pointer;
}

.center-tab-btn.active {
  color: #2563c9;
  border-bottom-color: #2563c9;
  font-weight: 600;
}

.list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.filter-controls {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.member-search-input {
  width: 210px;
}

.member-filter-select {
  width: 140px;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
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

.muted-text {
  color: #667085;
  font-size: 12px;
}

.table-footer {
  padding-top: 12px;
  font-size: 12px;
  color: #667085;
}

/* 角色布局 */
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

.role-nav-item.active {
  background: #edf4ff;
  color: #2563c9;
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

.rt-btn.active {
  background: #edf4ff;
  color: #2563c9;
  font-weight: 500;
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

/* 弹窗内部样式 */
.dialog-source-strip {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 12px;
  color: #667085;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.dialog-source-strip strong {
  color: #253247;
  font-size: 14px;
}

.mode-radio-group {
  margin-bottom: 16px;
}

.role-checkbox-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 260px;
  overflow-y: auto;
  width: 100%;
}

.role-check-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 6px 4px;
  cursor: pointer;
}

.role-check-item.disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.role-check-meta strong {
  display: block;
  font-size: 13px;
  line-height: 1.4;
}

.role-check-meta small {
  display: block;
  font-size: 12px;
  color: #667085;
  margin-top: 2px;
}

.picker-count {
  font-size: 12px;
  color: #667085;
  padding-top: 10px;
}

.quick-agent-chips {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 6px;
}

.chip-hint {
  font-size: 12px;
  color: #667085;
}

.agent-chip-btn {
  border: 1px solid #d3dae5;
  background: #f8fafc;
  border-radius: 3px;
  padding: 2px 8px;
  font-size: 12px;
  cursor: pointer;
}

.field-hint {
  font-size: 12px;
  color: #667085;
  line-height: 1.6;
  margin: 6px 0 0;
}

.form-error {
  color: #b63737;
  font-size: 12px;
  margin: 10px 0 0;
}

.state-note {
  padding: 10px 12px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  margin: 10px 0;
}

.warning-note {
  color: #80590a;
  background: #fff7e7;
}

.preview-summary-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.preview-summary-bar strong {
  margin-right: 10px;
}

.section-mini-label {
  display: block;
  font-size: 12px;
  color: #667085;
  margin-bottom: 6px;
}

.native-sql {
  background: #f6f8fb;
  padding: 12px;
  font: 12px/1.7 "SFMono-Regular", Consolas, monospace;
  white-space: pre-wrap;
  overflow: auto;
  color: #344054;
  border-radius: 4px;
  max-height: 220px;
  margin: 0;
}

.refine-group {
  margin-bottom: 14px;
}

.refine-group-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.native-checkbox-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.native-option-label {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.op-id-code {
  font-size: 12px;
}

.sub-cell-text {
  font-size: 12px;
  color: #667085;
}

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
