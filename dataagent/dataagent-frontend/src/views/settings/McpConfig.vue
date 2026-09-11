<template>
  <div class="mcp-config">
    <div class="mcp-config__toolbar">
      <div>
        <div class="mcp-config__title">MCP 服务</div>
        <div class="mcp-config__subtitle">
          管理 Model Context Protocol 服务与扩展（共 {{ totalServerCount }} 个服务）
        </div>
      </div>
      <div class="mcp-config__actions">
        <el-input
          v-model="searchKeyword"
          clearable
          placeholder="按服务名、命令或 URL 搜索..."
          class="mcp-config__search"
        />
        <el-button :icon="Refresh" @click="loadMcpServers">刷新</el-button>
        <el-button
          type="primary"
          :icon="Plus"
          @click="openAddDialog"
        >
          添加 MCP 服务
        </el-button>
      </div>
    </div>

    <div v-loading="loading" class="mcp-content">
      <!-- 分组 1：已配置的 MCP 服务 -->
      <section class="mcp-group-section">
        <div class="mcp-group-header">
          <div class="mcp-group-title">
            <span>已配置的 MCP 服务</span>
            <el-tag size="small" type="primary" effect="plain">{{ filteredConfiguredServers.length }}</el-tag>
          </div>
          <div class="mcp-group-desc">手动添加或本地导入的 MCP 服务。</div>
        </div>

        <el-table
          v-if="filteredConfiguredServers.length"
          :data="filteredConfiguredServers"
          border
          class="mcp-table"
        >
          <el-table-column prop="name" label="名称" min-width="160">
            <template #default="{ row }">
              <div class="server-name-cell">
                <span class="server-name">{{ row.name }}</span>
                <el-tag size="small" effect="plain" type="info">
                  {{ row.scope === 'user' ? '用户' : '工作区' }}
                </el-tag>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="来源" width="110">
            <template #default="{ row }">
              <el-tag size="small" type="primary">已配置</el-tag>
            </template>
          </el-table-column>

          <el-table-column label="传输方式" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ (row.transport || 'stdio').toUpperCase() }}</el-tag>
            </template>
          </el-table-column>

          <el-table-column label="命令 / 服务 URL" min-width="260">
            <template #default="{ row }">
              <code v-if="row.transport === 'stdio'" class="command-code">
                {{ row.command }} {{ (row.args || []).join(' ') }}
              </code>
              <span v-else class="url-text">{{ row.url }}</span>
            </template>
          </el-table-column>

          <el-table-column label="授权" width="130" align="center">
            <template #default="{ row }">
              <el-button
                v-if="row.oauth_required"
                size="small"
                type="warning"
                plain
                @click="handleOAuth(row)"
              >
                打开授权
              </el-button>
              <span v-else class="text-muted">无需授权</span>
            </template>
          </el-table-column>

          <el-table-column label="启用" width="90" align="center">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled"
                :loading="updatingServerId === row.server_id"
                @update:model-value="toggleServerEnabled(row, $event)"
              />
            </template>
          </el-table-column>

          <el-table-column label="操作" width="140" align="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openEditDialog(row)">
                编辑
              </el-button>
              <el-button text type="danger" size="small" @click="confirmDeleteServer(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-empty
          v-else
          description="暂无已配置的 MCP 服务"
          :image-size="80"
        />
      </section>

      <!-- 分组 2：插件自带的 MCP 服务 -->
      <section class="mcp-group-section">
        <div class="mcp-group-header">
          <div class="mcp-group-title">
            <span>插件自带的 MCP 服务</span>
            <el-tag size="small" type="info" effect="plain">{{ filteredPluginServers.length }}</el-tag>
          </div>
          <div class="mcp-group-desc">由平台插件或内置模块自带的 MCP 服务。</div>
        </div>

        <el-table
          v-if="filteredPluginServers.length"
          :data="filteredPluginServers"
          border
          class="mcp-table"
        >
          <el-table-column prop="name" label="名称" min-width="160">
            <template #default="{ row }">
              <div class="server-name-cell">
                <span class="server-name">{{ row.name }}</span>
                <el-tag size="small" effect="plain" type="info">系统</el-tag>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="来源" width="110">
            <template #default="{ row }">
              <el-tag size="small" type="info">插件自带</el-tag>
            </template>
          </el-table-column>

          <el-table-column label="传输方式" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ (row.transport || 'stdio').toUpperCase() }}</el-tag>
            </template>
          </el-table-column>

          <el-table-column label="命令 / 服务 URL" min-width="260">
            <template #default="{ row }">
              <code v-if="row.transport === 'stdio'" class="command-code">
                {{ row.command }} {{ (row.args || []).join(' ') }}
              </code>
              <span v-else class="url-text">{{ row.url }}</span>
            </template>
          </el-table-column>

          <el-table-column label="授权" width="130" align="center">
            <template #default="{ row }">
              <el-button
                v-if="row.oauth_required"
                size="small"
                type="warning"
                plain
                @click="handleOAuth(row)"
              >
                打开授权
              </el-button>
              <span v-else class="text-muted">无需授权</span>
            </template>
          </el-table-column>

          <el-table-column label="启用" width="90" align="center">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled"
                :loading="updatingServerId === row.server_id"
                @update:model-value="toggleServerEnabled(row, $event)"
              />
            </template>
          </el-table-column>
        </el-table>

        <el-empty
          v-else
          description="暂无插件自带的 MCP 服务"
          :image-size="80"
        />
      </section>
    </div>

    <!-- 添加 / 编辑 MCP 服务对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEditing ? '编辑 MCP 服务' : '添加 MCP 服务'"
      width="680px"
      :close-on-click-modal="false"
    >
      <el-tabs v-if="!isEditing" v-model="activeAddMode" class="mcp-dialog-tabs">
        <!-- 模式 1：表单模式 (stdio) -->
        <el-tab-pane label="表单模式 (stdio)" name="form_stdio">
          <el-form label-position="top" class="mcp-form">
            <el-row :gutter="16">
              <el-col :xs="24" :sm="12">
                <el-form-item label="作用域" required>
                  <el-radio-group v-model="formStdio.scope">
                    <el-radio value="workspace">工作区</el-radio>
                    <el-radio value="user">用户</el-radio>
                  </el-radio-group>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12">
                <el-form-item label="服务名称" required>
                  <el-input v-model="formStdio.name" placeholder="例如: filesystem" />
                </el-form-item>
              </el-col>
            </el-row>

            <el-form-item label="传输类型">
              <el-tag effect="plain">stdio (标准输入输出)</el-tag>
            </el-form-item>

            <el-form-item label="命令" required>
              <el-input v-model="formStdio.command" placeholder="例如: npx, uvx, python, node" />
            </el-form-item>

            <el-form-item label="命令行参数">
              <el-input
                v-model="formStdio.argsStr"
                type="textarea"
                :rows="3"
                placeholder="例如: -y @modelcontextprotocol/server-filesystem /path/to/dir (空格或换行分隔)"
              />
            </el-form-item>

            <div class="dynamic-list-block">
              <div class="dynamic-list-title">环境变量 (可选)</div>
              <div
                v-for="(item, idx) in formStdio.envList"
                :key="idx"
                class="dynamic-list-row"
              >
                <el-input v-model="item.key" placeholder="变量名，如 API_KEY" class="key-input" />
                <el-input v-model="item.value" placeholder="变量值" class="val-input" />
                <el-button text type="danger" @click="removeEnvItem(idx)">删除</el-button>
              </div>
              <el-button text :icon="Plus" @click="addEnvItem">添加环境变量</el-button>
            </div>
          </el-form>
        </el-tab-pane>

        <!-- 模式 2：远程服务 (http / sse) -->
        <el-tab-pane label="远程服务 (http / sse)" name="remote">
          <el-form label-position="top" class="mcp-form">
            <el-row :gutter="16">
              <el-col :xs="24" :sm="12">
                <el-form-item label="作用域" required>
                  <el-radio-group v-model="formRemote.scope">
                    <el-radio value="workspace">工作区</el-radio>
                    <el-radio value="user">用户</el-radio>
                  </el-radio-group>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12">
                <el-form-item label="服务名称" required>
                  <el-input v-model="formRemote.name" placeholder="例如: remote-indexer" />
                </el-form-item>
              </el-col>
            </el-row>

            <el-form-item label="传输方式" required>
              <el-radio-group v-model="formRemote.transport">
                <el-radio value="sse">SSE (Server-Sent Events)</el-radio>
                <el-radio value="http">HTTP</el-radio>
              </el-radio-group>
            </el-form-item>

            <el-form-item label="服务 URL" required>
              <el-input v-model="formRemote.url" placeholder="例如: https://mcp.example.com/sse" />
            </el-form-item>

            <div class="dynamic-list-block">
              <div class="dynamic-list-title">认证与自定义 Headers (可选)</div>
              <div
                v-for="(item, idx) in formRemote.headerList"
                :key="idx"
                class="dynamic-list-row"
              >
                <el-input v-model="item.key" placeholder="Header 名，如 Authorization" class="key-input" />
                <el-input v-model="item.value" placeholder="Header 值，如 Bearer token" class="val-input" />
                <el-button text type="danger" @click="removeHeaderItem(idx)">删除</el-button>
              </div>
              <el-button text :icon="Plus" @click="addHeaderItem">添加 Header</el-button>
            </div>
          </el-form>
        </el-tab-pane>

        <!-- 模式 3：完整配置 (JSON) -->
        <el-tab-pane label="完整配置 (JSON)" name="json">
          <el-form label-position="top" class="mcp-form">
            <el-form-item label="作用域">
              <el-radio-group v-model="formJson.scope">
                <el-radio value="workspace">工作区</el-radio>
                <el-radio value="user">用户</el-radio>
              </el-radio-group>
            </el-form-item>

            <el-form-item label="JSON 配置内容">
              <div class="json-tips">
                直接粘贴 MCP 配置 JSON，支持 <code>{"server-name": {...}}</code> 和 <code>{"mcpServers": {...}}</code> 两种格式。
              </div>
              <el-input
                v-model="formJson.rawJson"
                type="textarea"
                :rows="11"
                class="json-textarea"
                placeholder='{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path"]
    }
  }
}'
              />
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <!-- 编辑模式直接复用表单 -->
      <div v-else class="edit-form-wrapper">
        <el-form label-position="top">
          <el-row :gutter="16">
            <el-col :xs="24" :sm="12">
              <el-form-item label="作用域">
                <el-radio-group v-model="editForm.scope">
                  <el-radio value="workspace">工作区</el-radio>
                  <el-radio value="user">用户</el-radio>
                </el-radio-group>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12">
              <el-form-item label="服务名称">
                <el-input v-model="editForm.name" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item label="传输类型">
            <el-radio-group v-model="editForm.transport">
              <el-radio value="stdio">stdio</el-radio>
              <el-radio value="http">http</el-radio>
              <el-radio value="sse">sse</el-radio>
            </el-radio-group>
          </el-form-item>

          <template v-if="editForm.transport === 'stdio'">
            <el-form-item label="命令">
              <el-input v-model="editForm.command" />
            </el-form-item>
            <el-form-item label="参数">
              <el-input v-model="editForm.argsStr" type="textarea" :rows="2" />
            </el-form-item>
          </template>

          <template v-else>
            <el-form-item label="服务 URL">
              <el-input v-model="editForm.url" />
            </el-form-item>
          </template>
        </el-form>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="submitting"
            @click="handleSubmit"
          >
            {{ isEditing ? '保存修改' : '确认添加' }}
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { dataagentApi } from '@/api/dataagent'

