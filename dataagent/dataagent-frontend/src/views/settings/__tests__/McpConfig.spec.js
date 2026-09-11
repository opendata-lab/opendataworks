import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const apiMocks = vi.hoisted(() => ({
  listMcpServers: vi.fn(),
  createMcpServer: vi.fn(),
  updateMcpServer: vi.fn(),
  deleteMcpServer: vi.fn(),
  importMcpServers: vi.fn()
}))

const messageMocks = vi.hoisted(() => ({
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn()
}))

const messageBoxMocks = vi.hoisted(() => ({
  confirm: vi.fn(),
  alert: vi.fn()
}))

vi.mock('@/api/dataagent', () => ({
  dataagentApi: apiMocks
}))

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal()),
  ElMessage: messageMocks,
  ElMessageBox: messageBoxMocks
}))

import McpConfig from '../McpConfig.vue'

const stubs = {
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-button': {
    props: ['disabled', 'loading'],
    template: '<button :disabled="disabled"><slot /></button>'
  },
  'el-switch': {
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    template: '<button class="el-switch-stub" :disabled="disabled" @click="$emit(\'update:modelValue\', !modelValue)"><slot /></button>'
  },
  'el-tag': { template: '<span class="el-tag-stub"><slot /></span>' },
  'el-empty': { template: '<div class="el-empty-stub"><slot /></div>' },

  'el-dialog': {
    props: ['modelValue', 'title'],
    template: '<div v-if="modelValue" class="el-dialog-stub"><h3>{{ title }}</h3><slot /><slot name="footer" /></div>'
  },
  'el-tabs': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<div class="el-tabs-stub"><slot /></div>'
  },
  'el-tab-pane': {
    props: ['label', 'name'],
    template: '<div class="el-tab-pane-stub"><slot /></div>'
  },
  'el-radio-group': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<div class="el-radio-group-stub"><slot /></div>'
  },
  'el-radio': {
    props: ['value'],
    template: '<label><slot /></label>'
  },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-row': { template: '<div><slot /></div>' },
  'el-col': { template: '<div><slot /></div>' }
}

const basePayload = () => ({
  configured: [
    {
      server_id: 'srv-filesystem',
      name: 'filesystem',
      source: 'configured',
      transport: 'stdio',
      command: 'npx',
      args: ['-y', '@modelcontextprotocol/server-filesystem', '/data'],
      env: { DIR_ROOT: '/data' },
      url: '',
      headers: {},
      enabled: true,
      oauth_required: false
    },
    {
      server_id: 'srv-github-remote',
      name: 'github-remote',
      source: 'configured',
      transport: 'sse',
      command: '',
      args: [],
      env: {},
      url: 'https://mcp.github.com/sse',
      headers: { Authorization: 'Bearer token123' },
      enabled: false,
      oauth_required: true
    }
  ],
  plugin: [
    {
      server_id: 'srv-portal-internal',
      name: 'portal-tools',
      source: 'plugin',
      transport: 'stdio',
      command: 'python',
      args: ['-m', 'opendataworks.mcp_portal'],
      env: {},
      url: '',
      headers: {},
      enabled: true,
      oauth_required: false
    }
  ]
})

const mountConfig = () => mount(McpConfig, {
  global: { stubs }
})

