<template>
  <el-drawer
    :model-value="modelValue"
    :title="`${accountName} · 数据权限`"
    size="960px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
    @open="load"
  >
    <div class="access-bar">
      <span class="access-hint">
        已在 {{ grantedCount }} /
        {{ sources.length }}
        个数据源有访问权限。首次分配角色会自动创建数据库登录账号，移除会删除平台创建的账号。
      </span>
      <div class="access-actions">
        <el-button
          type="danger"
          plain
          size="small"
          data-testid="revoke-all-btn"
          :disabled="!grantedCount || revoking"
          :loading="revoking"
          @click="revokeAll"
        >
          回收全部权限
        </el-button>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      show-icon
      class="access-alert"
    />
    <el-alert
      v-if="revokeFailures.length"
      type="error"
      :closable="false"
      show-icon
      class="access-alert"
      :title="`有 ${revokeFailures.length} 个数据源回收失败，其余已回收`"
    >
      <div v-for="f in revokeFailures" :key="f.sourceId">{{ f.clusterName }}：{{ f.message }}</div>
    </el-alert>

    <el-table
      v-loading="loading"
      :data="sources"
      border
      row-key="sourceId"
      empty-text="暂无支持权限管理的数据源"
    >
      <el-table-column label="数据源" min-width="150">
        <template #default="{ row }">
          <div class="source-name">{{ row.clusterName }}</div>
          <el-tag size="small" type="info">{{ row.engine }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="角色" min-width="300">
        <template #default="{ row }">
          <el-checkbox-group
            v-if="row.roles?.length"
            v-model="drafts[row.sourceId]"
            class="role-list"
          >
            <el-checkbox
              v-for="role in row.roles"
              :key="role.id"
              :value="role.id"
              :data-testid="`role-${row.sourceId}-${role.id}`"
            >
              <span>{{ role.name }}</span>
              <el-tag
                v-if="role.status && role.status !== 'ACTIVE'"
                size="small"
                type="warning"
                class="role-tag"
              >
                {{ role.status === 'DRAFT' ? '草稿' : role.status }}
              </el-tag>
              <div class="role-summary">{{ summarizeGrants(role) }}</div>
            </el-checkbox>
          </el-checkbox-group>
          <span v-else class="muted">该数据源尚无角色，请在「数据源 → 权限配置」中创建</span>
        </template>
      </el-table-column>
      <el-table-column label="数据库账号" min-width="150">
        <template #default="{ row }">
          <template v-if="row.member?.account?.username">
            <div>{{ row.member.account.username }}</div>
            <el-tag v-if="row.member.account.managed" size="small">平台自动创建</el-tag>
          </template>
          <span v-else class="muted">首次授权时自动创建</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="150">
        <template #default="{ row }">
          <el-tag v-if="row.member" size="small" :type="memberStatus(row.member).tagType">
            {{ memberStatus(row.member).label }}
          </el-tag>
          <span v-else class="muted">未授权</span>
          <div v-if="row.lastFailedOperation" class="row-error">
            最近失败：{{ row.lastFailedOperation.message || row.lastFailedOperation.state }}
          </div>
          <div v-if="rowErrors[row.sourceId]" class="row-error">{{ rowErrors[row.sourceId] }}</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button
            type="primary"
            text
            size="small"
            :data-testid="`save-${row.sourceId}`"
            :disabled="!!saveBlockedReason(row)"
            :title="saveBlockedReason(row)"
            :loading="savingSource === row.sourceId"
            @click="save(row)"
          >
            保存
          </el-button>
          <el-button
            v-if="row.member"
            type="danger"
            text
            size="small"
            :data-testid="`remove-${row.sourceId}`"
            :disabled="savingSource !== null"
            @click="remove(row)"
          >
            移除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-drawer>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { usersApi } from '@/api/users'
import { permissionsApi } from '@/api/permissions'
import { memberStatus, summarizeGrants } from '../composables/permissions/permissionLabels'
import { revokeAllAccess, sourcesWithAccess } from '../composables/permissions/subjectAccess'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  account: { type: Object, default: null },
})
const emit = defineEmits(['update:modelValue', 'changed'])

