<template>
  <el-dialog v-model="visible" :title="`${cluster?.clusterName || ''} · 账号映射`" width="760px">
    <el-alert title="账号由 DBA 建好并授权；智能体账号必须只读。缺少映射将拒绝访问。" type="info" :closable="false" />
    <el-table :data="accounts" border class="account-list" v-loading="loading">
      <el-table-column prop="subjectType" label="主体类型" width="100" />
      <el-table-column prop="subjectId" label="主体 ID" min-width="150" />
      <el-table-column prop="username" label="数据库账号" min-width="140" />
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button text @click="edit(row)">编辑</el-button>
          <el-button text type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <h3>{{ form.id ? '编辑映射' : '新增映射' }}</h3>
    <el-form label-width="110px" :model="form" @submit.prevent="save">
      <el-form-item label="主体类型">
        <el-select v-model="form.subjectType" @change="form.subjectId = ''">
          <el-option label="人" value="USER" /><el-option label="智能体" value="AGENT" />
        </el-select>
      </el-form-item>
      <el-form-item label="主体">
        <el-select v-model="form.subjectId" filterable placeholder="选择主体" style="width: 100%">
          <el-option v-for="item in subjects" :key="item.id" :value="String(item.id)" :label="item.label" />
        </el-select>
      </el-form-item>
      <el-form-item label="数据库用户名"><el-input v-model="form.username" autocomplete="off" /></el-form-item>
      <el-form-item label="数据库密码"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" :placeholder="form.id ? '留空保留原密码' : '输入数据库密码'" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="reset">新增映射</el-button>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :loading="saving" @click="save">测试连接并保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dorisClusterApi } from '@/api/doris'
import { nl2sqlApi } from '@/api/nl2sql'

const props = defineProps({ modelValue: Boolean, cluster: Object })
const emit = defineEmits(['update:modelValue'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const loading = ref(false)
const saving = ref(false)
const accounts = ref([])
const users = ref([])
const agents = ref([])
const form = reactive({ id: null, subjectType: 'USER', subjectId: '', username: '', password: '' })
const subjects = computed(() => form.subjectType === 'USER'
  ? users.value.map(user => ({ id: user.id, label: user.username }))
  : agents.value.map(agent => ({ id: agent.agent_id, label: agent.name || agent.display_name || agent.agent_id })))
const reset = () => Object.assign(form, { id: null, subjectType: 'USER', subjectId: '', username: '', password: '' })
const edit = row => Object.assign(form, row, { password: '' })
const load = async () => { accounts.value = await dorisClusterApi.accounts(props.cluster.id) }
watch(visible, async value => {
  if (!value) return
  reset()
  loading.value = true
  try {
    const [rows, people, assistants] = await Promise.all([
      dorisClusterApi.accounts(props.cluster.id), dorisClusterApi.accountUsers(props.cluster.id), nl2sqlApi.listAgents()
    ])
    accounts.value = rows
    users.value = people
    agents.value = assistants
  } catch (error) {
    ElMessage.error(error?.response?.data?.detail || error.message || '无法读取账号映射主体')
  } finally { loading.value = false }
})
const save = async () => {
  if (!form.subjectId || !form.username) { ElMessage.warning('请选择主体并填写数据库用户名'); return }
  saving.value = true
  try {
    const payload = { ...form }
    if (form.id && !form.password) delete payload.password
    await dorisClusterApi.saveAccount(props.cluster.id, payload)
    await load()
    reset()
    ElMessage.success('连接校验通过，映射已保存')
  } finally { saving.value = false }
}
const remove = async row => {
  await ElMessageBox.confirm('删除后该主体将立即无法使用此数据源，是否继续？', '删除账号映射')
  await dorisClusterApi.removeAccount(props.cluster.id, row.id)
  await load()
}
</script>

<style scoped>
.account-list { margin-top: 16px; }
h3 { margin: 20px 0 12px; font-size: 14px; }
</style>
