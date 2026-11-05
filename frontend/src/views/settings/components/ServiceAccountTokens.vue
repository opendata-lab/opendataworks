<template>
  <el-dialog v-model="visible" title="管理 Token" width="760px" :close-on-click-modal="false" :close-on-press-escape="!issuing" :show-close="!issuing" @closed="clearSecret">
    <p class="context">{{ account?.nickname || account?.username }} <span>· 服务账号</span></p>
    <div v-if="generatedToken" class="token-reveal" data-testid="token-reveal">
      <h3>Token 已创建</h3>
      <p>明文仅显示这一次，请保存到调用方的安全配置中。关闭后无法再次查看。</p>
      <el-input :model-value="generatedToken" type="textarea" :rows="2" readonly aria-label="新创建的Token" />
      <div class="reveal-actions">
        <el-button type="primary" @click="copyToken">复制 Token</el-button>
        <el-button @click="clearSecret">我已保存</el-button>
      </div>
      <div class="mcp-example" data-testid="mcp-example">
        <p>在调用方（如 DataAgent）的 MCP 配置中填写：</p>
        <pre>地址：http://&lt;后端主机&gt;:8080/api/mcp/
请求头：Authorization: Bearer {{ generatedToken }}</pre>
        <p class="muted">MCP 由后端服务直接提供，门户地址不转发 /api/mcp；端口以部署配置为准。</p>
      </div>
    </div>
    <el-form v-else class="issue-form" :model="draft" label-width="76px" @submit.prevent="createToken">
      <div class="issue-fields">
        <el-form-item label="Token 名称" required>
          <el-input v-model="draft.name" maxlength="100" placeholder="例如：问数生产环境" aria-label="Token名称" :disabled="issuing || account?.enabled === false" />
        </el-form-item>
        <el-form-item label="有效期" required>
          <el-input-number v-model="draft.expiresInDays" :min="1" :max="365" :precision="0" aria-label="Token有效天数" :disabled="issuing || account?.enabled === false" />
          <span class="days">天</span>
        </el-form-item>
        <el-button type="primary" :loading="issuing" :disabled="issuing || account?.enabled === false" @click="createToken">创建 Token</el-button>
      </div>
      <p v-if="account?.enabled === false" class="muted">账号已停用，不能创建 Token；已有 Token 均不能认证，仍可在下方撤销。</p>
      <p v-if="issueError" role="alert" class="error">{{ issueError }}</p>
    </el-form>
    <div class="list-toolbar"><span>已签发 Token</span><el-button text :loading="loading" @click="loadTokens">刷新</el-button></div>
    <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon />
    <el-table v-else v-loading="loading" :data="tokens" border empty-text="暂无 Token，创建后可配置到外部应用" style="width:100%">
      <el-table-column prop="name" label="名称" min-width="130" show-overflow-tooltip />
      <el-table-column label="标识" min-width="145"><template #default="{ row }">{{ row.prefix }}…</template></el-table-column>
      <el-table-column label="状态" width="86"><template #default="{ row }"><el-tag :type="tokenState(row).type" size="small">{{ tokenState(row).label }}</el-tag></template></el-table-column>
      <el-table-column label="到期时间" min-width="155"><template #default="{ row }">{{ formatTime(row.expiresAt) }}</template></el-table-column>
      <el-table-column label="最后使用" min-width="155"><template #default="{ row }">{{ formatTime(row.lastUsedAt) }}</template></el-table-column>
      <el-table-column label="操作" width="76" fixed="right"><template #default="{ row }"><el-button text type="danger" size="small" :disabled="!!row.revokedAt || revoking !== null" :loading="revoking === row.id" @click="revokeToken(row)">撤销</el-button></template></el-table-column>
    </el-table>
    <template #footer>
      <el-button v-if="showAccessLink" type="primary" plain :disabled="issuing" data-testid="configure-access-btn" @click="emit('configure-access', account)">配置数据权限</el-button>
      <el-button :disabled="issuing" @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { usersApi } from '@/api/users'

const props = defineProps({ modelValue: Boolean, account: { type: Object, default: null }, showAccessLink: Boolean })
const emit = defineEmits(['update:modelValue', 'configure-access'])
const DEFAULT_TOKEN_NAME = '默认 Token'
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const tokens = ref([])
const loading = ref(false)
const issuing = ref(false)
const revoking = ref(null)
const listError = ref('')
const issueError = ref('')
const generatedToken = ref('')
const draft = reactive({ name: '', expiresInDays: 90 })
let generation = 0