const loading = ref(false)
const loadError = ref('')
const access = ref(null)
const drafts = reactive({})
const rowErrors = reactive({})
const savingSource = ref(null)
const revoking = ref(false)
const revokeFailures = ref([])

const accountName = computed(() => props.account?.nickname || props.account?.username || '账号')
const sources = computed(() => access.value?.sources || [])
const grantedCount = computed(() => sourcesWithAccess(access.value).length)

const sameIds = (a, b) =>
  a.length === b.length && a.every((id) => b.some((x) => String(x) === String(id)))

let loadSeq = 0
async function load() {
  if (!props.account?.id) return
  const seq = ++loadSeq
  loading.value = true
  loadError.value = ''
  try {
    const data = await usersApi.datasourceAccess(props.account.id)
    if (seq !== loadSeq) return
    access.value = data
    for (const source of data?.sources || [])
      drafts[source.sourceId] = [...(source.member?.roleIds || [])]
  } catch (error) {
    if (seq !== loadSeq) return
    loadError.value = error?.message || '加载数据权限失败'
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

function saveBlockedReason(row) {
  if (savingSource.value !== null || revoking.value) return '正在执行其他操作'
  const draft = drafts[row.sourceId] || []
  if (sameIds(draft, row.member?.roleIds || [])) return '角色未变化'
  if (!draft.length) return row.member ? '如需收回全部角色，请使用「移除」' : '请至少选择一个角色'
  return ''
}

async function save(row) {
  if (saveBlockedReason(row)) return
  savingSource.value = row.sourceId
  rowErrors[row.sourceId] = ''
  try {
    await permissionsApi.assignMemberRoles(
      row.sourceId,
      access.value.accountType,
      String(access.value.userId),
      {
        roleIds: drafts[row.sourceId],
        expectedRevision: row.member?.revision ?? 0,
      }
    )
    ElMessage.success(`${row.clusterName} 权限已保存`)
    emit('changed')
  } catch (error) {
    rowErrors[row.sourceId] = error?.message || '保存失败'
  } finally {
    savingSource.value = null
  }
  await load()
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `从「${row.clusterName}」移除该账号的全部角色，并删除平台为其创建的数据库登录账号？`,
      '移除数据源访问',
      { type: 'warning', confirmButtonText: '确认移除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  savingSource.value = row.sourceId
  rowErrors[row.sourceId] = ''
  try {
    await permissionsApi.removeMember(
      row.sourceId,
      access.value.accountType,
      String(access.value.userId)
    )
    ElMessage.success(`已从 ${row.clusterName} 移除`)
    emit('changed')
  } catch (error) {
    rowErrors[row.sourceId] = error?.message || '移除失败'
  } finally {
    savingSource.value = null
  }
  await load()
}

async function revokeAll() {
  try {
    await ElMessageBox.confirm(
      `回收该账号在 ${grantedCount.value} 个数据源的全部权限，并删除平台为其创建的数据库登录账号？`,
      '回收全部权限',
      { type: 'warning', confirmButtonText: '确认回收', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  revoking.value = true
  revokeFailures.value = []
  try {
    revokeFailures.value = await revokeAllAccess(access.value)
    if (!revokeFailures.value.length) ElMessage.success('已回收全部数据源权限')
    emit('changed')
  } finally {
    revoking.value = false
  }
  await load()
}

defineExpose({ load, drafts, save, remove, revokeAll, saveBlockedReason })
</script>

<style scoped>
.access-bar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.access-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
  flex: 1 1 360px;
}
.access-actions {
  display: flex;
  gap: 8px;
}
.access-alert {
  margin-bottom: 12px;
}
.source-name {
  font-weight: 500;
  margin-bottom: 4px;
}
.role-list {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}
.role-list :deep(.el-checkbox) {
  height: auto;
  align-items: flex-start;
}
.role-tag {
  margin-left: 6px;
}
.role-summary {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
  white-space: normal;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.row-error {
  color: var(--el-color-danger);
  font-size: 12px;
  margin-top: 4px;
  line-height: 1.4;
}
</style>
