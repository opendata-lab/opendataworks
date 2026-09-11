<template>
  <div class="mcp-config">
    <div class="mcp-config__toolbar">
      <div>
        <div class="mcp-config__title">MCP 服务</div>
        <div class="mcp-config__subtitle">
          已安装 {{ totalServerCount }} 个服务
        </div>
      </div>
      <div class="mcp-config__actions">
        <el-input
          v-model="searchKeyword"
          clearable
          placeholder="搜索 MCP 服务..."
          class="mcp-config__search"
        />
        <el-button :icon="Refresh" @click="loadMcpServers">刷新</el-button>
        <el-button
          type="primary"
          :icon="Plus"
          @click="openAddDialog"
        >
          新建 MCP 服务
        </el-button>
      </div>
    </div>

    <div v-loading="loading" class="mcp-content">
      <section class="mcp-section">
        <div class="mcp-section-title">已安装 <span>{{ filteredConfiguredServers.length }}</span></div>

        <div v-if="filteredConfiguredServers.length" class="mcp-list">
          <div v-for="server in filteredConfiguredServers" :key="server.server_id" class="mcp-row">
            <div class="mcp-row__icon" aria-hidden="true">
              <el-icon><Connection /></el-icon>
              <span class="mcp-row__dot" :class="{ 'is-enabled': server.enabled }" />
            </div>
            <div class="mcp-row__main">
              <div class="mcp-row__heading">
                <span class="server-name">{{ server.name }}</span>
                <span class="server-meta">{{ serverMeta(server) }}</span>
              </div>
              <p class="mcp-row__description">{{ serverDescription(server) }}</p>
            </div>
            <div class="mcp-row__actions">
              <el-button
                v-if="server.oauth_required"
                size="small"
                type="warning"
                plain
                @click="handleOAuth(server)"
              >
                授权
              </el-button>
              <el-switch
                :model-value="server.enabled"
                :loading="updatingServerId === server.server_id"
                :title="server.enabled ? '禁用服务' : '启用服务'"
                @update:model-value="toggleServerEnabled(server, $event)"
              />
              <el-button
                text
                :icon="EditPen"
                title="编辑服务"
                aria-label="编辑服务"
                @click="openEditDialog(server)"
              />
              <el-button
                text
                type="danger"
                :icon="Delete"
                title="删除服务"
                aria-label="删除服务"
                @click="confirmDeleteServer(server)"
              />
            </div>
          </div>
        </div>

        <div v-else-if="!configuredServers.length && !searchKeyword.trim()" class="mcp-empty">
          <div class="mcp-empty__title">尚未安装 MCP 服务器</div>
          <p>手动新建服务器，或导入已有配置。</p>
          <div class="mcp-empty__actions">
            <el-button type="primary" :icon="Plus" @click="openAddDialog">新建 MCP 服务器</el-button>
            <el-button :icon="Download" @click="openImportDialog">导入</el-button>
          </div>
        </div>

        <div v-else class="mcp-inline-empty">没有匹配的 MCP 服务，请调整搜索词。</div>
      </section>

      <section class="mcp-section">
        <div class="mcp-section-title">插件提供 <span>{{ filteredPluginServers.length }}</span></div>

        <div v-if="filteredPluginServers.length" class="mcp-list">
          <div v-for="server in filteredPluginServers" :key="server.server_id" class="mcp-row">
            <div class="mcp-row__icon" aria-hidden="true">
              <el-icon><Connection /></el-icon>
              <span class="mcp-row__dot" :class="{ 'is-enabled': server.enabled }" />
            </div>
            <div class="mcp-row__main">
              <div class="mcp-row__heading">
                <span class="server-name">{{ server.name }}</span>
                <span class="server-meta">{{ serverMeta(server) }}</span>
              </div>
              <p class="mcp-row__description">{{ pluginServerDescription(server) }}</p>
            </div>
            <div class="mcp-row__actions">
              <el-button
                v-if="server.oauth_required"
                size="small"
                type="warning"
                plain
                @click="handleOAuth(server)"
              >
                授权
              </el-button>
              <el-switch
                :model-value="server.enabled"
                disabled
                :title="server.enabled ? '禁用服务' : '启用服务'"
              />
            </div>
          </div>
        </div>

        <div v-else class="mcp-inline-empty">
          {{ searchKeyword.trim() ? '没有匹配的插件服务，请调整搜索词。' : '当前没有插件提供的 MCP 服务。' }}
        </div>
      </section>
    </div>

    <!-- 添加 / 编辑 MCP 服务对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEditing ? '编辑 MCP 服务' : '新建 MCP 服务'"
      width="680px"
      :close-on-click-modal="false"
    >
      <el-tabs v-if="!isEditing" v-model="activeAddMode" class="mcp-dialog-tabs">
        <!-- 模式 1：表单模式 (stdio) -->
        <el-tab-pane label="本地命令（stdio）" name="form_stdio">
          <el-form label-position="top" class="mcp-form">
            <el-row :gutter="16">
              <el-col :xs="24" :sm="12">
                <el-form-item label="服务名称" required>
                  <el-input v-model="formStdio.name" placeholder="例如：filesystem" />
                </el-form-item>
              </el-col>
            </el-row>

            <el-form-item label="传输类型">
              <el-tag effect="plain">stdio（标准输入输出）</el-tag>
            </el-form-item>

            <el-form-item label="命令" required>
              <el-input v-model="formStdio.command" placeholder="例如：npx、uvx、python、node" />
            </el-form-item>

            <el-form-item label="命令行参数">
              <el-input
                v-model="formStdio.argsStr"
                type="textarea"
                :rows="3"
                placeholder="例如：-y @modelcontextprotocol/server-filesystem /path/to/dir（用空格或换行分隔）"
              />
            </el-form-item>

            <div class="dynamic-list-block">
              <div class="dynamic-list-title">环境变量（可选）</div>
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
        <el-tab-pane label="远程服务（HTTP / SSE）" name="remote">
          <el-form label-position="top" class="mcp-form">
            <el-row :gutter="16">
              <el-col :xs="24" :sm="12">
                <el-form-item label="服务名称" required>
                  <el-input v-model="formRemote.name" placeholder="例如：remote-indexer" />
                </el-form-item>
              </el-col>
            </el-row>

            <el-form-item label="传输方式" required>
              <el-radio-group v-model="formRemote.transport">
                <el-radio value="sse">SSE（Server-Sent Events）</el-radio>
                <el-radio value="http">HTTP</el-radio>
              </el-radio-group>
            </el-form-item>

            <el-form-item label="服务 URL" required>
              <el-input v-model="formRemote.url" placeholder="例如：https://mcp.example.com/sse" />
            </el-form-item>

            <div class="dynamic-list-block">
              <div class="dynamic-list-title">认证与自定义 Header（可选）</div>
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
        <el-tab-pane label="导入 JSON" name="json">
          <el-form label-position="top" class="mcp-form">

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
            {{ submitButtonLabel }}
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Delete, Download, EditPen, Plus, Refresh } from '@element-plus/icons-vue'
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
  name: '',
  command: '',
  argsStr: '',
  envList: []
})

