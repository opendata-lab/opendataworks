import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const apiMocks = vi.hoisted(() => ({
  listSkillDocuments: vi.fn(),
  getSkillDocument: vi.fn(),
  updateSkillRuntime: vi.fn(),
  uninstallSkill: vi.fn(),
  updateSkillDocument: vi.fn(),
  compareSkillDocument: vi.fn(),
  rollbackSkillDocument: vi.fn()
}))

const routerPush = vi.hoisted(() => vi.fn())
const routeState = vi.hoisted(() => ({
  params: {
    folder: 'marketing-insights'
  }
}))

const messageMocks = vi.hoisted(() => ({
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn()
}))

const messageBoxMocks = vi.hoisted(() => ({
  confirm: vi.fn(),
  prompt: vi.fn()
}))

vi.mock('@/api/dataagent', () => ({
  dataagentApi: apiMocks
}))

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal()),
  useRouter: () => ({
    push: routerPush
  }),
  useRoute: () => routeState
}))

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal()),
  ElMessage: messageMocks,
  ElMessageBox: messageBoxMocks
}))

import SkillDetailView from '../SkillDetailView.vue'

const stubs = {
  'el-button': {
    props: ['disabled', 'loading'],
    template: '<button :disabled="disabled"><slot /></button>'
  },
  'el-switch': {
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    template: '<button :disabled="disabled" @click="$emit(\'update:modelValue\', !modelValue)"><slot /></button>'
  },
  'el-tag': { template: '<span><slot /></span>' },
  'el-input': {
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-empty': { template: '<div><slot /></div>' },
  'el-alert': { template: '<div><slot /><slot name="title" /></div>' },
  'el-dialog': { template: '<div><slot /></div>' },
  'el-select': {
    props: ['modelValue'],
    emits: ['update:modelValue', 'change'],
    template: '<select><slot /></select>'
  },
  'el-option': { template: '<option />' },
  'el-table': { template: '<div><slot /></div>' },
  'el-table-column': { template: '<div />' },
  TextCodeEditor: { template: '<div />' },
  SkillFileTreeNode: { template: '<div />' }
}

const documentsPayload = () => ([
  {
    id: 8,
    folder: 'marketing-insights',
    relative_path: 'scripts/run.py',
    file_name: 'run.py',
    category: 'scripts',
    content_type: 'python',
    source: 'managed',
    version_count: 1,
    updated_at: '2026-04-17T09:00:00',
    editable: true,
    enabled: false
  },
  {
    id: 7,
    folder: 'marketing-insights',
    relative_path: 'SKILL.md',
    file_name: 'SKILL.md',
    category: 'root',
    content_type: 'markdown',
    source: 'managed',
    version_count: 3,
    updated_at: '2026-04-17T10:00:00',
    editable: true,
    enabled: false
  }
])

const detailPayload = {
  id: 7,
  folder: 'marketing-insights',
  relative_path: 'SKILL.md',
  file_name: 'SKILL.md',
  category: 'root',
  content_type: 'markdown',
  source: 'managed',
  current_content: '# marketing-insights',
  version_count: 3,
  versions: [
    {
      id: 3,
      version_no: 3,
      is_current: true,
      change_source: 'sync',
      change_summary: 'manual sync',
      created_at: '2026-04-17T10:00:00'
    }
  ],
  updated_at: '2026-04-17T10:00:00',
  editable: true,
  enabled: false
}

const mountView = () => shallowMount(SkillDetailView, {
  global: { stubs }
})

describe('SkillDetailView', () => {
  beforeEach(() => {
    apiMocks.listSkillDocuments.mockReset()
    apiMocks.getSkillDocument.mockReset()
    apiMocks.updateSkillRuntime.mockReset()
    apiMocks.uninstallSkill.mockReset()
    apiMocks.updateSkillDocument.mockReset()
    apiMocks.compareSkillDocument.mockReset()
    apiMocks.rollbackSkillDocument.mockReset()
    messageMocks.success.mockReset()
    messageMocks.warning.mockReset()
    messageMocks.error.mockReset()
    messageBoxMocks.prompt.mockReset()
    routerPush.mockReset()

    apiMocks.listSkillDocuments.mockResolvedValue(documentsPayload())
    apiMocks.getSkillDocument.mockResolvedValue(detailPayload)
    apiMocks.updateSkillRuntime.mockResolvedValue({
      skill_id: 'marketing-insights',
      enabled: true
    })
    apiMocks.uninstallSkill.mockResolvedValue({
      skill_id: 'marketing-insights',
      removed_documents: [],
      was_enabled: false,
      document_count: 1
    })
    apiMocks.updateSkillDocument.mockResolvedValue({
      ...detailPayload,
      current_content: '# marketing-insights\nupdated'
    })
    messageBoxMocks.prompt.mockResolvedValue({ value: '补充运行说明' })
  })

  it('loads the current skill and selects SKILL.md as the default document', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(apiMocks.getSkillDocument).toHaveBeenCalledWith(7)
    expect(wrapper.vm.selectedDocumentId).toBe(7)
    expect(wrapper.vm.detail.file_name).toBe('SKILL.md')
    expect(wrapper.text()).toContain('← Skill 列表')
    expect(wrapper.text()).not.toContain('Back Back')
    expect(wrapper.text()).not.toContain('刷新目录')
    expect(wrapper.text()).not.toContain('版本比对')
  })

  it('asks for change summary after save click and sends it with content', async () => {
    const wrapper = mountView()
    await flushPromises()

    wrapper.vm.editorContent = '# marketing-insights\nupdated'
    await wrapper.vm.saveDocument()
    await flushPromises()

    expect(messageBoxMocks.prompt).toHaveBeenCalledWith(
      '请输入本次修改说明，保存后会写入版本记录。',
      '保存文件',
      expect.objectContaining({
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputPlaceholder: '本次修改说明'
      })
    )
    expect(apiMocks.updateSkillDocument).toHaveBeenCalledWith(7, {
      content: '# marketing-insights\nupdated',
      change_summary: '补充运行说明'
    })
    expect(messageMocks.success).toHaveBeenCalledWith('文件已保存')
  })

  it('enables the current skill from the detail page', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.vm.toggleSkillEnabled(true)
    await flushPromises()

    expect(apiMocks.updateSkillRuntime).toHaveBeenCalledWith('marketing-insights', { enabled: true })
    expect(messageMocks.success).toHaveBeenCalledWith('Skill「marketing-insights」已启用')
  })

  it('disables the current skill from the detail page', async () => {
    const enabledDocs = [
      ...documentsPayload().map((item) => ({ ...item, enabled: true })),
      {
        id: 9,
        folder: 'dataagent-nl2sql',
        relative_path: 'SKILL.md',
        file_name: 'SKILL.md',
        category: 'root',
        content_type: 'markdown',
        source: 'bundled',
        version_count: 1,
        updated_at: '2026-04-17T09:00:00',
        editable: true,
        enabled: true
      }
    ]
    apiMocks.listSkillDocuments.mockResolvedValue(enabledDocs)
    apiMocks.getSkillDocument.mockResolvedValue({ ...detailPayload, enabled: true })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.vm.toggleSkillEnabled(false)
    await flushPromises()

    expect(apiMocks.updateSkillRuntime).toHaveBeenCalledWith('marketing-insights', { enabled: false })
    expect(messageMocks.success).toHaveBeenCalledWith('Skill「marketing-insights」已禁用')
  })

  it('prevents disabling the last enabled skill from the detail page', async () => {
    const enabledDocs = documentsPayload().map((item) => ({ ...item, enabled: true }))
    apiMocks.listSkillDocuments.mockResolvedValue(enabledDocs)
    apiMocks.getSkillDocument.mockResolvedValue({ ...detailPayload, enabled: true })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.vm.toggleSkillEnabled(false)

    expect(apiMocks.updateSkillRuntime).not.toHaveBeenCalled()
    expect(messageMocks.warning).toHaveBeenCalledWith('至少需要保留一个启用 Skill')
  })

  it('uninstalls a managed skill from the detail page and returns to the list', async () => {
    messageBoxMocks.prompt.mockResolvedValue({ value: 'marketing-insights' })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.vm.confirmUninstallCurrentSkill()
    await flushPromises()

    expect(messageBoxMocks.prompt).toHaveBeenCalledWith(
      '请输入 marketing-insights 确认卸载。',
      '卸载 Skill',
      expect.objectContaining({
        confirmButtonText: '确认卸载',
        cancelButtonText: '取消',
        inputPlaceholder: 'marketing-insights'
      })
    )
    expect(apiMocks.uninstallSkill).toHaveBeenCalledWith('marketing-insights')
    expect(messageMocks.success).toHaveBeenCalledWith('Skill「marketing-insights」已卸载')
    expect(routerPush).toHaveBeenCalledWith({
      path: '/intelligent-query',
      query: { tab: 'skills' }
    })
  })
})
