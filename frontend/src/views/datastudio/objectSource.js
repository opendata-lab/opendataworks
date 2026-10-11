// 对象详情与 SQL 执行目标独立：物理表操作始终使用对象所属连接。
// currentSourceId 仅兼容没有连接字段的旧页签状态。
export const getObjectSourceId = (table, currentSourceId = null) =>
  table?.sourceId || table?.clusterId || currentSourceId || null