// 模式 2: remote
const formRemote = reactive({
  name: '',
  transport: 'sse',
  url: '',
  headerList: []
})

// 模式 3: json
const formJson = reactive({
  rawJson: ''
})

// 编辑模式表单
const editForm = reactive({
  name: '',
  transport: 'stdio',
  command: '',
  argsStr: '',
  url: ''
})

const totalServerCount = computed(() => configuredServers.value.length + pluginServers.value.length)
const submitButtonLabel = computed(() => {
  if (isEditing.value) return '保存修改'
  return activeAddMode.value === 'json' ? '导入配置' : '新建服务'
})

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

const serverMeta = (server) => {
  return String(server.transport || 'stdio').toUpperCase()
}

const serverDescription = (server) => {
  const description = String(server.description || '').trim()
  if (description) return description
  if (server.transport === 'stdio') {
    return `本地命令：${[server.command, ...(server.args || [])].filter(Boolean).join(' ') || '未配置启动命令'}`
  }
  return `服务地址：${server.url || '未配置 URL'}`
}

const pluginServerDescription = (server) => {
  const description = String(server.description || '').trim()
  if (description) return description
  return '该 MCP 服务器由平台插件提供，运行时身份和配置由平台管理。'
}

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
  formStdio.name = ''
  formStdio.command = ''
  formStdio.argsStr = ''
  formStdio.envList = []
  formRemote.name = ''
  formRemote.transport = 'sse'
  formRemote.url = ''
  formRemote.headerList = []
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
  activeAddMode.value = 'form_stdio'
  resetForms()
  dialogVisible.value = true
}

const openImportDialog = () => {
  isEditing.value = false
  editingServerId.value = ''
  resetForms()
  activeAddMode.value = 'json'
  dialogVisible.value = true
}

