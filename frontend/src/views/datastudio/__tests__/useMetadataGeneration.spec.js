import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive, ref } from 'vue'

const { nl2sqlApiMock, settingsApiMock, elMessageMock, tableApiMock, domainApiMock } = vi.hoisted(() => ({
  nl2sqlApiMock: {
    listAgents: vi.fn(),
    createTopic: vi.fn(),
    deliverMessage: vi.fn(),
    getTask: vi.fn(),
    getTaskMessage: vi.fn()
  },
  settingsApiMock: { getAgentSettings: vi.fn() },
  elMessageMock: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  tableApiMock: {
    updateComment: vi.fn(),
    updateField: vi.fn(),
    getFields: vi.fn(),
    profileColumnValues: vi.fn(),
    update: vi.fn(),
    getFreshness: vi.fn(),
    saveFreshness: vi.fn()
  },
  domainApiMock: {
    businessDomainApi: { list: vi.fn() },
    dataDomainApi: { list: vi.fn() }
  }
}))

vi.mock('@/api/nl2sql', async () => {
  const actual = await vi.importActual('@/api/nl2sql')
  return { nl2sqlApi: nl2sqlApiMock, nl2sqlErrorMessage: actual.nl2sqlErrorMessage }
})
vi.mock('@/api/settings', () => ({ settingsApi: settingsApiMock }))
vi.mock('@/api/table', () => ({ tableApi: tableApiMock }))
vi.mock('@/api/domain', () => domainApiMock)
vi.mock('element-plus', () => ({ ElMessage: elMessageMock }))
vi.mock('@/demo/runtime', () => ({ isDemoMode: false, showDemoReadonlyMessage: vi.fn() }))

import { useMetadataGeneration } from '../composables/useMetadataGeneration'

const buildDeps = () => {
  const tabStates = reactive({
    t1: {
      table: { id: 1, tableName: 'orders', dbName: 'ods', sourceId: 'object-source' },
      fields: [{ id: 11, fieldName: 'status', fieldType: 'INT', fieldComment: '' }],
      lineage: { upstreamTables: [], downstreamTables: [] },
      tasks: { writeTasks: [], readTasks: [] },
      ddl: 'CREATE TABLE orders(status INT)'
    }
  })
  return {
    clusterId: ref('c1'),
    tabStates,
    taskApi: { getById: vi.fn() },
    loadDdl: vi.fn(),
    layerOptions: [{ value: 'ODS' }, { value: 'DWD' }],
    warnPlatformMetadataMissing: () => false,
    structureEditBlockedReason: () => ''
  }
}

const okMessage = {
  blocks: [
    {
      type: 'main_text',
      text: '```json\n{"table_comment":"订单表","fields":[{"field_name":"status","comment":"订单状态","enum_values":[]}]}\n```'
    }
  ]
}

const enumMessage = (enumValues) => ({
  blocks: [
    {
      type: 'main_text',
      text:
        '```json\n' +
        JSON.stringify({
          table_comment: '订单表',
          fields: [{ field_name: 'status', comment: '订单状态', enum_values: enumValues }]
        }) +
        '\n```'
    }
  ]
})

