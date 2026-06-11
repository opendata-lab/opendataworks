import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const apiMocks = vi.hoisted(() => ({
  listSkillDocuments: vi.fn(),
  updateSkillRuntime: vi.fn(),
  importSkill: vi.fn(),
  exportSkill: vi.fn(),
  uninstallSkill: vi.fn()
}))

const routerPush = vi.hoisted(() => vi.fn())

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
  })
}))

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal()),
  ElMessage: messageMocks,
  ElMessageBox: messageBoxMocks
}))

import SkillStudio from '../SkillStudio.vue'

const stubs = {
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-button': {
    props: ['loading', 'disabled', 'icon'],
    template: '<button :disabled="disabled"><slot /></button>'
  },
  'el-tooltip': { template: '<div><slot /></div>' },
  'el-alert': { template: '<div><slot /><slot name="title" /></div>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-empty': { template: '<div><slot /></div>' },
  'el-switch': {
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    template: '<button :disabled="disabled" @click="$emit(\'update:modelValue\', !modelValue)"><slot /></button>'
  },
  'el-upload': {
    template: '<div><slot /></div>'
  }
}

const documentsPayload = () => ([
  {
    id: 1,
    folder: 'dataagent-nl2sql',
    relative_path: 'SKILL.md',
    file_name: 'SKILL.md',
    category: 'root',
    content_type: 'markdown',
    source: 'bundled',
    version_count: 2,
    updated_at: '2026-04-17T09:00:00',
    editable: true,
    enabled: true
  },
  {
    id: 2,
    folder: 'dataagent-nl2sql',
    relative_path: 'reference/40-runtime-metadata.md',
    file_name: '40-runtime-metadata.md',
    category: 'reference',
    content_type: 'markdown',
    source: 'bundled',
    version_count: 1,
    updated_at: '2026-04-17T10:00:00',
    editable: true,
    enabled: true
  },
  {
    id: 3,
    folder: 'marketing-insights',
    relative_path: 'SKILL.md',
    file_name: 'SKILL.md',
    category: 'root',
    content_type: 'markdown',
    source: 'managed',
    version_count: 4,
    updated_at: '2026-04-17T11:00:00',
    editable: true,
    enabled: false
  }
])

const mountView = () => shallowMount(SkillStudio, {
  global: { stubs }
})