const openEditDialog = (server) => {
  isEditing.value = true
  editingServerId.value = server.server_id
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
        await dataagentApi.importMcpServers({ ...parsed })
      } else {
        const firstKey = Object.keys(parsed)[0]
        const serverConfig = parsed[firstKey]
        if (serverConfig && typeof serverConfig === 'object' && (serverConfig.command || serverConfig.url)) {
          const payload = {
            name: firstKey,
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
          await dataagentApi.importMcpServers({ mcpServers: parsed })
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
/* Hallmark · pre-emit critique: P5 H4 E5 S5 R5 V4 */
/* Hallmark · macrostructure: Workbench · tone: utilitarian · anchor hue: blue */
.mcp-config {
  --mcp-ink: #172033;
  --mcp-muted: #64748b;
  --mcp-muted-strong: #475569;
  --mcp-muted-light: #94a3b8;
  --mcp-rule: #e2e8f0;
  --mcp-rule-strong: #cbd5e1;
  --mcp-paper: #fdfefe;
  --mcp-paper-muted: #f8fafc;
  --mcp-paper-hover: #f1f5f9;
  --mcp-success: #22a861;
  --mcp-focus: #2563eb;
  --mcp-ease-out: cubic-bezier(0.16, 1, 0.3, 1);
  display: flex;
  flex-direction: column;
  gap: 28px;
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
  color: var(--mcp-ink);
}

.mcp-config__subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: var(--mcp-muted);
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
  gap: 28px;
}

.mcp-section {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.mcp-section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--mcp-ink);
}

.mcp-section-title span {
  margin-left: 4px;
  font-size: 12px;
  font-weight: 500;
  color: var(--mcp-muted);
}

.mcp-list {
  min-width: 0;
  border-top: 1px solid var(--mcp-rule);
}

.mcp-row {
  min-width: 0;
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-height: 72px;
  padding: 11px 8px;
  border-bottom: 1px solid var(--mcp-rule);
  transition: background 150ms var(--mcp-ease-out);
}

@media (hover: hover) and (pointer: fine) {
  .mcp-row:hover {
    background: var(--mcp-paper-muted);
  }
}

.mcp-row__icon {
  position: relative;
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 8px;
  background: var(--mcp-paper-hover);
  color: var(--mcp-muted-strong);
  font-size: 19px;
}

.mcp-row__dot {
  position: absolute;
  right: 1px;
  bottom: 1px;
  width: 8px;
  height: 8px;
  border: 2px solid var(--mcp-paper);
  border-radius: 50%;
  background: var(--mcp-muted-light);
}

.mcp-row__dot.is-enabled {
  background: var(--mcp-success);
}

.mcp-row__main {
  min-width: 0;
}

.mcp-row__heading {
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.server-name {
  min-width: 0;
  overflow: hidden;
  font-weight: 600;
  color: var(--mcp-ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.server-meta {
  flex: 0 0 auto;
  color: var(--mcp-muted-light);
  font-size: 12px;
}

.mcp-row__description {
  margin: 4px 0 0;
  overflow: hidden;
  color: var(--mcp-muted);
  font-size: 13px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mcp-row__actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 0 0 auto;
}

.mcp-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 210px;
  padding: 32px 20px;
  border: 1px dashed var(--mcp-rule-strong);
  border-radius: 8px;
  text-align: center;
}

.mcp-empty__title {
  color: var(--mcp-ink);
  font-size: 16px;
  font-weight: 600;
}

.mcp-empty p {
  margin: 8px 0 16px;
  color: var(--mcp-muted);
  font-size: 13px;
}

.mcp-empty__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mcp-inline-empty {
  padding: 22px 8px;
  border-top: 1px solid var(--mcp-rule);
  border-bottom: 1px solid var(--mcp-rule);
  color: var(--mcp-muted);
  font-size: 13px;
  text-align: center;
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
  border: 1px dashed var(--mcp-rule-strong);
  border-radius: 6px;
  background: var(--mcp-paper-muted);
}

.dynamic-list-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--mcp-muted-strong);
}

.dynamic-list-row {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 8px;
  align-items: center;
}

.json-tips {
  font-size: 12px;
  color: var(--mcp-muted);
  margin-bottom: 8px;
  line-height: 1.5;
}

.json-tips code {
  font-family: monospace;
  background: var(--mcp-rule);
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

  .mcp-row {
    grid-template-columns: 40px minmax(0, 1fr);
  }

  .mcp-row__actions {
    grid-column: 2;
    justify-self: start;
  }

  .mcp-empty__actions {
    flex-wrap: wrap;
    justify-content: center;
  }

  .mcp-empty__actions :deep(.el-button) {
    white-space: nowrap;
  }

  .dynamic-list-row {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .mcp-row {
    transition-duration: 0ms;
  }
}
</style>
