// Public API fixtures, including a type that production code has never heard of.
export const definition = (type, port = 1234, overrides = {}) => ({
  schemaVersion: 1, type, label: type === 'POSTGRESQL' ? 'PostgreSQL' : type,
  defaultPort: port, driverStatus: 'AVAILABLE', editorDialect: 'standard', identifierQuote: '"',
  connectionFields: [
    { key: 'feHost', label: '地址', control: 'text', required: true, defaultValue: '' },
    { key: 'fePort', label: '端口', control: 'number', required: true, defaultValue: port },
    { key: 'databaseName', label: '连接数据库', control: 'text', required: type === 'POSTGRESQL', defaultValue: '' },
    { key: 'username', label: '用户名', control: 'text', required: true, defaultValue: '' },
    { key: 'password', label: '密码', control: 'password', required: false, defaultValue: '' },
  ],
  capabilities: {
    ddlExport: { supported: type !== 'POSTGRESQL', reason: 'PostgreSQL 暂不支持在线 DDL 导出' },
    structureEdit: { supported: type !== 'POSTGRESQL', reason: 'PostgreSQL 暂不支持在线修改表结构' },
    engineProperties: { supported: type === 'DORIS', reason: '不支持引擎配置' },
    existingPrimaryKeyEdit: { supported: false, reason: '已有字段主键不可修改' },
    newPrimaryKeyEdit: { supported: type !== 'DORIS', reason: '新增字段主键不可修改' },
    freshnessMetadata: { supported: type !== 'POSTGRESQL', reason: '不提供表更新时间' },
  },
  ...overrides,
})
export const builtinDefinitions = [definition('DORIS', 9030), definition('MYSQL', 3306), definition('POSTGRESQL', 5432)]