describe('useMetadataGeneration agent 解析', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    settingsApiMock.getAgentSettings.mockResolvedValue({ metadataAgentId: 'agent_biz' })
    nl2sqlApiMock.listAgents.mockResolvedValue([{ agent_id: 'agent_biz' }, { agent_id: 'agent_default' }])
    nl2sqlApiMock.createTopic.mockResolvedValue({ topic_id: 'tp1' })
    nl2sqlApiMock.deliverMessage.mockResolvedValue({ task_id: 'tk1' })
    nl2sqlApiMock.getTask.mockResolvedValue({ task_status: 'finished' })
    nl2sqlApiMock.getTaskMessage.mockResolvedValue(okMessage)
    tableApiMock.profileColumnValues.mockResolvedValue([])
    tableApiMock.getFreshness.mockResolvedValue({ configured: false, config: null })
    tableApiMock.saveFreshness.mockResolvedValue({})
    domainApiMock.businessDomainApi.list.mockResolvedValue([])
    domainApiMock.dataDomainApi.list.mockResolvedValue([])
  })

  it('使用设置中配置的助手，而不是隐式挑选默认助手', async () => {
    const { generateMetadata, metadataResult } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(nl2sqlApiMock.createTopic).toHaveBeenCalledWith(expect.objectContaining({ agent_id: 'agent_biz' }))
    expect(nl2sqlApiMock.deliverMessage).toHaveBeenCalledWith(
      expect.objectContaining({ agent_id: 'agent_biz', topic_id: 'tp1' })
    )
    expect(metadataResult.value.table.suggestedComment).toBe('订单表')
  })

  it('未配置助手时提示去设置，且不建话题', async () => {
    settingsApiMock.getAgentSettings.mockResolvedValue({ metadataAgentId: '' })
    const { generateMetadata } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(nl2sqlApiMock.createTopic).not.toHaveBeenCalled()
    expect(elMessageMock.error).toHaveBeenCalledWith(expect.stringContaining('尚未配置'))
  })

  it('配置的助手已不在可用清单时提示重新选择', async () => {
    settingsApiMock.getAgentSettings.mockResolvedValue({ metadataAgentId: 'agent_removed' })
    const { generateMetadata } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(nl2sqlApiMock.createTopic).not.toHaveBeenCalled()
    expect(elMessageMock.error).toHaveBeenCalledWith(expect.stringContaining('agent_removed'))
  })

  it('助手目录暂时取不到时不阻断，仍用已配置的助手', async () => {
    nl2sqlApiMock.listAgents.mockRejectedValue(new Error('boom'))
    const { generateMetadata } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(nl2sqlApiMock.createTopic).toHaveBeenCalledWith(expect.objectContaining({ agent_id: 'agent_biz' }))
  })

  it('枚举取值只保留实测数据中出现过的值', async () => {
    tableApiMock.profileColumnValues.mockResolvedValue([
      { fieldName: 'status', values: [{ value: '0', count: 12 }, { value: '1', count: 8 }] }
    ])
    nl2sqlApiMock.getTaskMessage.mockResolvedValue(
      enumMessage([
        { value: '0', label: '待支付' },
        { value: '1', label: '已支付' },
        { value: '2', label: '已退款' }
      ])
    )
    const { generateMetadata, metadataResult } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(tableApiMock.profileColumnValues).toHaveBeenCalledWith(1, 'object-source')
    expect(metadataResult.value.fields[0].suggestedComment).toBe('订单状态。枚举：0=待支付；1=已支付')
    // prompt 里带上实测取值，模型才有据可依
    expect(nl2sqlApiMock.deliverMessage.mock.calls[0][0].content).toContain('- status: 0(12)、1(8)')
  })

  it('取不到实测取值时不输出任何枚举，而不是采信模型编造的取值', async () => {
    tableApiMock.profileColumnValues.mockRejectedValue(new Error('permission denied'))
    nl2sqlApiMock.getTaskMessage.mockResolvedValue(
      enumMessage([
        { value: '0', label: '待支付' },
        { value: '1', label: '已支付' }
      ])
    )
    const { generateMetadata, metadataResult } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(metadataResult.value.fields[0].suggestedComment).toBe('订单状态')
  })

  it('后端 400 时透出 detail 而不是 axios 通用文案', async () => {
    nl2sqlApiMock.createTopic.mockRejectedValue({
      message: 'Request failed with status code 400',
      response: { data: { detail: 'agent not found' } }
    })
    const { generateMetadata } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(elMessageMock.error).toHaveBeenCalledWith('agent not found')
  })

  it('表属性建议按平台清单过滤后进入结果', async () => {
    domainApiMock.businessDomainApi.list.mockResolvedValue([{ domainCode: 'TRADE', domainName: '交易' }])
    domainApiMock.dataDomainApi.list.mockResolvedValue([
      { domainCode: 'REFUND', domainName: '退款', businessDomain: 'TRADE' }
    ])
    nl2sqlApiMock.getTaskMessage.mockResolvedValue({
      blocks: [
        {
          type: 'main_text',
          text: '```json\n{"table_comment":"订单表","table_attributes":{"layer":"DWD","business_domain":"TRADE","data_domain":"FAKE"},"fields":[]}\n```'
        }
      ]
    })
    const { generateMetadata, metadataResult } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    const byKey = Object.fromEntries(metadataResult.value.attributes.map((a) => [a.key, a]))
    expect(byKey.layer).toMatchObject({ suggestedValue: 'DWD', hasRecommendation: true })
    expect(byKey.businessDomain).toMatchObject({ suggestedValue: 'TRADE', hasRecommendation: true })
    // FAKE 不在数据域清单里，被丢弃
    expect(byKey.dataDomain).toMatchObject({ suggestedValue: '', hasRecommendation: false })
  })

  it('采纳表属性时走 tableApi.update 并带上有效分层', async () => {
    tableApiMock.update.mockResolvedValue({ layer: 'DWD', businessDomain: 'TRADE' })
    tableApiMock.getFields.mockResolvedValue([])
    const deps = buildDeps()
    const { adoptMetadata } = useMetadataGeneration(deps)

    await adoptMetadata('t1', {
      attributes: [
        { key: 'layer', value: 'DWD' },
        { key: 'businessDomain', value: 'TRADE' }
      ]
    })

    expect(tableApiMock.update).toHaveBeenCalledWith(
      1,
      { layer: 'DWD', businessDomain: 'TRADE' },
      'object-source'
    )
    expect(deps.tabStates.t1.table.layer).toBe('DWD')
  })

  it('数据源不支持改物理注释时丢弃表/字段描述，仍保存平台属性', async () => {
    tableApiMock.update.mockResolvedValue({ layer: 'DWD' })
    tableApiMock.getFields.mockResolvedValue([])
    const deps = buildDeps()
    deps.structureEditBlockedReason = () => 'PostgreSQL 暂不支持在线修改表结构'
    const { adoptMetadata } = useMetadataGeneration(deps)

    await adoptMetadata('t1', {
      table: { text: '订单表' },
      attributes: [{ key: 'layer', value: 'DWD' }],
      fields: [{ fieldName: 'status', text: '状态' }]
    })

    expect(tableApiMock.updateComment).not.toHaveBeenCalled()
    expect(tableApiMock.updateField).not.toHaveBeenCalled()
    expect(tableApiMock.update).toHaveBeenCalledWith(1, { layer: 'DWD' }, 'object-source')
    expect(elMessageMock.success).toHaveBeenCalled()
  })

  it('表无分层且未采纳分层时给出明确提示，不调用 update', async () => {
    const deps = buildDeps()
    const { adoptMetadata } = useMetadataGeneration(deps)

    await adoptMetadata('t1', { attributes: [{ key: 'businessDomain', value: 'TRADE' }] })

    expect(tableApiMock.update).not.toHaveBeenCalled()
    expect(elMessageMock.error).toHaveBeenCalledWith(expect.stringContaining('数据分层'))
  })

  it('表已有分层时采纳业务域会自动带上现有分层', async () => {
    tableApiMock.update.mockResolvedValue({})
    tableApiMock.getFields.mockResolvedValue([])
    const deps = buildDeps()
    deps.tabStates.t1.table.layer = 'ODS'
    const { adoptMetadata } = useMetadataGeneration(deps)

    await adoptMetadata('t1', { attributes: [{ key: 'businessDomain', value: 'TRADE' }] })

    expect(tableApiMock.update).toHaveBeenCalledWith(1, { layer: 'ODS', businessDomain: 'TRADE' }, 'object-source')
  })

  it('新鲜度建议命中真实字段时进入结果并可采纳', async () => {
    nl2sqlApiMock.getTaskMessage.mockResolvedValue({
      blocks: [
        {
          type: 'main_text',
          text:
            '```json\n' +
            JSON.stringify({
              table_comment: '订单表',
              freshness: {
                loaded_at_field: 'status',
                warn_after_count: 1,
                warn_after_period: 'day',
                error_after_count: 1,
                error_after_period: 'day'
              },
              fields: []
            }) +
            '\n```'
        }
      ]
    })
    const { generateMetadata, metadataResult } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(tableApiMock.getFreshness).toHaveBeenCalledWith(1)
    expect(metadataResult.value.freshness.hasRecommendation).toBe(true)
    expect(metadataResult.value.freshness.suggested).toMatchObject({ mode: 'column', loadedAtField: 'status' })
  })

  it('AI 挑了不存在的时间列时不产出新鲜度建议', async () => {
    nl2sqlApiMock.getTaskMessage.mockResolvedValue({
      blocks: [
        {
          type: 'main_text',
          text:
            '```json\n' +
            JSON.stringify({ table_comment: '订单表', freshness: { loaded_at_field: 'ghost_col' }, fields: [] }) +
            '\n```'
        }
      ]
    })
    const { generateMetadata, metadataResult } = useMetadataGeneration(buildDeps())

    await generateMetadata('t1')

    expect(metadataResult.value.freshness.hasRecommendation).toBe(false)
    expect(metadataResult.value.freshness.suggested).toBeNull()
  })

  it('采纳新鲜度建议时走 tableApi.saveFreshness', async () => {
    tableApiMock.getFields.mockResolvedValue([])
    const deps = buildDeps()
    const { adoptMetadata } = useMetadataGeneration(deps)
    const contract = {
      mode: 'column',
      loadedAtField: 'status',
      warnAfterCount: 1,
      warnAfterPeriod: 'day',
      errorAfterCount: 1,
      errorAfterPeriod: 'day',
      enabled: true
    }

    await adoptMetadata('t1', { freshness: contract })

    expect(tableApiMock.saveFreshness).toHaveBeenCalledWith(1, contract)
  })
})


it('采纳表与字段描述使用对象连接，查询连接切换不会改变写回目标', async () => {
  tableApiMock.getFields.mockResolvedValue([])
  const deps = buildDeps()
  let finishComment
  tableApiMock.updateComment.mockReturnValueOnce(new Promise(resolve => { finishComment = resolve }))
  const { adoptMetadata } = useMetadataGeneration(deps)
  const adopting = adoptMetadata('t1', { table: { text: '订单表' }, fields: [{ fieldName: 'status', text: '状态' }] })
  deps.clusterId.value = 'other-query-source'
  finishComment()
  await adopting
  expect(tableApiMock.updateComment).toHaveBeenLastCalledWith(1, '订单表', 'object-source')
  expect(tableApiMock.updateField).toHaveBeenLastCalledWith(1, 11, expect.anything(), 'object-source')
})