const loading = ref(false)
const submitting = ref(false)
const updatingServerId = ref('')
const searchKeyword = ref('')
const configuredServers = ref([])
const pluginServers = ref([])

// 对话框状态
const dialogVisible = ref(false)
const isEditing = ref(false)
const editingServerId = ref('')
const activeAddMode = ref('form_stdio')

// 模式 1: stdio
const formStdio = reactive({
  scope: 'workspace',
  name: '',
  command: '',
  argsStr: '',
  envList: []
})

// 模式 2: remote
const formRemote = reactive({
  scope: 'workspace',
  name: '',
  transport: 'sse',
  url: '',
  headerList: []
})

// 模式 3: json
const formJson = reactive({
  scope: 'workspace',
  rawJson: ''
})

// 编辑模式表单
const editForm = reactive({
  scope: 'workspace',
  name: '',
  transport: 'stdio',
  command: '',
  argsStr: '',
  url: ''
})

const totalServerCount = computed(() => configuredServers.value.length + pluginServers.value.length)

const filterServerList = (list) => {
  const keyword = String(searchKeyword.value || '').trim().toLowerCase()
  if (!keyword) return list
  return list.filter((item) => {
    if (String(item.name || '').toLowerCase().includes(keyword)) return true
    if (String(item.command || '').toLowerCase().includes(keyword)) return true
    if (String(item.url || '').toLowerCase().includes(keyword)) return true
    return false
  })
}

