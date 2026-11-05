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
          <span v-else-if="activeClusterName" class="current-source-label">{{
            activeClusterName
          }}</span>
        </div>
        <div class="header-actions">
          <el-button size="small" data-testid="open-management-btn" @click="openManagementDialog">
            授权管理连接
            <span
              class="mgmt-status-dot"
              :class="{ 'is-configured': overview.management?.configured }"
            />
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

      <PermissionMembersPanel v-if="activeTab === 'members'" :model="permissionMembersPanelView" />
      <RoleMatrixPanel v-else-if="activeTab === 'roles'" :model="roleMatrixPanelView" />
      <PermissionOperationsPanel
        v-else-if="activeTab === 'operations'"
        :model="permissionOperationsPanelView"
      />
    </div>

    <MemberAuthorizationDialog :model="memberAuthorizationDialogView" />
    <MemberRolesDialog :model="memberRolesDialogView" />
    <MemberBindingDialog :model="memberBindingDialogView" />
    <MemberGrantInspectDialog :model="memberGrantInspectDialogView" />
    <ManagementConnectionDialog :model="managementConnectionDialogView" />
    <RoleEditorDialogs :model="roleEditorDialogsView" />
    <RoleRefineDialog :model="roleRefineDialogView" />
    <RolePreviewDialog :model="rolePreviewDialogView" />
    <PermissionOperationDetail :model="permissionOperationDetailView" />
    <ServiceAccountTokens
      v-model="newServiceTokensVisible"
      :account="memberState.createdServiceAccount.value"
    />
  </el-drawer>
</template>

<script setup>
import { computed, ref, watch, reactive, onBeforeUnmount } from 'vue'
import { permissionsApi } from '@/api/permissions'
import { usersApi } from '@/api/users'
import {
  getTemplateLabel,
  formatDateTime,
  getOperationStateMeta,
  ROLE_TEMPLATES,
  getOperationDialogTitle,
} from '../composables/permissions/permissionLabels'
import { usePermissionScope } from '../composables/permissions/usePermissionScope'
import { usePermissionMatrix } from '../composables/permissions/usePermissionMatrix'
import { usePermissionRoleActions } from '../composables/permissions/usePermissionRoleActions'
import { usePermissionMembers } from '../composables/permissions/usePermissionMembers'
import { usePermissionManagement } from '../composables/permissions/usePermissionManagement'
import { usePermissionOperations } from '../composables/permissions/usePermissionOperations'
import PermissionMembersPanel from './datasource-permissions/PermissionMembersPanel.vue'
import RoleMatrixPanel from './datasource-permissions/RoleMatrixPanel.vue'
import PermissionOperationsPanel from './datasource-permissions/PermissionOperationsPanel.vue'
import MemberAuthorizationDialog from './datasource-permissions/MemberAuthorizationDialog.vue'
import MemberRolesDialog from './datasource-permissions/MemberRolesDialog.vue'
import MemberBindingDialog from './datasource-permissions/MemberBindingDialog.vue'
import MemberGrantInspectDialog from './datasource-permissions/MemberGrantInspectDialog.vue'
import ManagementConnectionDialog from './datasource-permissions/ManagementConnectionDialog.vue'
import RoleEditorDialogs from './datasource-permissions/RoleEditorDialogs.vue'
import RoleRefineDialog from './datasource-permissions/RoleRefineDialog.vue'
import RolePreviewDialog from './datasource-permissions/RolePreviewDialog.vue'
import PermissionOperationDetail from './datasource-permissions/PermissionOperationDetail.vue'
import ServiceAccountTokens from './ServiceAccountTokens.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: true },
  cluster: { type: Object, default: null },
  clusters: { type: Array, default: () => [] },
})

const emit = defineEmits(['update:modelValue', 'source-change'])