describe('McpConfig', () => {
  beforeEach(() => {
    apiMocks.listMcpServers.mockReset()
    apiMocks.createMcpServer.mockReset()
    apiMocks.updateMcpServer.mockReset()
    apiMocks.deleteMcpServer.mockReset()
    apiMocks.importMcpServers.mockReset()
    messageMocks.success.mockReset()
    messageMocks.warning.mockReset()
    messageMocks.error.mockReset()
    messageBoxMocks.confirm.mockReset()
    messageBoxMocks.alert.mockReset()

    apiMocks.listMcpServers.mockResolvedValue(basePayload())
    apiMocks.createMcpServer.mockResolvedValue({ server_id: 'new-srv' })
    apiMocks.updateMcpServer.mockResolvedValue({ ok: true })
    apiMocks.deleteMcpServer.mockResolvedValue({ ok: true })
    apiMocks.importMcpServers.mockResolvedValue({ imported: 1 })
  })

  it('renders both configured and plugin MCP server groups with their details', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('已安装 2')
    expect(text).toContain('插件提供 1')
    expect(text).toContain('filesystem')
    expect(text).toContain('github-remote')
    expect(text).toContain('portal-tools')
    expect(text).toContain('https://mcp.github.com/sse')
    expect(text).toContain('npx -y @modelcontextprotocol/server-filesystem /data')
    expect(text).toContain('该 MCP 服务器由平台插件提供，运行时身份和配置由平台管理。')
    expect(wrapper.findAll('.mcp-row')).toHaveLength(3)
    expect(wrapper.findAll('.mcp-list')).toHaveLength(2)
    expect(wrapper.find('.mcp-table').exists()).toBe(false)
  })

  it('renders OAuth authorization button for servers requiring OAuth and opens alert on click', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    expect(wrapper.text()).toContain('授权')
    const oauthServer = wrapper.vm.filteredConfiguredServers.find((s) => s.oauth_required)
    wrapper.vm.handleOAuth(oauthServer)

    expect(messageBoxMocks.alert).toHaveBeenCalledWith(
      expect.stringContaining('github-remote'),
      'OAuth 授权',
      expect.any(Object)
    )
  })

  it('toggles server enabled state via updateMcpServer api', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    const server = wrapper.vm.filteredConfiguredServers[0]
    expect(server.enabled).toBe(true)

    await wrapper.vm.toggleServerEnabled(server, false)
    await flushPromises()

    expect(apiMocks.updateMcpServer).toHaveBeenCalledWith('srv-filesystem', { enabled: false })
    expect(messageMocks.success).toHaveBeenCalledWith('服务「filesystem」已禁用')
  })

  it('confirms and deletes server via deleteMcpServer api', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    messageBoxMocks.confirm.mockResolvedValue('confirm')
    const server = wrapper.vm.filteredConfiguredServers[0]

    await wrapper.vm.confirmDeleteServer(server)
    await flushPromises()

    expect(messageBoxMocks.confirm).toHaveBeenCalledWith(
      expect.stringContaining('filesystem'),
      '删除服务确认',
      expect.any(Object)
    )
    expect(apiMocks.deleteMcpServer).toHaveBeenCalledWith('srv-filesystem')
    expect(messageMocks.success).toHaveBeenCalledWith('服务「filesystem」已删除')
  })

  it('adds stdio server via form mode', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    wrapper.vm.openAddDialog()
    expect(wrapper.vm.dialogVisible).toBe(true)
    expect(wrapper.vm.activeAddMode).toBe('form_stdio')

    wrapper.vm.formStdio.name = 'my-sqlite'
    wrapper.vm.formStdio.command = 'uvx'
    wrapper.vm.formStdio.argsStr = 'mcp-server-sqlite --db-path /tmp/test.db'
    wrapper.vm.formStdio.envList = [{ key: 'SQLITE_MODE', value: 'ro' }]

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(apiMocks.createMcpServer).toHaveBeenCalledWith({
      name: 'my-sqlite',
      source: 'configured',
      transport: 'stdio',
      command: 'uvx',
      args: ['mcp-server-sqlite', '--db-path', '/tmp/test.db'],
      env: { SQLITE_MODE: 'ro' },
      enabled: true,
      oauth_required: false
    })
    expect(messageMocks.success).toHaveBeenCalledWith('MCP 服务已创建')
    expect(wrapper.vm.dialogVisible).toBe(false)
  })

  it('adds remote server via remote mode (SSE / HTTP)', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    wrapper.vm.openAddDialog()
    wrapper.vm.activeAddMode = 'remote'
    wrapper.vm.formRemote.name = 'slack-remote'
    wrapper.vm.formRemote.transport = 'http'
    wrapper.vm.formRemote.url = 'https://mcp.slack.com/http'
    wrapper.vm.formRemote.headerList = [{ key: 'X-Token', value: 'secret' }]

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(apiMocks.createMcpServer).toHaveBeenCalledWith({
      name: 'slack-remote',
      source: 'configured',
      transport: 'http',
      url: 'https://mcp.slack.com/http',
      headers: { 'X-Token': 'secret' },
      enabled: true,
      oauth_required: false
    })
    expect(messageMocks.success).toHaveBeenCalledWith('远程 MCP 服务已添加')
  })

  it('imports full configuration via JSON mode (mcpServers structure)', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    wrapper.vm.openAddDialog()
    wrapper.vm.activeAddMode = 'json'
    wrapper.vm.formJson.rawJson = JSON.stringify({
      mcpServers: {
        git: {
          command: 'uvx',
          args: ['mcp-server-git']
        }
      }
    })

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(apiMocks.importMcpServers).toHaveBeenCalledWith({
      mcpServers: {
        git: {
          command: 'uvx',
          args: ['mcp-server-git']
        }
      }
    })
    expect(messageMocks.success).toHaveBeenCalledWith('MCP 配置已导入')
  })

  it('imports single-server configuration via JSON mode', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    wrapper.vm.openAddDialog()
    wrapper.vm.activeAddMode = 'json'
    wrapper.vm.formJson.rawJson = JSON.stringify({
      'redis-mcp': {
        command: 'npx',
        args: ['-y', '@modelcontextprotocol/server-redis'],
        env: { REDIS_URL: 'redis://localhost:6379' }
      }
    })

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(apiMocks.createMcpServer).toHaveBeenCalledWith({
      name: 'redis-mcp',
      source: 'configured',
      transport: 'stdio',
      command: 'npx',
      args: ['-y', '@modelcontextprotocol/server-redis'],
      env: { REDIS_URL: 'redis://localhost:6379' },
      url: '',
      headers: {},
      enabled: true,
      oauth_required: false
    })
  })

  it('filters server lists when typing search keywords', async () => {
    const wrapper = mountConfig()
    await flushPromises()

    wrapper.vm.searchKeyword = 'filesystem'
    expect(wrapper.vm.filteredConfiguredServers).toHaveLength(1)
    expect(wrapper.vm.filteredConfiguredServers[0].name).toBe('filesystem')
    expect(wrapper.vm.filteredPluginServers).toHaveLength(0)

    wrapper.vm.searchKeyword = 'portal'
    expect(wrapper.vm.filteredConfiguredServers).toHaveLength(0)
    expect(wrapper.vm.filteredPluginServers).toHaveLength(1)
    expect(wrapper.vm.filteredPluginServers[0].name).toBe('portal-tools')
  })

  it('renders an actionable empty state and opens JSON import directly', async () => {
    apiMocks.listMcpServers.mockResolvedValue({ configured: [], plugin: [] })
    const wrapper = mountConfig()
    await flushPromises()

    const emptyState = wrapper.find('.mcp-empty')
    expect(emptyState.text()).toContain('尚未安装 MCP 服务器')
    expect(emptyState.text()).toContain('手动新建服务器，或导入已有配置。')
    expect(emptyState.text()).toContain('新建 MCP 服务器')
    expect(emptyState.text()).toContain('导入')

    await emptyState.findAll('button')[1].trigger('click')
    await flushPromises()

    expect(wrapper.vm.dialogVisible).toBe(true)
    expect(wrapper.vm.activeAddMode).toBe('json')
    expect(wrapper.find('.el-dialog-stub').text()).toContain('导入配置')
  })
})