const filteredConfiguredServers = computed(() => filterServerList(configuredServers.value))
const filteredPluginServers = computed(() => filterServerList(pluginServers.value))

const parseArgs = (argsStr) => {
  return String(argsStr || '')
    .split(/[\n\s]+/)
    .map((s) => s.trim())
    .filter(Boolean)
}

const listToObj = (list) => {
  const obj = {}
  list.forEach((item) => {
    const k = String(item.key || '').trim()
    if (k) obj[k] = item.value
  })
  return obj
}

const objToList = (obj = {}) => {
  return Object.entries(obj || {}).map(([key, value]) => ({ key, value }))
}

const resetForms = () => {
  formStdio.scope = 'workspace'
  formStdio.name = ''
  formStdio.command = ''
  formStdio.argsStr = ''
  formStdio.envList = []

  formRemote.scope = 'workspace'
  formRemote.name = ''
  formRemote.transport = 'sse'
  formRemote.url = ''
  formRemote.headerList = []

  formJson.scope = 'workspace'
  formJson.rawJson = ''
}

const loadMcpServers = async () => {
  loading.value = true
  try {
    const res = await dataagentApi.listMcpServers()
    configuredServers.value = Array.isArray(res?.configured) ? res.configured : []
    pluginServers.value = Array.isArray(res?.plugin) ? res.plugin : []
  } catch (error) {
    // 接口待后端补齐时提供默认空列表，并避免报错阻塞页面
    configuredServers.value = []
    pluginServers.value = []
  } finally {
    loading.value = false
  }
}