const activeTab = ref('members')
const scopeState = usePermissionScope({ props })
const matrixState = usePermissionMatrix({
  captureRequest: scopeState.captureRequest,
  overview: scopeState.overview,
  isPostgresEngine: scopeState.isPostgresEngine,
  activeSourceId: scopeState.activeSourceId,
})
const operationState = usePermissionOperations({
  captureRequest: scopeState.captureRequest,
  activeSourceId: scopeState.activeSourceId,
  getRoleName: scopeState.getRoleName,
  getPlatformUserName: scopeState.getPlatformUserName,
})
const memberState = usePermissionMembers({
  captureRequest: scopeState.captureRequest,
  overview: scopeState.overview,
  getMemberStatus: scopeState.getMemberStatus,
  getMemberDisplayName: scopeState.getMemberDisplayName,
  getRoleName: scopeState.getRoleName,
  platformUsers: scopeState.platformUsers,
  loadAllForSource: loadAllForSource,
  loadOperations: operationState.loadOperations,
})
const newServiceTokensVisible = computed({
  get: () => Boolean(memberState.createdServiceAccount.value),
  set: (open) => {
    if (!open) memberState.createdServiceAccount.value = null
  },
})
const roleState = usePermissionRoleActions({
  captureRequest: scopeState.captureRequest,
  overview: scopeState.overview,
  loadAllForSource: loadAllForSource,
  selectedRoleId: matrixState.selectedRoleId,
  syncDraftFromSelectedRole: matrixState.syncDraftFromSelectedRole,
  countRoleMembers: scopeState.countRoleMembers,
  operationDetail: operationState.operationDetail,
  operationDetailVisible: operationState.operationDetailVisible,
  selectedRole: matrixState.selectedRole,
  buildDraftGrantsPayload: matrixState.buildDraftGrantsPayload,
})
const managementState = usePermissionManagement({
  captureRequest: scopeState.captureRequest,
  overview: scopeState.overview,
  loadResources: matrixState.loadResources,
  selectedDatabase: matrixState.selectedDatabase,
})

const {
  loading,
  loadError,
  activeSourceId,
  overview,
  activeClusterObj,
  platformUsers,
  activeClusterName,
  currentEngine,
} = scopeState
const {
  selectedRoleId,
  syncDraftFromSelectedRole,
  selectedRole,
  currentResourceType,
  loadResources,
  selectedDatabase,
} = matrixState
const { memberFilters } = memberState
const { openManagementDialog } = managementState
const { loadOperations } = operationState

const drawerVisible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

let sourceLoadSeq = 0