describe('SkillStudio', () => {
  beforeEach(() => {
    apiMocks.listSkillDocuments.mockReset()
    apiMocks.updateSkillRuntime.mockReset()
    apiMocks.importSkill.mockReset()
    apiMocks.exportSkill.mockReset()
    apiMocks.uninstallSkill.mockReset()
    messageMocks.success.mockReset()
    messageMocks.warning.mockReset()
    messageMocks.error.mockReset()
    messageBoxMocks.confirm.mockReset()
    messageBoxMocks.prompt.mockReset()
    routerPush.mockReset()

    apiMocks.listSkillDocuments.mockResolvedValue(documentsPayload())
    apiMocks.updateSkillRuntime.mockResolvedValue({
      skill_id: 'marketing-insights',
      enabled: true
    })
    apiMocks.importSkill.mockResolvedValue({
      skill_id: 'customer-care',
      source: 'managed',
      enabled: false,
      imported_documents: [],
      document_count: 3
    })
    apiMocks.exportSkill.mockResolvedValue(new Blob(['zip'], { type: 'application/zip' }))
    apiMocks.uninstallSkill.mockResolvedValue({
      skill_id: 'marketing-insights',
      removed_documents: [],
      was_enabled: false,
      document_count: 1
    })
    messageBoxMocks.prompt.mockResolvedValue({ value: 'marketing-insights' })
  })

  it('groups documents into skill cards and routes to detail view', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.filteredSkills).toHaveLength(2)
    expect(wrapper.vm.enabledSummary).toBe('已启用 1 / 共 2')
    expect(wrapper.text()).toContain('dataagent-nl2sql')
    expect(wrapper.text()).toContain('marketing-insights')
    expect(wrapper.text()).toContain('本地导入')
    expect(wrapper.text()).toContain('已启用')
    expect(wrapper.text()).toContain('未启用')
    expect(wrapper.text()).not.toContain('当前运行')
    expect(wrapper.text()).not.toContain('最近更新')
    expect(wrapper.text()).not.toContain('2026-04-17')
    expect(wrapper.find('.skill-card__stats').exists()).toBe(false)

    expect(wrapper.find('.skill-studio__sync-button').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('刷新目录')

    wrapper.vm.openSkillDetail('marketing-insights')
    expect(routerPush).toHaveBeenCalledWith({
      name: 'IntelligentQuerySkillDetail',
      params: { folder: 'marketing-insights' }
    })
  })

  it('enables the selected skill through runtime update api', async () => {
    const wrapper = mountView()
    await flushPromises()

    const targetSkill = wrapper.vm.filteredSkills.find((item) => item.folder === 'marketing-insights')
    await wrapper.vm.setSkillEnabled(targetSkill, true)
    await flushPromises()

    expect(apiMocks.updateSkillRuntime).toHaveBeenCalledWith('marketing-insights', { enabled: true })
    expect(messageMocks.success).toHaveBeenCalledWith('Skill「marketing-insights」已启用')
  })

  it('disables an enabled skill without changing other cards locally', async () => {
    apiMocks.listSkillDocuments.mockResolvedValue(
      documentsPayload().map((item) => (
        item.folder === 'marketing-insights' ? { ...item, enabled: true } : item
      ))
    )
    const wrapper = mountView()
    await flushPromises()

    const currentSkill = wrapper.vm.filteredSkills.find((item) => item.folder === 'dataagent-nl2sql')
    await wrapper.vm.setSkillEnabled(currentSkill, false)
    await flushPromises()

    expect(apiMocks.updateSkillRuntime).toHaveBeenCalledWith('dataagent-nl2sql', { enabled: false })
    expect(messageMocks.success).toHaveBeenCalledWith('Skill「dataagent-nl2sql」已禁用')
  })

  it('prevents disabling the last enabled skill locally', async () => {
    const wrapper = mountView()
    await flushPromises()

    const currentSkill = wrapper.vm.filteredSkills.find((item) => item.folder === 'dataagent-nl2sql')
    await wrapper.vm.setSkillEnabled(currentSkill, false)

    expect(apiMocks.updateSkillRuntime).not.toHaveBeenCalled()
    expect(messageMocks.warning).toHaveBeenCalledWith('至少需要保留一个启用 Skill')
  })

  it('imports a skill zip and reloads the list', async () => {
    const wrapper = mountView()
    await flushPromises()

    const file = new File(['zip'], 'customer-care.zip', { type: 'application/zip' })
    await wrapper.vm.handleSkillUpload({ file })
    await flushPromises()

    expect(apiMocks.importSkill).toHaveBeenCalledWith(file)
    expect(apiMocks.listSkillDocuments).toHaveBeenCalledTimes(2)
    expect(messageMocks.success).toHaveBeenCalledWith('Skill「customer-care」已导入，默认未启用')
  })

  it('reimports an existing skill with a new version and reports the update', async () => {
    apiMocks.importSkill.mockResolvedValue({
      skill_id: 'marketing-insights',
      source: 'managed',
      enabled: true,
      replaced: true,
      version: '2.0.0',
      previous_version: '1.0.0',
      imported_documents: [],
      document_count: 3
    })
    const wrapper = mountView()
    await flushPromises()

    const file = new File(['zip'], 'marketing-insights.zip', { type: 'application/zip' })
    await wrapper.vm.handleSkillUpload({ file })
    await flushPromises()

    expect(messageMocks.success).toHaveBeenCalledWith('Skill「marketing-insights」已更新（版本 2.0.0）')
  })

  it('downloads a skill as a zip through the export api', async () => {
    const createObjectURL = vi.fn(() => 'blob:demo')
    const revokeObjectURL = vi.fn()
    window.URL.createObjectURL = createObjectURL
    window.URL.revokeObjectURL = revokeObjectURL
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    const wrapper = mountView()
    await flushPromises()

    const targetSkill = wrapper.vm.filteredSkills.find((item) => item.folder === 'marketing-insights')
    await wrapper.vm.downloadSkill(targetSkill)
    await flushPromises()

    expect(apiMocks.exportSkill).toHaveBeenCalledWith('marketing-insights')
    expect(createObjectURL).toHaveBeenCalledTimes(1)
    expect(clickSpy).toHaveBeenCalledTimes(1)
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:demo')
    expect(messageMocks.success).toHaveBeenCalledWith('Skill「marketing-insights」已下载')

    clickSpy.mockRestore()
  })

  it('uninstalls managed skills only after folder confirmation', async () => {
    const wrapper = mountView()
    await flushPromises()

    const managedSkill = wrapper.vm.filteredSkills.find((item) => item.folder === 'marketing-insights')
    await wrapper.vm.confirmUninstallSkill(managedSkill)
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
    expect(apiMocks.listSkillDocuments).toHaveBeenCalledTimes(2)
    expect(messageMocks.success).toHaveBeenCalledWith('Skill「marketing-insights」已卸载')

    const builtinSkill = wrapper.vm.filteredSkills.find((item) => item.folder === 'dataagent-nl2sql')
    await wrapper.vm.confirmUninstallSkill(builtinSkill)
    expect(apiMocks.uninstallSkill).toHaveBeenCalledTimes(1)
  })
})