const openAddDialog = () => {
  isEditing.value = false
  editingServerId.value = ''
  resetForms()
  dialogVisible.value = true
}

const openEditDialog = (server) => {
  isEditing.value = true
  editingServerId.value = server.server_id
  editForm.scope = server.scope || 'workspace'
  editForm.name = server.name || ''
  editForm.transport = server.transport || 'stdio'
  editForm.command = server.command || ''
  editForm.argsStr = Array.isArray(server.args) ? server.args.join(' ') : ''
  editForm.url = server.url || ''
  dialogVisible.value = true
}

const addEnvItem = () => {
  formStdio.envList.push({ key: '', value: '' })
}

const removeEnvItem = (idx) => {
  formStdio.envList.splice(idx, 1)
}

const addHeaderItem = () => {
  formRemote.headerList.push({ key: '', value: '' })
}

const removeHeaderItem = (idx) => {
  formRemote.headerList.splice(idx, 1)
}

const handleOAuth = (server) => {
  ElMessageBox.alert(
    `服务「${server.name}」已打开 OAuth 认证流程。若浏览器未弹出授权窗口，请检查弹窗拦截设置。`,
    'OAuth 授权',
    { confirmButtonText: '已知晓' }
  )
}

const toggleServerEnabled = async (server, enabled) => {
  updatingServerId.value = server.server_id
  try {
    await dataagentApi.updateMcpServer(server.server_id, { enabled: Boolean(enabled) })
    server.enabled = Boolean(enabled)
    ElMessage.success(enabled ? `服务「${server.name}」已启用` : `服务「${server.name}」已禁用`)
  } catch (error) {
    server.enabled = !enabled
    ElMessage.error(error?.message || '更新状态失败')
  } finally {
    updatingServerId.value = ''
  }
}