async function loadAllForSource(sourceId) {
  if (!sourceId) return
  const request = scopeState.captureRequest()
  const seq = ++sourceLoadSeq
  loading.value = true
  loadError.value = ''

  try {
    const [overviewData, userList] = await Promise.all([
      permissionsApi.getOverview(sourceId),
      usersApi.list(),
    ])
    if (
      !request.isCurrent() ||
      seq !== sourceLoadSeq ||
      String(activeSourceId.value) !== String(sourceId)
    ) {
      return
    }

    overview.sourceId = overviewData?.sourceId ?? sourceId
    overview.engine = overviewData?.engine || activeClusterObj.value?.sourceType || ''
    overview.roles = Array.isArray(overviewData?.roles) ? overviewData.roles : []
    overview.members = Array.isArray(overviewData?.members) ? overviewData.members : []
    overview.management = overviewData?.management || { username: '', configured: false }
    overview.capabilities = overviewData?.capabilities || {
      resourceTypes: ['SCHEMA', 'TABLE', 'VIEW'],
      actions: [],
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
    if (
      !request.isCurrent() ||
      seq !== sourceLoadSeq ||
      String(activeSourceId.value) !== String(sourceId)
    ) {
      return
    }
    loadError.value = error?.message || '加载数据源权限配置失败'
  } finally {
    if (
      request.isCurrent() &&
      seq === sourceLoadSeq &&
      String(activeSourceId.value) === String(sourceId)
    ) {
      loading.value = false
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

function resetSourceState() {
  scopeState.invalidateRequests()
  sourceLoadSeq += 1
  matrixState.reset()
  roleState.reset()
  memberState.reset()
  managementState.reset()
  operationState.reset()
  scopeState.clearOverview()
}

function handleSourceSwitch(newSourceId) {
  if (String(newSourceId) === String(activeSourceId.value)) return
  resetSourceState()
  activeSourceId.value = newSourceId
  activeTab.value = 'members'
  emit('source-change', newSourceId)
  loadAllForSource(newSourceId)
}

function switchTab(tab) {
  activeTab.value = tab
  if (tab === 'operations') {
    loadOperations(activeSourceId.value)
  }
}

function jumpToRoleMembers(roleId) {
  memberFilters.roleId = String(roleId)
  activeTab.value = 'members'
}

function navigateToRole(roleId) {
  activeTab.value = 'roles'
  matrixState.selectRole(roleId)
}

const permissionMembersPanelView = reactive({
  loading: scopeState.loading,
  overview: scopeState.overview,
  getRoleName: scopeState.getRoleName,
  getMemberDisplayName: scopeState.getMemberDisplayName,
  getMemberStatus: scopeState.getMemberStatus,
  memberFilters: memberState.memberFilters,
  filteredMembers: memberState.filteredMembers,
  openAuthorizeDialog: memberState.openAuthorizeDialog,
  openRoleAssignDialog: memberState.openRoleAssignDialog,
  openAccountDialog: memberState.openAccountDialog,
  handleRemoveMember: memberState.handleRemoveMember,
  openInspectDialog: memberState.openInspectDialog,
  navigateToRole: navigateToRole,
})

const roleMatrixPanelView = reactive({
  getTemplateLabel: getTemplateLabel,
  loading: scopeState.loading,
  overview: scopeState.overview,
  isPostgresEngine: scopeState.isPostgresEngine,
  countRoleMembers: scopeState.countRoleMembers,
  getResourceTypeLabel: scopeState.getResourceTypeLabel,
  selectedRoleId: matrixState.selectedRoleId,
  roleSearchKeyword: matrixState.roleSearchKeyword,
  currentResourceType: matrixState.currentResourceType,
  selectedDatabase: matrixState.selectedDatabase,
  resourceSearch: matrixState.resourceSearch,
  capabilityNoteVisible: matrixState.capabilityNoteVisible,
  resourcesState: matrixState.resourcesState,
  filteredRoles: matrixState.filteredRoles,
  selectedRole: matrixState.selectedRole,
  availableResourceTypes: matrixState.availableResourceTypes,
  normalizedCapabilityActions: matrixState.normalizedCapabilityActions,
  matrixActionColumns: matrixState.matrixActionColumns,
  isActionSupported: matrixState.isActionSupported,
  getUnsupportedBadge: matrixState.getUnsupportedBadge,
  isActionDisabledForRole: matrixState.isActionDisabledForRole,
  getCellTooltip: matrixState.getCellTooltip,
  currentScopeNote: matrixState.currentScopeNote,
  currentResourceRows: matrixState.currentResourceRows,
  isActionChecked: matrixState.isActionChecked,
  isActionIndeterminate: matrixState.isActionIndeterminate,
  isCellChanged: matrixState.isCellChanged,
  toggleMatrixAction: matrixState.toggleMatrixAction,
  hasRefinableActions: matrixState.hasRefinableActions,
  openRefineDialog: matrixState.openRefineDialog,
  batchSelectTemplate: matrixState.batchSelectTemplate,
  onlyGranted: matrixState.onlyGranted,
  grantCounts: matrixState.grantCounts,
  databaseOptions: matrixState.databaseOptions,
  draftChangeCounts: matrixState.draftChangeCounts,
  resetRoleDraft: matrixState.resetRoleDraft,
  hasDraftChanges: matrixState.hasDraftChanges,
  loadResources: matrixState.loadResources,
  selectRole: matrixState.selectRole,
  switchResourceType: matrixState.switchResourceType,
  handleDatabaseChange: matrixState.handleDatabaseChange,
  previewing: roleState.previewing,
  applying: roleState.applying,
  openCreateRoleDialog: roleState.openCreateRoleDialog,
  openRenameRoleDialog: roleState.openRenameRoleDialog,
  handleDeleteRole: roleState.handleDeleteRole,
  handlePreviewGrants: roleState.handlePreviewGrants,
  jumpToRoleMembers: jumpToRoleMembers,
})

const permissionOperationsPanelView = reactive({
  formatDateTime: formatDateTime,
  getOperationStateMeta: getOperationStateMeta,
  getRoleName: scopeState.getRoleName,
  getPlatformUserName: scopeState.getPlatformUserName,
  operationsState: operationState.operationsState,
  loadOperations: operationState.loadOperations,
  openOperationDetail: operationState.openOperationDetail,
  opFilters: operationState.opFilters,
  filteredOperations: operationState.filteredOperations,
  overview: scopeState.overview,
})

const memberAuthorizationDialogView = reactive({
  overview: scopeState.overview,
  platformUsers: scopeState.platformUsers,
  activeClusterName: scopeState.activeClusterName,
  summarizeRoleGrants: scopeState.summarizeRoleGrants,
  authorizeDialogVisible: memberState.authorizeDialogVisible,
  authorizing: memberState.authorizing,
  authorizeError: memberState.authorizeError,
  authorizeForm: memberState.authorizeForm,
  clearAuthorizeDialog: memberState.clearAuthorizeDialog,
  toggleAuthorizeRole: memberState.toggleAuthorizeRole,
  submitAuthorize: memberState.submitAuthorize,
})

const memberRolesDialogView = reactive({
  overview: scopeState.overview,
  activeClusterName: scopeState.activeClusterName,
  getMemberDisplayName: scopeState.getMemberDisplayName,
  summarizeRoleGrants: scopeState.summarizeRoleGrants,
  roleAssignDialogVisible: memberState.roleAssignDialogVisible,
  roleAssignSaving: memberState.roleAssignSaving,
  roleAssignError: memberState.roleAssignError,
  roleAssignTarget: memberState.roleAssignTarget,
  roleAssignForm: memberState.roleAssignForm,
  toggleMemberRole: memberState.toggleMemberRole,
  submitRoleAssign: memberState.submitRoleAssign,
})

const memberBindingDialogView = reactive({
  activeClusterName: scopeState.activeClusterName,
  getMemberDisplayName: scopeState.getMemberDisplayName,
  accountDialogVisible: memberState.accountDialogVisible,
  accountSaving: memberState.accountSaving,
  accountError: memberState.accountError,
  accountTarget: memberState.accountTarget,
  accountForm: memberState.accountForm,
  isKeepingSameMemberAccount: memberState.isKeepingSameMemberAccount,
  clearAccountDialog: memberState.clearAccountDialog,
  submitAccountBind: memberState.submitAccountBind,
})

const memberGrantInspectDialogView = reactive({
  activeClusterName: scopeState.activeClusterName,
  getMemberDisplayName: scopeState.getMemberDisplayName,
  getMemberStatus: scopeState.getMemberStatus,
  formatDateTime: formatDateTime,
  inspectDialogVisible: memberState.inspectDialogVisible,
  inspectTarget: memberState.inspectTarget,
  inspectState: memberState.inspectState,
  normalizedInspectGrants: memberState.normalizedInspectGrants,
  diagnosisIssues: memberState.diagnosisIssues,
  expectedGrantRows: memberState.expectedGrantRows,
  reapplying: memberState.reapplying,
  handleReapplyRoles: memberState.handleReapplyRoles,
  openOperationDetail: operationState.openOperationDetail,
  navigateToRole: navigateToRole,
})

const managementConnectionDialogView = reactive({
  managementDialogVisible: managementState.managementDialogVisible,
  managementSaving: managementState.managementSaving,
  managementError: managementState.managementError,
  managementForm: managementState.managementForm,
  isKeepingSameManagementAccount: managementState.isKeepingSameManagementAccount,
  clearManagementDialog: managementState.clearManagementDialog,
  submitManagementAccount: managementState.submitManagementAccount,
})

const roleEditorDialogsView = reactive({
  ROLE_TEMPLATES: ROLE_TEMPLATES,
  createRoleDialogVisible: roleState.createRoleDialogVisible,
  creatingRole: roleState.creatingRole,
  createRoleError: roleState.createRoleError,
  createRoleForm: roleState.createRoleForm,
  renameRoleDialogVisible: roleState.renameRoleDialogVisible,
  renamingRole: roleState.renamingRole,
  renameRoleError: roleState.renameRoleError,
  renameRoleForm: roleState.renameRoleForm,
  currentTemplateDescription: roleState.currentTemplateDescription,
  submitCreateRole: roleState.submitCreateRole,
  submitRenameRole: roleState.submitRenameRole,
})

const roleRefineDialogView = reactive({
  refineDialogVisible: matrixState.refineDialogVisible,
  refineTarget: matrixState.refineTarget,
  isActionDisabledForRole: matrixState.isActionDisabledForRole,
  getRefinableColumns: matrixState.getRefinableColumns,
  isNativeSubActionChecked: matrixState.isNativeSubActionChecked,
  toggleNativeSubAction: matrixState.toggleNativeSubAction,
})

const rolePreviewDialogView = reactive({
  formatDateTime: formatDateTime,
  countRoleMembers: scopeState.countRoleMembers,
  selectedRole: matrixState.selectedRole,
  getResourceTypeLabel: scopeState.getResourceTypeLabel,
  applying: roleState.applying,
  previewDialogVisible: roleState.previewDialogVisible,
  previewResult: roleState.previewResult,
  handleApplyGrants: roleState.handleApplyGrants,
})

const permissionOperationDetailView = reactive({
  getOperationStateMeta: getOperationStateMeta,
  getOperationDialogTitle: getOperationDialogTitle,
  operationDetailVisible: operationState.operationDetailVisible,
  operationDetail: operationState.operationDetail,
  activeTab: activeTab,
  switchTab: switchTab,
})

watch(
  () => [props.modelValue, props.cluster?.id],
  ([visible, clusterId]) => {
    if (!visible) {
      resetSourceState()
      return
    }
    if (clusterId !== undefined && clusterId !== null) {
      const switched = String(activeSourceId.value) !== String(clusterId)
      if (switched) {
        resetSourceState()
        activeTab.value = 'members'
      }
      activeSourceId.value = clusterId
      loadAllForSource(clusterId)
    }
  },
  { immediate: true, flush: 'sync' }
)
onBeforeUnmount(resetSourceState)
</script>

<style scoped lang="scss">
@use './datasource-permissions/permission-shared';

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
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.center-tab-btn.active {
  color: #2563c9;
  border-bottom-color: #2563c9;
  font-weight: 600;
}
</style>