const formatTime = value => value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'
const tokenState = row => row.revokedAt ? { label: '已撤销', type: 'info' }
  : dayjs(row.expiresAt).isBefore(dayjs()) ? { label: '已过期', type: 'warning' }
    : props.account?.enabled === false ? { label: '账号停用', type: 'info' } : { label: '有效', type: 'success' }
function clearSecret() { generatedToken.value = ''; issueError.value = '' }
async function loadTokens() {
  if (!props.account?.id || !props.modelValue) return
  const current = generation
  loading.value = true; listError.value = ''
  try {
    const rows = await usersApi.listTokens(props.account.id)
    if (current === generation) {
      tokens.value = Array.isArray(rows) ? rows : []
      // First token for this account: prefill a name so issuing is one click.
      if (!tokens.value.length && !draft.name) draft.name = DEFAULT_TOKEN_NAME
    }
  } catch (error) {
    if (current === generation) listError.value = error?.message || '加载 Token 失败，请刷新重试'
  } finally { if (current === generation) loading.value = false }
}
async function createToken() {
  if (issuing.value || !props.modelValue || !props.account?.id || props.account.enabled === false) return
  issueError.value = ''
  if (!draft.name.trim()) { issueError.value = '请输入 Token 名称'; return }
  if (!Number.isInteger(draft.expiresInDays) || draft.expiresInDays < 1 || draft.expiresInDays > 365) { issueError.value = '有效期为 1～365 天'; return }
  const current = generation
  issuing.value = true
  try {
    const result = await usersApi.createToken(props.account.id, { name: draft.name.trim(), expiresInDays: draft.expiresInDays })
    if (current !== generation) return
    if (!result?.token) { issueError.value = '未返回 Token 明文，请查看列表核对记录后重试'; await loadTokens(); return }
    generatedToken.value = result.token
    draft.name = ''
    await loadTokens()
  } catch (error) { if (current === generation) issueError.value = error?.message || '创建 Token 失败' }
  finally { if (current === generation) issuing.value = false }
}
async function copyToken() {
  try { await navigator.clipboard.writeText(generatedToken.value); ElMessage.success('Token 已复制') }
  catch { ElMessage.warning('复制失败，请手动复制并妥善保存') }
}
async function revokeToken(row) {
  if (row.revokedAt || revoking.value !== null) return
  const current = generation
  const accountId = props.account?.id
  try { await ElMessageBox.confirm(`撤销「${row.name}」后，使用此 Token 的应用将无法继续访问。`, '撤销 Token', { type: 'warning', confirmButtonText: '确认撤销', cancelButtonText: '取消' }) }
  catch { return }
  if (current !== generation || !props.modelValue || !accountId) return
  revoking.value = row.id; listError.value = ''
  try {
    await usersApi.revokeToken(accountId, row.id)
    if (current !== generation) return
    ElMessage.success('Token 已撤销'); await loadTokens()
  } catch (error) { if (current === generation) listError.value = error?.message || '撤销失败，请刷新核对状态' }
  finally { if (current === generation) revoking.value = null }
}
watch(() => [props.modelValue, props.account?.id], () => {
  generation++; clearSecret(); tokens.value = []; draft.name = ''; draft.expiresInDays = 90
  issuing.value = false; revoking.value = null; loading.value = false; listError.value = ''
  if (props.modelValue) loadTokens()
}, { immediate: true })
onBeforeUnmount(() => { generation++; clearSecret() })
</script>

<style scoped>
.context { margin: 0 0 20px; font-weight: 600; }
.context span, .muted { color: var(--el-text-color-secondary); font-weight: 400; }
.issue-fields { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
.issue-fields .el-form-item { margin-bottom: 8px; }
.issue-fields .el-form-item:first-child { flex: 1; min-width: 230px; }
.days { margin-left: 6px; }
.list-toolbar { display: flex; align-items: center; justify-content: space-between; margin: 16px 0 8px; font-weight: 600; }
.token-reveal { margin-bottom: 24px; }
.token-reveal h3 { font-size: 16px; margin: 0 0 8px; }
.token-reveal p { line-height: 1.6; }
.reveal-actions { display: flex; gap: 8px; margin-top: 12px; }
.error { color: var(--el-color-danger); }
.mcp-example { margin-top: 16px; }
.mcp-example pre { margin: 8px 0; padding: 10px 12px; background: var(--el-fill-color-light); border-radius: 4px; white-space: pre-wrap; word-break: break-all; font-size: 12px; line-height: 1.6; }
</style>