const confirmDeleteServer = async (server) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除 MCP 服务「${server.name}」吗？`,
      '删除服务确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  try {
    await dataagentApi.deleteMcpServer(server.server_id)
    await loadMcpServers()
    ElMessage.success(`服务「${server.name}」已删除`)
  } catch (error) {
    ElMessage.error(error?.message || '删除服务失败')
  }
}

const handleSubmit = async () => {
  submitting.value = true
  try {
    if (isEditing.value) {
      const payload = {
        name: editForm.name,
        scope: editForm.scope,
        transport: editForm.transport,
        command: editForm.command,
        args: parseArgs(editForm.argsStr),
        url: editForm.url
      }
      await dataagentApi.updateMcpServer(editingServerId.value, payload)
      ElMessage.success('MCP 服务已更新')
      dialogVisible.value = false
      await loadMcpServers()
      return
    }

    // 新增模式
    if (activeAddMode.value === 'form_stdio') {
      if (!formStdio.name.trim()) {
        ElMessage.warning('请输入服务名称')
        return
      }
      if (!formStdio.command.trim()) {
        ElMessage.warning('请输入启动命令')
        return
      }
      const payload = {
        name: formStdio.name.trim(),
        scope: formStdio.scope,
        source: 'configured',
        transport: 'stdio',
        command: formStdio.command.trim(),
        args: parseArgs(formStdio.argsStr),
        env: listToObj(formStdio.envList),
        enabled: true,
        oauth_required: false
      }
      await dataagentApi.createMcpServer(payload)
      ElMessage.success('MCP 服务已创建')
    } else if (activeAddMode.value === 'remote') {
      if (!formRemote.name.trim()) {
        ElMessage.warning('请输入服务名称')
        return
      }
      if (!formRemote.url.trim()) {
        ElMessage.warning('请输入服务 URL')
        return
      }
      const payload = {
        name: formRemote.name.trim(),
        scope: formRemote.scope,
        source: 'configured',
        transport: formRemote.transport,
        url: formRemote.url.trim(),
        headers: listToObj(formRemote.headerList),
        enabled: true,
        oauth_required: false
      }
      await dataagentApi.createMcpServer(payload)
      ElMessage.success('远程 MCP 服务已添加')
    } else if (activeAddMode.value === 'json') {
      if (!formJson.rawJson.trim()) {
        ElMessage.warning('请粘贴 JSON 配置')
        return
      }
      let parsed
      try {
        parsed = JSON.parse(formJson.rawJson.trim())
      } catch (err) {
        ElMessage.error('JSON 格式有误，请检查语法')
        return
      }

      // 接受 {"mcpServers": {...}} 或 {"server-name": {...}} 格式
      if (parsed.mcpServers && typeof parsed.mcpServers === 'object') {
        await dataagentApi.importMcpServers({ scope: formJson.scope, ...parsed })
      } else {
        const firstKey = Object.keys(parsed)[0]
        const serverConfig = parsed[firstKey]
        if (serverConfig && typeof serverConfig === 'object' && (serverConfig.command || serverConfig.url)) {
          const payload = {
            name: firstKey,
            scope: formJson.scope,
            source: 'configured',
            transport: serverConfig.transport || (serverConfig.command ? 'stdio' : 'sse'),
            command: serverConfig.command || '',
            args: serverConfig.args || [],
            env: serverConfig.env || {},
            url: serverConfig.url || '',
            headers: serverConfig.headers || {},
            enabled: true,
            oauth_required: Boolean(serverConfig.oauth_required)
          }
          await dataagentApi.createMcpServer(payload)
        } else {
          await dataagentApi.importMcpServers({ scope: formJson.scope, mcpServers: parsed })
        }
      }
      ElMessage.success('MCP 配置已导入')
    }

    dialogVisible.value = false
    await loadMcpServers()
  } catch (error) {
    ElMessage.error(error?.message || '操作失败，请重试')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await loadMcpServers()
})
</script>

<style scoped>
.mcp-config {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-width: 0;
}

.mcp-config__toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.mcp-config__title {
  font-size: 18px;
  font-weight: 600;
  color: #0f172a;
}

.mcp-config__subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: #64748b;
  line-height: 1.6;
}

.mcp-config__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.mcp-config__search {
  width: 260px;
}

.mcp-content {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.mcp-group-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.mcp-group-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.mcp-group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
}

.mcp-group-desc {
  font-size: 12px;
  color: #64748b;
}

.mcp-table {
  min-width: 0;
}

.server-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.server-name {
  font-weight: 600;
  color: #0f172a;
}

.command-code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  padding: 2px 6px;
  border-radius: 4px;
  background: #f1f5f9;
  color: #0f172a;
  word-break: break-all;
}

.url-text {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  color: #2563eb;
  word-break: break-all;
}

.text-muted {
  font-size: 12px;
  color: #94a3b8;
}

.mcp-dialog-tabs {
  margin-top: -10px;
}

.mcp-form {
  padding-top: 8px;
}

.dynamic-list-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
  padding: 12px;
  border: 1px dashed #cbd5e1;
  border-radius: 6px;
  background: #f8fafc;
}

.dynamic-list-title {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
}

.dynamic-list-row {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 8px;
  align-items: center;
}

.json-tips {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 8px;
  line-height: 1.5;
}

.json-tips code {
  font-family: monospace;
  background: #e2e8f0;
  padding: 1px 4px;
  border-radius: 3px;
}

.json-textarea :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.edit-form-wrapper {
  padding-top: 8px;
}

@media (max-width: 768px) {
  .mcp-config__toolbar,
  .mcp-config__actions {
    flex-direction: column;
    align-items: stretch;
  }

  .mcp-config__search {
    width: 100%;
  }

  .dynamic-list-row {
    grid-template-columns: 1fr;
  }
}
</style>
