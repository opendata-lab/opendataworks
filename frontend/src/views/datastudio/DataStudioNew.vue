<template>
  <div :class="['data-studio', { 'is-resizing': isResizing }]">
    <div ref="studioLayoutRef" class="studio-layout">
      <!-- Left: Database Tree -->
      <aside class="studio-sidebar" :style="sidebarPaneStyle">
        <div class="sidebar-controls">
          <div class="search-row">
            <el-input
              v-model="searchKeyword"
              placeholder="搜索表名或注释"
              clearable
              size="small"
              class="search-input"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-button size="small" :loading="dbLoading" @click="refreshCatalog">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
            <el-button size="small" type="primary" :disabled="isDemoMode" @click="handleCreateTable">
              <el-icon><Plus /></el-icon>
              新建表
            </el-button>
          </div>
	          <div class="sort-row">
	            <el-radio-group v-model="sortField" size="small" class="sort-group">
	              <el-radio-button value="tableName">表名</el-radio-button>
	              <el-radio-button value="createdAt">创建时间</el-radio-button>
	              <el-radio-button value="rowCount">行数</el-radio-button>
	              <el-radio-button value="storageSize">数据量</el-radio-button>
	              <el-radio-button value="dorisUpdateTime">更新时间</el-radio-button>
	            </el-radio-group>
	            <el-radio-group v-model="sortOrder" size="small" class="sort-group">
	              <el-radio-button value="asc">升序</el-radio-button>
	              <el-radio-button value="desc">降序</el-radio-button>
	            </el-radio-group>
	          </div>
	        </div>

        <div class="db-tree" v-loading="dbLoading">
          <el-scrollbar class="db-tree-scroll">
            <el-tree
              ref="catalogTreeRef"
              :data="catalogRoots"
              node-key="nodeKey"
              :props="catalogTreeProps"
              lazy
              accordion
              highlight-current
              :expand-on-click-node="false"
              :current-node-key="selectedTableKey"
              :filter-node-method="filterCatalogNode"
              :load="loadCatalogNode"
              class="catalog-tree"
              @node-click="handleCatalogNodeClick"
            >
              <template #default="{ data }">
                <div
                  class="catalog-node"
                  :class="`catalog-node--${data.type}`"
                  :ref="(el) => (data.type === 'table' ? setTableRef(data.nodeKey, el, data.table?.id) : null)"
                >
                <div
                  v-if="data.type === 'table'"
                  class="table-progress-bg"
                  :style="{ width: getProgressWidth(data.sourceId, data.schemaName, data.table) }"
                ></div>

                <div class="catalog-node-row">
                  <template v-if="data.type === 'datasource'">
                    <img
                      v-if="getDatasourceIconUrl(data.sourceType)"
                      :class="['node-icon', 'datasource-logo', { 'is-inactive': isDatasourceIconInactive(data) }]"
                      :src="getDatasourceIconUrl(data.sourceType)"
                      :alt="data.sourceType || 'datasource'"
                    />
                    <el-icon v-else :class="['node-icon', 'datasource', { 'is-inactive': isDatasourceIconInactive(data) }]">
                      <Document />
                    </el-icon>
                  </template>
                  <el-icon v-else-if="data.type === 'schema'" class="node-icon schema"><Coin /></el-icon>
                  <el-icon
                    v-else-if="data.type === 'object_group'"
                    :class="['node-icon', data.objectType === 'view' ? 'view-folder' : 'table-folder']"
                  >
                    <View v-if="data.objectType === 'view'" />
                    <Grid v-else />
                  </el-icon>
                  <el-icon v-else :class="['node-icon', isViewTable(data.table) ? 'view' : 'table']">
                    <View v-if="isViewTable(data.table)" />
                    <Grid v-else />
                  </el-icon>

	                  <div v-if="data.type === 'table'" class="table-main">
	                    <div class="table-title">
	                      <span class="table-name" :title="data.table?.tableName">
	                        {{ data.table?.tableName }}
	                      </span>
                        <el-tooltip
                          v-if="isPlatformMetadataMissing(data.table)"
                          content="平台元数据不存在，请先同步"
                          placement="top"
                        >
                          <el-icon class="metadata-warning-icon"><Warning /></el-icon>
                        </el-tooltip>
	                    </div>
	                    <div v-if="data.table?.tableComment" class="table-comment" :title="data.table.tableComment">
	                      {{ data.table.tableComment }}
	                    </div>
	                  </div>
                  <span v-else class="node-name">{{ data.name }}</span>

                  <div v-if="data.type === 'datasource'" class="node-right">
                    <el-tag size="small" class="source-type" :type="data.sourceType === 'MYSQL' ? 'success' : 'warning'">
                      {{ data.sourceType || 'DORIS' }}
                    </el-tag>
                    <el-tooltip content="刷新数据源" placement="top">
                      <el-icon
                        :class="['refresh-icon', { 'is-disabled': dbLoading || schemaLoading[String(data.sourceId)] }]"
                        @click.stop="refreshDatasourceNode(data)"
                      >
                        <Refresh />
                      </el-icon>
                    </el-tooltip>
                    <el-icon v-if="schemaLoading[String(data.sourceId)]" class="is-loading loading-icon"><Loading /></el-icon>
                  </div>

                  <div v-else-if="data.type === 'schema'" class="node-right">
                    <el-badge :value="getTableCount(data.sourceId, data.schemaName)" type="info" class="db-count" />
                    <el-tooltip content="刷新数据库" placement="top">
                      <el-icon
                        :class="[
                          'refresh-icon',
                          {
                            'is-disabled':
                              dbLoading ||
                              schemaCountLoading[String(data.sourceId)] ||
                              tableLoading[`${String(data.sourceId)}::${data.schemaName}`]
                          }
                        ]"
                        @click.stop="refreshSchemaNode(data)"
                      >
                        <Refresh />
                      </el-icon>
                    </el-tooltip>
                    <el-icon
                      v-if="schemaCountLoading[String(data.sourceId)] || tableLoading[`${String(data.sourceId)}::${data.schemaName}`]"
                      class="is-loading loading-icon"
                    >
                      <Loading />
                    </el-icon>
                  </div>

                  <div v-else-if="data.type === 'object_group'" class="node-right">
                    <el-badge
                      :value="getTableCountByType(data.sourceId, data.schemaName, data.objectType)"
                      type="info"
                      class="db-count"
                    />
                  </div>

                  <div v-else-if="data.type === 'table'" class="table-meta-tags">
                    <span class="row-count" :title="`数据量: ${formatNumber(getTableRowCount(data.table))} 行`">
                      {{ formatRowCount(getTableRowCount(data.table)) }}
                    </span>
                    <span class="storage-size" :title="`存储大小: ${formatStorageSize(getTableStorageSize(data.table))}`">
                      {{ formatStorageSize(getTableStorageSize(data.table)) }}
                    </span>
                    <span
                      :class="['lineage-count', 'upstream', { 'is-zero': getUpstreamCount(data.table?.id) === 0 }]"
                      :title="`上游表: ${getUpstreamCount(data.table?.id)} 个`"
                    >
                      ↑{{ getUpstreamCount(data.table?.id) }}
                    </span>
                    <span
                      :class="['lineage-count', 'downstream', { 'is-zero': getDownstreamCount(data.table?.id) === 0 }]"
                      :title="`下游表: ${getDownstreamCount(data.table?.id)} 个`"
                    >
                      ↓{{ getDownstreamCount(data.table?.id) }}
                    </span>
                  </div>
                </div>
              </div>
            </template>
            </el-tree>
          </el-scrollbar>
        </div>
      </aside>

      <div class="sidebar-resizer" title="拖动调整宽度" @mousedown="startResize"></div>

      <!-- Right: Workspace -->
      <section class="studio-workspace">
        <div class="workspace-body">
          <PersistentTabs
            v-if="openTabs.length"
            v-model="activeTab"
            :tabs="openTabs"
            type="card"
            closable
            addable
            class="workspace-tabs"
            style="height: 100%;"
            @tab-remove="handleTabRemove"
            @tab-add="handleTabAdd"
            @close-left="handleCloseLeft"
            @close-right="handleCloseRight"
            @close-all="handleCloseAll"
          >
            <template #label="{ tab }">
              <div class="tab-label">
                <span class="tab-title">{{ tab.tableName }}</span>
                <span class="tab-sub">{{ getTabSubtitle(tab) }}</span>
              </div>
            </template>

            <template #default="{ tab }">
              <div class="tab-grid">
                <div
                  class="tab-left"
                  :ref="(el) => setLeftPaneRef(tab.id, el)"
                  :style="getLeftPaneStyle(tab.id)"
                >
                  <div class="query-panel">
                    <div class="query-topbar">
                      <div class="query-topbar__left">
                        <div class="query-context">
                          <template v-if="tab.kind === 'query'">
                            <el-select
                              v-model="tabStates[tab.id].table.sourceId"
                              size="small"
                              filterable
                              clearable
                              class="query-select query-select--source"
                              placeholder="选择数据源"
                              @change="(value) => handleQuerySourceSelect(tab.id, value)"
                            >
                              <el-option
                                v-for="source in dataSources"
                                :key="String(source.id)"
                                :label="source.clusterName || source.name || `DataSource ${source.id}`"
                                :value="String(source.id)"
                              />
                            </el-select>

                            <el-select
                              v-model="tabStates[tab.id].table.dbName"
                              size="small"
                              filterable
                              clearable
                              class="query-select query-select--db"
                              placeholder="选择数据库"
                              :disabled="!tabStates[tab.id].table.sourceId"
                              @change="(value) => handleQueryDatabaseSelect(tab.id, value)"
                            >
                              <el-option
                                v-for="db in getSchemaOptions(tabStates[tab.id].table.sourceId)"
                                :key="db"
                                :label="db"
                                :value="db"
                              />
                            </el-select>
                          </template>

                          <template v-else>
                            <el-tag size="small" type="info">{{ getSourceName(tab.sourceId) || '-' }}</el-tag>
                            <el-tag size="small" type="info">{{ tabStates[tab.id].table.dbName || '-' }}</el-tag>
                          </template>
                        </div>

                        <div class="query-divider"></div>

                        <span class="limit-label">Limit</span>
                        <el-input-number
                          v-model="tabStates[tab.id].query.limit"
                          :min="1"
                          :max="5000"
                          :step="100"
                          size="small"
                          controls-position="right"
                          class="limit-input"
                        />
                      </div>

                      <div class="query-topbar__actions">
                        <el-button
                          type="success"
                          size="small"
                          :loading="tabStates[tab.id].queryLoading"
                          :disabled="tabStates[tab.id].queryLoading"
                          @click="executeQuery(tab.id)"
                        >
                          <el-icon><CaretRight /></el-icon>
                          {{ tabStates[tab.id].query.hasSelection ? '运行已选择' : '运行全部' }}
                        </el-button>
	                        <el-button
	                          size="small"
	                          :loading="tabStates[tab.id].queryStopping"
	                          :disabled="!tabStates[tab.id].queryCancelable || tabStates[tab.id].queryStopping"
	                          @click="stopQuery(tab.id)"
	                        >
	                          <el-icon><VideoPause /></el-icon>
	                          停止
                        </el-button>
                        <el-button size="small" :disabled="tabStates[tab.id].queryLoading" @click="resetQuery(tab.id)">
                          重置
                        </el-button>
                        <el-button
                          size="small"
                          type="success"
                          plain
                          :disabled="tabStates[tab.id].queryLoading || isDemoMode"
                          @click="saveAsTask(tab.id)"
                        >
                          存为任务
                        </el-button>
                      </div>
                    </div>
                    <SqlEditor
                      v-model="tabStates[tab.id].query.sql"
                      class="sql-editor"
                      placeholder="-- 输入 SQL，支持查询与变更语句（高风险语句需强确认）"
                      :table-names="getSqlCompletionTables(tab.id)"
                      :completion-context="getSqlCompletionContext(tab.id)"
                      @selection-change="(payload) => handleSqlSelectionChange(tab.id, payload)"
                    />
                  </div>

                  <div class="left-resizer" title="拖动调整高度" @mousedown="startLeftResize(tab.id, $event)"></div>

                  <div class="result-panel">
                    <el-tabs v-model="tabStates[tab.id].resultTab" type="border-card" class="result-tabs" style="height: 100%;">
                      <el-tab-pane name="info">
                        <template #label>
                          <span class="result-label"><el-icon><Document /></el-icon> 信息</span>
                        </template>

                        <div class="table-toolbar">
                          <div class="meta-info">
                            <span class="meta-item">
                              <el-icon><Timer /></el-icon>
                              {{ formatDuration(getLiveDurationMs(tab.id)) }}
                            </span>
                            <span class="meta-item">
                              <el-tag v-if="tabStates[tab.id].queryLoading" size="small" type="info">运行中</el-tag>
                              <el-tag v-else-if="tabStates[tab.id].queryResult.cancelled" size="small" type="warning">已停止</el-tag>
                              <el-tag v-else size="small" type="success">已完成</el-tag>
                            </span>
                            <span v-if="tabStates[tab.id].queryResult.executedAt" class="meta-item">
                              <el-icon><Clock /></el-icon>
                              {{ formatDateTime(tabStates[tab.id].queryResult.executedAt) }}
                            </span>
                          </div>
                        </div>

                        <div class="result-view-container">
                          <div class="table-wrapper">
                            <el-empty
                              v-if="!(tabStates[tab.id].queryResult.statementInfos || []).length"
                              description="暂无执行信息"
                              :image-size="80"
                            />
                            <el-table
                              v-else
                              :data="tabStates[tab.id].queryResult.statementInfos || []"
                              border
                              stripe
                              size="small"
                              height="100%"
                            >
                              <el-table-column prop="statementIndex" label="#" width="70" />
                              <el-table-column label="状态" width="120">
                                <template #default="{ row }">
                                  <el-tag size="small" :type="getStatementStatusTagType(row.status)">
                                    {{ row.status || '-' }}
                                  </el-tag>
                                </template>
                              </el-table-column>
                              <el-table-column label="耗时" width="110">
                                <template #default="{ row }">
                                  {{ formatDuration(row.durationMs || 0) }}
                                </template>
                              </el-table-column>
                              <el-table-column prop="sqlSnippet" label="SQL 摘要" min-width="320" show-overflow-tooltip />
                              <el-table-column prop="resultInfo" label="结果信息" min-width="220" show-overflow-tooltip />
                            </el-table>
                          </div>
                        </div>
                      </el-tab-pane>

                      <el-tab-pane
                        v-for="(resultSet, idx) in getDisplayResultSets(tab.id)"
                        :key="String(idx)"
                        :name="`result-${idx}`"
                      >
                        <template #label>
                          <span class="result-label"><el-icon><List /></el-icon> Result {{ idx + 1 }}</span>
                        </template>

	                        <div class="table-toolbar">
	                          <div class="meta-info">
                            <span class="meta-item">
                              <el-icon><Timer /></el-icon>
	                              {{ formatDuration(getLiveDurationMs(tab.id)) }}
	                            </span>
	                            <span v-if="tabStates[tab.id].queryCancelable" class="meta-item">
	                              <template v-if="tabStates[tab.id].queryLoading">
	                                <el-icon><Loading /></el-icon> 查询中
	                              </template>
	                              <template v-else>
	                                <el-icon><Warning /></el-icon> 仍可停止
	                              </template>
	                            </span>
	                            <template v-else>
	                              <el-tag v-if="tabStates[tab.id].queryResult.cancelled" size="small" type="warning">
	                                已停止
	                              </el-tag>
                              <span v-if="tabStates[tab.id].queryResult.executedAt" class="meta-item">
                                <el-icon><Clock /></el-icon>
                                {{ formatDateTime(tabStates[tab.id].queryResult.executedAt) }}
                              </span>
                              <span v-if="tabStates[tab.id].queryResult.message" class="meta-item meta-message" :title="tabStates[tab.id].queryResult.message">
                                {{ tabStates[tab.id].queryResult.message }}
                              </span>
                            </template>
                            <span class="meta-item">
                              <el-icon><Files /></el-icon>
                              {{ getResultSetCountText(resultSet) }}
                            </span>
                            <span v-if="resultSet.hasMore" class="meta-item truncate">
                              <el-icon><Warning /></el-icon> 结果已截断
                            </span>
                          </div>
                          <div class="export-actions">
                            <el-radio-group
                              v-if="isResultSetType(resultSet)"
                              v-model="tabStates[tab.id].resultViewTabs[idx]"
                              size="small"
                              class="result-view-switch"
                            >
                              <el-radio-button value="table">
                                <span class="view-label"><el-icon><Grid /></el-icon> 表格</span>
                              </el-radio-button>
                              <el-radio-button value="chart">
                                <span class="view-label"><el-icon><TrendCharts /></el-icon> 图表</span>
                              </el-radio-button>
                            </el-radio-group>
                            <el-button
                              size="small"
                              :disabled="!isResultSetType(resultSet) || !(resultSet.rows || []).length"
                              @click="exportResult(tab.id, idx)"
                            >
                              导出 CSV
                            </el-button>
                          </div>
	                        </div>

                        <div v-if="tabStates[tab.id].queryResult.errorMessage" class="result-message">
                          <el-alert
                            type="error"
                            :closable="false"
                            show-icon
                            :title="tabStates[tab.id].queryResult.errorMessage"
                          />
                        </div>
                        <div
                          v-else-if="tabStates[tab.id].queryResult.cancelled && tabStates[tab.id].queryResult.message"
                          class="result-message"
                        >
                          <el-alert
                            type="warning"
                            :closable="false"
                            show-icon
                            :title="tabStates[tab.id].queryResult.message"
                          />
                        </div>

	                        <div class="result-view-container">
                          <div
                            v-show="!isResultSetType(resultSet) || (tabStates[tab.id].resultViewTabs?.[idx] || 'table') === 'table'"
                            class="table-wrapper"
                          >
                            <div v-if="!isResultSetType(resultSet)" class="statement-result-card">
                              <el-alert
                                :type="getResultSetAlertType(resultSet)"
                                :closable="false"
                                show-icon
                                :title="resultSet.message || '语句执行完成'"
                              />
                            </div>
                            <el-empty
                              v-else-if="!(resultSet.rows || []).length && !tabStates[tab.id].queryLoading"
                              description="暂无数据"
                              :image-size="80"
                            />
                            <DataStudioResultGrid
                              v-else
                              :columns="resultSet.columns || []"
                              :rows="resultSet.rows || []"
                              :row-key-prefix="getResultRowKeyPrefix(tab.id, idx)"
                            />
	                          </div>
	
                          <div
                            v-if="isResultSetType(resultSet)"
                            v-show="(tabStates[tab.id].resultViewTabs?.[idx] || 'table') === 'chart'"
                            class="result-chart"
                          >
	                            <div class="chart-grid">
	                              <div class="chart-config">
	                                <div class="config-title">图表类型</div>
		                                <div class="chart-type">
		                                  <el-radio-group v-model="tabStates[tab.id].charts[idx].type" size="small">
		                                    <el-radio-button value="bar">柱状图</el-radio-button>
		                                    <el-radio-button value="line">折线图</el-radio-button>
		                                    <el-radio-button value="pie">饼图</el-radio-button>
		                                  </el-radio-group>
		                                </div>
	                                <div class="config-title">
	                                  {{ tabStates[tab.id].charts[idx].type === 'pie' ? '分类字段' : 'X 轴字段' }}
	                                </div>
	                                <el-select
	                                  v-model="tabStates[tab.id].charts[idx].xAxis"
	                                  size="small"
	                                  placeholder="选择字段"
	                                  class="config-select"
	                                  :disabled="!(resultSet.columns || []).length"
	                                >
	                                  <el-option
	                                    v-for="col in (resultSet.columns || [])"
	                                    :key="col"
	                                    :label="col"
	                                    :value="col"
	                                  />
	                                </el-select>
	                                <div class="config-title">
	                                  {{ tabStates[tab.id].charts[idx].type === 'pie' ? '数值字段' : 'Y 轴字段' }}
	                                </div>
	                                <el-select
	                                  v-model="tabStates[tab.id].charts[idx].yAxis"
	                                  size="small"
	                                  multiple
	                                  collapse-tags
	                                  placeholder="选择数值字段"
	                                  class="config-select"
	                                  :disabled="!(resultSet.columns || []).length"
	                                >
	                                  <el-option
	                                    v-for="col in getNumericColumns(tab.id, idx)"
	                                    :key="col"
	                                    :label="col"
	                                    :value="col"
	                                  />
	                                </el-select>
	                                <div class="hint">配置变更后自动刷新</div>
	                              </div>
	                              <div class="chart-canvas">
	                                <div class="chart-inner" :ref="(el) => setChartRef(tab.id, idx, el)"></div>
	                                <div v-if="!(resultSet.rows || []).length" class="chart-empty">暂无数据</div>
	                                <div v-else-if="!canRenderChart(tab.id, idx)" class="chart-empty">
	                                  请选择字段并执行查询
	                                </div>
	                              </div>
	                            </div>
	                          </div>
	                        </div>
                      </el-tab-pane>

                      <el-tab-pane name="history">
                        <template #label>
                          <span class="result-label"><el-icon><Clock /></el-icon> 历史查询</span>
                        </template>
                        <div class="history-panel">
                          <el-table
                            :data="historyData"
                            border
                            size="small"
                            height="100%"
                            v-loading="historyLoading"
                          >
                            <el-table-column prop="sqlText" label="SQL" min-width="220" show-overflow-tooltip />
                            <el-table-column prop="databaseName" label="数据库" width="120" />
                            <el-table-column prop="clusterId" label="集群" width="100" />
                            <el-table-column label="执行时间" width="160">
                              <template #default="{ row }">
                                {{ formatDateTime(row.executedAt || row.createdAt) }}
                              </template>
                            </el-table-column>
                            <el-table-column label="耗时" width="100">
                              <template #default="{ row }">
                                {{ formatDuration(row.durationMs) }}
                              </template>
                            </el-table-column>
                            <el-table-column label="操作" width="90">
                              <template #default="{ row }">
                                <el-button type="primary" link size="small" @click="applyHistory(row, tab.id)">
                                  填入
                                </el-button>
                              </template>
                            </el-table-column>
                          </el-table>
                        </div>
                        <div class="history-pagination">
                          <el-pagination
                            v-model:current-page="historyPager.pageNum"
                            v-model:page-size="historyPager.pageSize"
                            :page-sizes="[10, 15, 30, 50]"
                            layout="total, sizes, prev, pager, next"
                            :total="historyPager.total"
                            background
                            small
                          />
                        </div>
                      </el-tab-pane>
                    </el-tabs>
                  </div>
                </div>

                <!-- moved to DataStudioRightPanel.vue
                <Teleport to="#datastudio-right-panel">
                  <div v-if="tab.kind !== 'query' && String(activeTab) === String(tab.id)" class="tab-right">
                    <div class="meta-panel">
                    <el-tabs v-model="tabStates[tab.id].metaTab" class="meta-tabs">
                      <el-tab-pane name="basic" label="基本信息">
                        <div class="meta-section meta-section-fill">
                          <div class="section-header">
                            <span>表信息</span>
                            <div class="section-actions">
                              <el-tooltip
                                v-if="!tabStates[tab.id].metaEditing && isDorisTable(tabStates[tab.id].table) && !clusterId"
                                content="请选择 Doris 集群后再编辑"
                                placement="top"
                              >
                                <span>
                                  <el-button type="primary" size="small" disabled>编辑</el-button>
                                </span>
                              </el-tooltip>
                              <el-button
                                v-else-if="!tabStates[tab.id].metaEditing"
                                type="primary"
                                size="small"
                                @click="startMetaEdit(tab.id)"
                              >
                                编辑
                              </el-button>
                              <el-tooltip
                                v-if="!tabStates[tab.id].metaEditing && isDorisTable(tabStates[tab.id].table) && !clusterId"
                                content="请选择 Doris 集群后再删除"
                                placement="top"
                              >
                                <span>
                                  <el-button type="danger" plain size="small" disabled>删除表</el-button>
                                </span>
                              </el-tooltip>
                              <el-button
                                v-else-if="!tabStates[tab.id].metaEditing"
                                type="danger"
                                plain
                                size="small"
                                @click="handleDeleteTable"
                              >
                                删除表
                              </el-button>
                              <template v-else>
                                <el-button size="small" @click="cancelMetaEdit(tab.id)">取消</el-button>
                                <el-button
                                  type="primary"
                                  size="small"
                                  :loading="tabStates[tab.id].metaSaving"
                                  @click="saveMetaEdit(tab.id)"
                                >
                                  保存
                                </el-button>
                              </template>
                            </div>
                          </div>

                          <div class="meta-scroll">
                            <el-descriptions :column="1" border size="small" class="meta-descriptions">
                              <el-descriptions-item label="表名">
                                <el-input
                                  v-if="tabStates[tab.id].metaEditing"
                                  v-model="tabStates[tab.id].metaForm.tableName"
                                  size="small"
                                  class="meta-input"
                                />
                                <span v-else>{{ tabStates[tab.id].table.tableName || '-' }}</span>
                              </el-descriptions-item>
                              <el-descriptions-item label="表注释">
                                <el-input
                                  v-if="tabStates[tab.id].metaEditing"
                                  v-model="tabStates[tab.id].metaForm.tableComment"
                                  size="small"
                                  class="meta-input"
                                />
                                <span v-else>{{ tabStates[tab.id].table.tableComment || '-' }}</span>
                              </el-descriptions-item>
                              <el-descriptions-item label="分层">
                                <el-select
                                  v-if="tabStates[tab.id].metaEditing"
                                  v-model="tabStates[tab.id].metaForm.layer"
                                  size="small"
                                  placeholder="选择分层"
                                  class="meta-input"
                                >
                                  <el-option v-for="item in layerOptions" :key="item.value" :label="item.label" :value="item.value" />
                                </el-select>
                                <span v-else>{{ tabStates[tab.id].table.layer || '-' }}</span>
                              </el-descriptions-item>
                              <el-descriptions-item label="负责人">
                                <el-input
                                  v-if="tabStates[tab.id].metaEditing"
                                  v-model="tabStates[tab.id].metaForm.owner"
                                  size="small"
                                  class="meta-input"
                                />
                                <span v-else>{{ tabStates[tab.id].table.owner || '-' }}</span>
                              </el-descriptions-item>
                              <el-descriptions-item label="数据库">
                                <span>{{ tabStates[tab.id].table.dbName || '-' }}</span>
                              </el-descriptions-item>
                            </el-descriptions>

                            <template v-if="isDorisTable(tabStates[tab.id].table)">
                              <div class="section-divider"></div>

                              <div class="section-header small">
                                <span>Doris 配置</span>
                              </div>
                              <el-descriptions :column="1" border size="small" class="meta-descriptions">
                                <el-descriptions-item label="表模型">{{ tabStates[tab.id].table.tableModel || '-' }}</el-descriptions-item>
                                <el-descriptions-item label="主键列">{{ tabStates[tab.id].table.keyColumns || '-' }}</el-descriptions-item>
                                <el-descriptions-item label="分区字段">{{ tabStates[tab.id].table.partitionColumn || '-' }}</el-descriptions-item>
                                <el-descriptions-item label="分桶字段">{{ tabStates[tab.id].table.distributionColumn || '-' }}</el-descriptions-item>
                                <el-descriptions-item label="分桶数">
                                  <el-input-number
                                    v-if="tabStates[tab.id].metaEditing"
                                    v-model="tabStates[tab.id].metaForm.bucketNum"
                                    :min="1"
                                    size="small"
                                    controls-position="right"
                                    class="meta-input"
                                  />
                                  <span v-else>{{ tabStates[tab.id].table.bucketNum || '-' }}</span>
                                </el-descriptions-item>
                                <el-descriptions-item label="副本数">
                                  <template v-if="tabStates[tab.id].metaEditing">
                                    <div class="replica-edit">
                                      <el-input-number
                                        v-model="tabStates[tab.id].metaForm.replicaNum"
                                        :min="1"
                                        size="small"
                                        controls-position="right"
                                        class="meta-input"
                                      />
                                      <span v-if="isReplicaWarning(tabStates[tab.id].metaForm.replicaNum)" class="replica-warning">
                                        <el-icon><Warning /></el-icon>
                                        建议≥3
                                      </span>
                                    </div>
                                  </template>
                                  <span v-else :class="['replica-value', { 'replica-danger': isReplicaWarning(tabStates[tab.id].table.replicaNum) }]">
                                    <el-icon v-if="isReplicaWarning(tabStates[tab.id].table.replicaNum)" class="warning-icon"><Warning /></el-icon>
                                    {{ tabStates[tab.id].table.replicaNum || '-' }}
                                  </span>
                                </el-descriptions-item>
                              </el-descriptions>
                            </template>
                          </div>
                        </div>
                      </el-tab-pane>

      <el-tab-pane name="columns" label="列信息">
        <div class="meta-section meta-section-fill">
          <div class="section-header">
            <div class="section-title">
              <span>字段定义</span>
              <el-tag
                v-if="tabStates[tab.id].fieldsEditing && isAggregateTable(tabStates[tab.id].table)"
                type="warning"
                size="small"
                effect="plain"
              >
                AGGREGATE 表仅支持修改注释
              </el-tag>
              <el-tag
                v-if="tabStates[tab.id].fieldsEditing && isDorisTable(tabStates[tab.id].table)"
                type="warning"
                size="small"
                effect="plain"
              >
                主键列不可在线修改
              </el-tag>
            </div>
            <div class="section-actions">
              <el-tooltip
                v-if="!tabStates[tab.id].fieldsEditing && isDorisTable(tabStates[tab.id].table) && !clusterId"
                content="请选择 Doris 集群后再编辑"
                placement="top"
              >
                <span>
                  <el-button type="primary" size="small" disabled>编辑</el-button>
                </span>
              </el-tooltip>
              <el-button
                v-else-if="!tabStates[tab.id].fieldsEditing"
                type="primary"
                size="small"
                @click="startFieldsEdit(tab.id)"
              >
                编辑
              </el-button>
              <template v-else>
                <el-button size="small" @click="cancelFieldsEdit(tab.id)" :disabled="tabStates[tab.id].fieldSubmitting">
                  取消
                </el-button>
                <el-button
                  type="primary"
                  size="small"
                  :loading="tabStates[tab.id].fieldSubmitting"
                  @click="saveFieldsEdit(tab.id)"
                >
                  保存修改
                </el-button>
              </template>
            </div>
          </div>
          <div v-if="getFieldRows(tab.id).length" class="meta-table">
            <el-table
              :data="getFieldRows(tab.id)"
              border
              size="small"
              height="100%"
            >
              <el-table-column label="字段名" width="130" show-overflow-tooltip>
                <template #default="{ row }">
                  <el-input
                    v-if="tabStates[tab.id].fieldsEditing"
                    v-model="row.fieldName"
                    size="small"
                    placeholder="字段名"
                    :disabled="isAggregateTable(tabStates[tab.id].table)"
                  />
                  <span v-else>{{ row.fieldName }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="150">
                <template #default="{ row }">
                  <el-input
                    v-if="tabStates[tab.id].fieldsEditing"
                    v-model="row.fieldType"
                    size="small"
                    placeholder="VARCHAR(255)"
                    :disabled="isAggregateTable(tabStates[tab.id].table)"
                  />
                  <span v-else>{{ row.fieldType }}</span>
                </template>
              </el-table-column>
              <el-table-column label="可为空" width="90">
                <template #default="{ row }">
                  <el-switch
                    v-if="tabStates[tab.id].fieldsEditing"
                    v-model="row.isNullable"
                    :active-value="1"
                    :inactive-value="0"
                    size="small"
                    :disabled="isAggregateTable(tabStates[tab.id].table)"
                  />
                  <el-tag v-else :type="row.isNullable ? 'success' : 'danger'" size="small">
                    {{ row.isNullable ? '是' : '否' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="主键" width="80">
                <template #default="{ row }">
                  <template v-if="tabStates[tab.id].fieldsEditing">
                    <el-tooltip
                      v-if="isDorisTable(tabStates[tab.id].table)"
                      content="Doris 不支持在线修改主键列"
                      placement="top"
                    >
                      <span>
                        <el-switch
                          v-model="row.isPrimary"
                          :active-value="1"
                          :inactive-value="0"
                          size="small"
                          disabled
                        />
                      </span>
                    </el-tooltip>
                    <el-switch
                      v-else
                      v-model="row.isPrimary"
                      :active-value="1"
                      :inactive-value="0"
                      size="small"
                      :disabled="isAggregateTable(tabStates[tab.id].table)"
                    />
                  </template>
                  <template v-else>
                    <el-tag v-if="row.isPrimary" type="info" size="small">是</el-tag>
                    <span v-else>-</span>
                  </template>
                </template>
              </el-table-column>
              <el-table-column label="默认值" width="120">
                <template #default="{ row }">
                  <el-input
                    v-if="tabStates[tab.id].fieldsEditing"
                    v-model="row.defaultValue"
                    size="small"
                    placeholder="可选"
                    :disabled="isAggregateTable(tabStates[tab.id].table)"
                  />
                  <span v-else>{{ row.defaultValue || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="注释" min-width="150">
                <template #default="{ row }">
                  <el-input
                    v-if="tabStates[tab.id].fieldsEditing"
                    v-model="row.fieldComment"
                    size="small"
                    placeholder="字段注释"
                  />
                  <span v-else>{{ row.fieldComment || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="tabStates[tab.id].fieldsEditing" label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-tooltip
                    v-if="isAggregateTable(tabStates[tab.id].table)"
                    content="AGGREGATE 表不支持新增字段"
                    placement="top"
                  >
                    <span>
                      <el-button link type="primary" size="small" disabled>新增</el-button>
                    </span>
                  </el-tooltip>
                  <el-button
                    v-else
                    link
                    type="primary"
                    size="small"
                    @click="addField(tab.id, row)"
                  >
                    新增
                  </el-button>
                  <el-popconfirm
                    width="240"
                    confirm-button-text="确定"
                    cancel-button-text="取消"
                    :title="`确定删除字段「${row.fieldName || '未命名'}」吗？`"
                    @confirm="removeField(tab.id, row)"
                  >
                    <template #reference>
                      <el-tooltip
                        v-if="isAggregateTable(tabStates[tab.id].table)"
                        content="AGGREGATE 表不支持删除字段"
                        placement="top"
                      >
                        <span>
                          <el-button link type="danger" size="small" disabled>删除</el-button>
                        </span>
                      </el-tooltip>
                      <el-button v-else link type="danger" size="small">删除</el-button>
                    </template>
                  </el-popconfirm>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <el-empty v-else description="暂无字段" :image-size="60">
            <template #default>
              <el-button
                v-if="tabStates[tab.id].fieldsEditing"
                type="primary"
                size="small"
                @click="addField(tab.id)"
                :disabled="isAggregateTable(tabStates[tab.id].table)"
              >
                新增字段
              </el-button>
            </template>
          </el-empty>
        </div>
      </el-tab-pane>

                      <el-tab-pane name="ddl" label="DDL">
                        <div class="meta-section meta-section-fill" v-loading="tabStates[tab.id].ddlLoading">
                          <div class="ddl-header">
                            <el-button
                              size="small"
                              :disabled="!tabStates[tab.id].ddl"
                              @click="copyDdl(tab.id)"
                            >
                              复制
                            </el-button>
                          </div>
                          <el-input
                            v-model="tabStates[tab.id].ddl"
                            type="textarea"
                            resize="none"
                            readonly
                            class="ddl-textarea"
                            placeholder="加载中或暂无 DDL"
                          />
                        </div>
                      </el-tab-pane>
                    </el-tabs>
                  </div>

                  <div class="lineage-panel">
                    <div class="lineage-header">
                      <span>数据血缘</span>
                      <el-button type="primary" link size="small" @click="goLineage(tab.id)">
                        查看完整血缘
                      </el-button>
                    </div>
                    <div class="lineage-grid">
	                      <div class="lineage-card">
	                        <div class="lineage-title">上游表 ({{ tabStates[tab.id].lineage.upstreamTables.length }})</div>
	                        <div class="task-block">
	                          <div class="task-title-row">
	                            <div class="task-title">写入任务 ({{ tabStates[tab.id].tasks.writeTasks.length }})</div>
	                            <el-button
	                              type="primary"
	                              size="small"
	                              plain
	                              :disabled="!tabStates[tab.id].table?.id"
	                              @click.stop="goCreateRelatedTask(tab.id, 'write')"
	                            >
	                              <el-icon><Plus /></el-icon>
	                              新增写入任务
	                            </el-button>
	                          </div>
	                          <div v-if="tabStates[tab.id].tasks.writeTasks.length" class="task-list">
	                            <div
	                              v-for="task in tabStates[tab.id].tasks.writeTasks"
                              :key="task.id"
                              class="task-item"
                              @click="openTask(task.id)"
                            >
                              <div class="task-name">{{ task.taskName || '-' }}</div>
                              <div class="task-meta">{{ task.engine || '-' }}</div>
                            </div>
                          </div>
                          <el-empty v-else description="暂无写入任务" :image-size="40" />
                        </div>
                        <div class="lineage-list">
                          <div
                            v-for="item in tabStates[tab.id].lineage.upstreamTables"
                            :key="item.id"
                            class="lineage-item"
                            @click="openTableTab(item)"
                          >
                            <el-icon><Document /></el-icon>
                            <div class="lineage-info">
                              <div class="lineage-name">{{ item.tableName }}</div>
                              <div class="lineage-desc">{{ item.tableComment || '-' }}</div>
                            </div>
                            <el-tag v-if="item.layer" size="small" :type="getLayerType(item.layer)">{{ item.layer }}</el-tag>
                          </div>
                        </div>
                      </div>

	                      <div class="lineage-card">
	                        <div class="lineage-title">下游表 ({{ tabStates[tab.id].lineage.downstreamTables.length }})</div>
	                        <div class="task-block">
	                          <div class="task-title-row">
	                            <div class="task-title">读取任务 ({{ tabStates[tab.id].tasks.readTasks.length }})</div>
	                            <el-button
	                              type="primary"
	                              size="small"
	                              plain
	                              :disabled="!tabStates[tab.id].table?.id"
	                              @click.stop="goCreateRelatedTask(tab.id, 'read')"
	                            >
	                              <el-icon><Plus /></el-icon>
	                              新增读取任务
	                            </el-button>
	                          </div>
	                          <div v-if="tabStates[tab.id].tasks.readTasks.length" class="task-list">
	                            <div
	                              v-for="task in tabStates[tab.id].tasks.readTasks"
                              :key="task.id"
                              class="task-item"
                              @click="openTask(task.id)"
                            >
                              <div class="task-name">{{ task.taskName || '-' }}</div>
                              <div class="task-meta">{{ task.engine || '-' }}</div>
                            </div>
                          </div>
                          <el-empty v-else description="暂无读取任务" :image-size="40" />
                        </div>
                        <div class="lineage-list">
                          <div
                            v-for="item in tabStates[tab.id].lineage.downstreamTables"
                            :key="item.id"
                            class="lineage-item"
                            @click="openTableTab(item)"
                          >
                            <el-icon><Document /></el-icon>
                            <div class="lineage-info">
                              <div class="lineage-name">{{ item.tableName }}</div>
                              <div class="lineage-desc">{{ item.tableComment || '-' }}</div>
                            </div>
                            <el-tag v-if="item.layer" size="small" :type="getLayerType(item.layer)">{{ item.layer }}</el-tag>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                  </div>
                </Teleport>
                -->
              </div>
            </template>
          </PersistentTabs>

	          <div v-else class="empty-state">
	            <el-empty description="从左侧选择表以打开工作区" :image-size="120">
	              <el-button type="primary" @click="handleTabAdd">
	                <el-icon><Plus /></el-icon>
	                新建查询
	              </el-button>
	            </el-empty>
	          </div>
	        </div>
	      </section>

      <div class="workspace-resizer" title="拖动调整宽度" @mousedown="startRightResize"></div>

      <!-- Right: Meta/Lineage -->
      <aside class="studio-right" :style="rightPaneStyle">
        <DataStudioRightPanel visual-variant="clean-slate" />
      </aside>
    </div>

    <CreateTableDrawer v-if="createDrawerVisible" v-model="createDrawerVisible" @created="handleCreateSuccess" />
    <TaskEditDrawer ref="taskDrawerRef" @success="handleTaskSuccess" />

  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, markRaw, nextTick, onBeforeUnmount, onMounted, provide, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Coin,
  Search,
  Clock,
  Delete,
  Plus,
  CaretRight,
  Document,
  Grid,
  Loading,
  Refresh,
  List,
  TrendCharts,
  Timer,
  Files,
  View,
  VideoPause,
  Warning
} from '@element-plus/icons-vue'
import { tableApi } from '@/api/table'
import { lineageApi } from '@/api/lineage'
import { dorisClusterApi } from '@/api/doris'
import { dataQueryApi } from '@/api/query'
import { businessDomainApi, dataDomainApi } from '@/api/domain'
import PersistentTabs from '@/components/PersistentTabs.vue'
import TaskEditDrawer from '@/views/tasks/TaskEditDrawer.vue'
import DataStudioResultGrid from '@/views/datastudio/components/DataStudioResultGrid.vue'
import { isDemoMode, showDemoReadonlyMessage } from '@/demo/runtime'
import { copyText } from '@/utils/clipboard'
import { loadEcharts } from '@/utils/loadEcharts'
import { buildCsvContent } from './csvExport'
import { buildResultGridRows } from './components/resultGridModel'

const SqlEditor = defineAsyncComponent({
  loader: () => import('@/components/SqlEditor.vue'),
  suspensible: false
})

const CreateTableDrawer = defineAsyncComponent({
  loader: () => import('@/views/datastudio/CreateTableDrawer.vue'),
  suspensible: false
})

const DataStudioRightPanel = defineAsyncComponent({
  loader: () => import('@/views/datastudio/components/DataStudioRightPanel.vue'),
  suspensible: false
})

const clusterId = ref(null)
const route = useRoute()
const router = useRouter()
const studioLayoutRef = ref(null)
const DEFAULT_SIDEBAR_RATIO = 0.2
const DEFAULT_RIGHT_RATIO = 0.23
const MIN_SIDEBAR_WIDTH = 220
const MAX_SIDEBAR_WIDTH = 840
const MIN_RIGHT_WIDTH = 320
const MAX_RIGHT_WIDTH = 900
const sidebarWidthRatio = ref(DEFAULT_SIDEBAR_RATIO)
const rightPanelWidthRatio = ref(DEFAULT_RIGHT_RATIO)
const getLayoutWidth = () => {
  const width = studioLayoutRef.value?.getBoundingClientRect()?.width || window.innerWidth || 1
  return Math.max(1, width)
}
const clampWidth = (value, min, max) => Math.max(min, Math.min(max, value))
const clampSidebarWidth = (value) => clampWidth(value, MIN_SIDEBAR_WIDTH, MAX_SIDEBAR_WIDTH)
const clampRightWidth = (value) => clampWidth(value, MIN_RIGHT_WIDTH, MAX_RIGHT_WIDTH)
const getSidebarWidthPx = () => clampSidebarWidth(getLayoutWidth() * sidebarWidthRatio.value)
const getRightPanelWidthPx = () => clampRightWidth(getLayoutWidth() * rightPanelWidthRatio.value)
const sidebarPaneStyle = computed(() => ({
  width: `${(sidebarWidthRatio.value * 100).toFixed(2)}%`,
  minWidth: `${MIN_SIDEBAR_WIDTH}px`,
  maxWidth: `${MAX_SIDEBAR_WIDTH}px`
}))
const rightPaneStyle = computed(() => ({
  width: `${(rightPanelWidthRatio.value * 100).toFixed(2)}%`,
  minWidth: `${MIN_RIGHT_WIDTH}px`,
  maxWidth: `${MAX_RIGHT_WIDTH}px`
}))
const normalizePaneRatios = () => {
  const layoutWidth = getLayoutWidth()
  sidebarWidthRatio.value = clampSidebarWidth(layoutWidth * sidebarWidthRatio.value) / layoutWidth
  rightPanelWidthRatio.value = clampRightWidth(layoutWidth * rightPanelWidthRatio.value) / layoutWidth
}
const isResizing = ref(false)
let resizeMoveHandler = null
let resizeUpHandler = null
let resizeRightMoveHandler = null
let resizeRightUpHandler = null
const leftPaneHeights = reactive({})
const leftPaneRefs = ref({})
let resizeLeftMoveHandler = null
let resizeLeftUpHandler = null
const historyData = ref([])
const historyPager = reactive({ pageNum: 1, pageSize: 15, total: 0 })
const historyLoading = ref(false)
const createDrawerVisible = ref(false)

const dbLoading = ref(false)
const dataSources = ref([])
const activeSource = ref('')
const schemaStore = reactive({})
const schemaLoading = reactive({})
const schemaCountStore = reactive({})
const schemaCountLoading = reactive({})
const schemaCountKeyword = reactive({})
const schemaCountRequestSeq = reactive({})
const activeSchema = reactive({})
const tableLoading = reactive({})
const tableStore = reactive({})
const columnStore = reactive({})
const lineageCache = reactive({})
const activatedSources = reactive({})
const datasourceActivationTasks = new Map()
const EMPTY_SCHEMA_COUNTS = Object.freeze({ tableCount: 0, viewCount: 0, totalCount: 0 })
let schemaCountReloadTimer = null

const catalogTreeRef = ref(null)
const catalogTreeProps = {
  children: 'children',
  label: 'name',
  isLeaf: 'leaf'
}

const getDatasourceNodeKey = (sourceId) => `ds:${String(sourceId)}`
const getSchemaNodeKey = (sourceId, schemaName) => `schema:${String(sourceId)}::${schemaName}`
const getObjectGroupNodeKey = (sourceId, schemaName, objectType) =>
  `group:${String(sourceId)}::${schemaName}::${objectType}`

const catalogRoots = computed(() => {
  const list = Array.isArray(dataSources.value) ? dataSources.value : []
  return list.map((source) => ({
    nodeKey: getDatasourceNodeKey(source.id),
    type: 'datasource',
    name: source.clusterName || source.name || `DataSource ${source.id}`,
    sourceId: String(source.id),
    sourceType: source.sourceType,
    status: source.status,
    leaf: false
  }))
})

const searchKeyword = ref('')
const sortField = ref('tableName')
const sortOrder = ref('asc')

const selectedTableKey = ref('')
const suppressRouteSync = ref(false)

const openTabs = ref([])
const activeTab = ref('')
const tabStates = reactive({})
const queryTimerHandles = new Map()
const queryTabCounter = ref(1)

const TAB_PERSIST_KEY = isDemoMode
  ? 'odw:datastudio:workspace-tabs:demo-v1'
  : 'odw:datastudio:workspace-tabs:v1'
const isRestoringTabs = ref(false)
let persistTabsTimer = null

const tabsPersistSnapshot = computed(() => {
  const tabs = (Array.isArray(openTabs.value) ? openTabs.value : []).map((tab) => {
    const id = String(tab?.id ?? '')
    const state = id ? tabStates[id] : null
    return {
      id,
      kind: tab?.kind === 'query' ? 'query' : 'table',
      tableName: tab?.tableName || '',
      dbName: tab?.dbName || state?.table?.dbName || '',
      sourceId: tab?.sourceId || state?.table?.sourceId || '',
      sourceType: state?.table?.sourceType || '',
      tableId: state?.table?.id || null,
      sql: state?.query?.sql ?? '',
      limit: Number(state?.query?.limit ?? 200)
    }
  })
  return {
    version: 1,
    activeTab: String(activeTab.value || ''),
    tabs
  }
})

const persistTabsNow = (snapshot) => {
  try {
    const tabs = snapshot?.tabs || []
    if (!Array.isArray(tabs) || tabs.length === 0) {
      localStorage.removeItem(TAB_PERSIST_KEY)
      return
    }
    localStorage.setItem(TAB_PERSIST_KEY, JSON.stringify(snapshot))
  } catch (error) {
    console.warn('保存工作区 Tab 状态失败', error)
  }
}

const schedulePersistTabs = (snapshot) => {
  if (persistTabsTimer) {
    clearTimeout(persistTabsTimer)
  }
  persistTabsTimer = setTimeout(() => {
    persistTabsTimer = null
    persistTabsNow(snapshot)
  }, 250)
}

const flushPersistTabs = () => {
  if (persistTabsTimer) {
    clearTimeout(persistTabsTimer)
    persistTabsTimer = null
  }
  persistTabsNow(tabsPersistSnapshot.value)
}

const restoreTabsFromStorage = () => {
  let parsed = null
  try {
    const raw = localStorage.getItem(TAB_PERSIST_KEY)
    if (!raw) return false
    parsed = JSON.parse(raw)
  } catch (error) {
    console.warn('读取工作区 Tab 状态失败', error)
    return false
  }

  if (!parsed || parsed.version !== 1 || !Array.isArray(parsed.tabs)) return false

  isRestoringTabs.value = true
  try {
    const nextTabs = []
    const existingKeys = Object.keys(tabStates)
    existingKeys.forEach((key) => delete tabStates[key])

    parsed.tabs.forEach((item) => {
      const id = String(item?.id ?? '')
      if (!id) return
      const kind = item?.kind === 'query' ? 'query' : 'table'
      const tabItem = {
        id,
        kind,
        tableName: String(item?.tableName ?? ''),
        dbName: String(item?.dbName ?? ''),
        sourceId: String(item?.sourceId ?? ''),
        sourceType: String(item?.sourceType ?? '')
      }

      const tablePayload =
        kind === 'query'
          ? { tableName: '', dbName: tabItem.dbName, sourceId: tabItem.sourceId, sourceType: tabItem.sourceType }
          : { id: item?.tableId || undefined, tableName: tabItem.tableName, dbName: tabItem.dbName, sourceId: tabItem.sourceId, sourceType: tabItem.sourceType }

      tabStates[id] = createTabState(tablePayload)
      if (typeof item?.sql === 'string') {
        tabStates[id].query.sql = item.sql
      }
      if (Number.isFinite(Number(item?.limit))) {
        tabStates[id].query.limit = Number(item.limit)
      }

      nextTabs.push(tabItem)
    })

    openTabs.value = nextTabs

    const active = String(parsed?.activeTab ?? '')
    const activeExists = active && nextTabs.some((tab) => String(tab.id) === active)
    activeTab.value = activeExists ? active : (nextTabs[0] ? String(nextTabs[0].id) : '')

    const maxQueryIndex = nextTabs
      .filter((tab) => tab.kind === 'query')
      .map((tab) => {
        const match = String(tab.tableName || '').match(/(\d+)$/)
        return match ? Number(match[1]) : 0
      })
      .reduce((max, val) => (Number.isFinite(val) ? Math.max(max, val) : max), 0)
    queryTabCounter.value = maxQueryIndex ? maxQueryIndex + 1 : 1

    return true
  } finally {
    isRestoringTabs.value = false
  }
}

watch(
  tabsPersistSnapshot,
  (snapshot) => {
    if (isRestoringTabs.value) return
    schedulePersistTabs(snapshot)
  },
  { deep: true }
)

	const tableRefs = ref({})
	const chartRefs = ref({})
	const chartInstances = new Map()
	const taskDrawerRef = ref(null)
	const tableObserver = ref(null)
	const nowTick = ref(Date.now())
	let nowTickHandle = null

	const startNowTicker = () => {
	  if (nowTickHandle) return
	  nowTickHandle = setInterval(() => {
	    nowTick.value = Date.now()
	  }, 200)
	}

	const stopNowTickerIfIdle = () => {
	  if (!nowTickHandle) return
	  const hasCancelable = Object.values(tabStates).some((state) => !!state?.queryCancelable)
	  if (hasCancelable) return
	  clearInterval(nowTickHandle)
	  nowTickHandle = null
	}

const layerOptions = [
  { label: 'ODS - 原始数据层', value: 'ODS' },
  { label: 'DWD - 明细数据层', value: 'DWD' },
  { label: 'DIM - 维度数据层', value: 'DIM' },
  { label: 'DWS - 汇总数据层', value: 'DWS' },
  { label: 'ADS - 应用数据层', value: 'ADS' }
]
const businessDomainOptions = ref([])

	const clearQueryTimer = (tabId) => {
	  const handle = queryTimerHandles.get(tabId)
	  if (!handle) return
	  clearInterval(handle)
	  queryTimerHandles.delete(tabId)
	}

	const startQueryTimer = (tabId) => {
	  clearQueryTimer(tabId)
	  const state = tabStates[tabId]
	  if (!state) return
	  state.queryTiming.startedAt = Date.now()
	  state.queryTiming.elapsedMs = 0
	  startNowTicker()
	  const handle = setInterval(() => {
	    const current = tabStates[tabId]
	    if (!current?.queryCancelable) {
	      clearQueryTimer(tabId)
	      stopNowTickerIfIdle()
	      return
	    }
	    current.queryTiming.elapsedMs = Date.now() - current.queryTiming.startedAt
	  }, 200)
	  queryTimerHandles.set(tabId, handle)
	}

const loadClusters = async () => {
  dbLoading.value = true
  try {
    const clusters = await dorisClusterApi.list()
    dataSources.value = Array.isArray(clusters) ? clusters : []
    if (!clusterId.value && dataSources.value.length) {
      const defaultCluster =
        dataSources.value.find((item) => item.isDefault === 1) || dataSources.value[0]
      clusterId.value = defaultCluster?.id || null
    }
    if (!activeSource.value && dataSources.value.length) {
      const defaultSource =
        dataSources.value.find((item) => item.isDefault === 1) || dataSources.value[0]
      activeSource.value = defaultSource?.id ? String(defaultSource.id) : ''
      if (activeSource.value) {
        const ok = await loadSchemas(activeSource.value)
        if (ok) {
          await nextTick()
          await ensureCatalogPathExpanded(activeSource.value, activeSchema[String(activeSource.value)])
        }
      }
    }
  } catch (error) {
    ElMessage.error('加载数据源失败')
  } finally {
    dbLoading.value = false
  }
}

const loadBusinessDomains = async () => {
  try {
    const options = await businessDomainApi.list()
    businessDomainOptions.value = Array.isArray(options) ? options : []
  } catch (error) {
    businessDomainOptions.value = []
    console.error('加载业务域失败', error)
  }
}

const loadMetaDataDomainOptions = async (tabId, businessDomain) => {
  const state = tabStates[tabId]
  if (!state) return
  if (!businessDomain) {
    state.metaDataDomainOptions = []
    return
  }
  try {
    const options = await dataDomainApi.list({ businessDomain })
    state.metaDataDomainOptions = Array.isArray(options) ? options : []
  } catch (error) {
    state.metaDataDomainOptions = []
    console.error('加载数据域失败', error)
  }
}

const getMetaDataDomainOptions = (tabId) => {
  const state = tabStates[tabId]
  if (!state?.metaDataDomainOptions) return []
  return state.metaDataDomainOptions
}

const handleMetaBusinessDomainChange = async (tabId) => {
  const state = tabStates[tabId]
  if (!state) return
  state.metaForm.dataDomain = ''
  await loadMetaDataDomainOptions(tabId, state.metaForm.businessDomain)
}

const getDatasourceById = (sourceId) => {
  const id = String(sourceId || '')
  const list = Array.isArray(dataSources.value) ? dataSources.value : []
  return list.find((item) => String(item.id) === id) || null
}

const activateDatasource = async (sourceId) => {
  if (!sourceId) return false
  const key = String(sourceId)
  const source = getDatasourceById(key)
  if (source?.status && source.status !== 'active') {
    ElMessage.warning('数据源已停用')
    return false
  }
  if (activatedSources[key]) return true
  if (datasourceActivationTasks.has(key)) {
    return datasourceActivationTasks.get(key)
  }

  const task = (async () => {
    try {
      await dorisClusterApi.testConnection(sourceId)
      activatedSources[key] = true
      return true
    } catch (error) {
      activatedSources[key] = false
      ElMessage.error('数据源连接失败')
      return false
    } finally {
      datasourceActivationTasks.delete(key)
    }
  })()

  datasourceActivationTasks.set(key, task)
  return task
}

const toSafeCount = (value) => {
  const num = Number(value)
  if (!Number.isFinite(num) || num <= 0) return 0
  return Math.floor(num)
}

const normalizeSchemaCounts = (item) => {
  const tableCount = toSafeCount(item?.tableCount)
  const viewCount = toSafeCount(item?.viewCount)
  const totalFromPayload = toSafeCount(item?.totalCount)
  const totalCount = totalFromPayload || tableCount + viewCount
  return { tableCount, viewCount, totalCount }
}

const normalizeKeyword = (keyword) => String(keyword || '').trim()

const getSchemaCountSnapshot = (sourceId, schemaName) => {
  const sourceKey = String(sourceId || '')
  if (!sourceKey || !schemaName) return EMPTY_SCHEMA_COUNTS
  return schemaCountStore[sourceKey]?.[schemaName] || EMPTY_SCHEMA_COUNTS
}

const isSchemaTablesLoaded = (sourceId, database) => {
  const sourceKey = String(sourceId || '')
  return Array.isArray(tableStore[sourceKey]?.[database])
}

const loadSchemaCounts = async (sourceId, keyword = searchKeyword.value, force = false) => {
  if (!sourceId) return false
  const sourceKey = String(sourceId)
  const normalizedKeyword = normalizeKeyword(keyword)
  if (!force && schemaCountStore[sourceKey] && schemaCountKeyword[sourceKey] === normalizedKeyword) {
    return true
  }

  const requestSeq = (schemaCountRequestSeq[sourceKey] || 0) + 1
  schemaCountRequestSeq[sourceKey] = requestSeq
  schemaCountLoading[sourceKey] = true
  try {
    const params = {}
    if (normalizedKeyword) {
      params.keyword = normalizedKeyword
    }
    const counts = await dorisClusterApi.getSchemaObjectCounts(sourceId, params)
    if (schemaCountRequestSeq[sourceKey] !== requestSeq) {
      return false
    }
    const normalizedStore = {}
    ;(Array.isArray(counts) ? counts : []).forEach((item) => {
      const schemaName = String(item?.schemaName || '')
      if (!schemaName) return
      normalizedStore[schemaName] = normalizeSchemaCounts(item)
    })
    schemaCountStore[sourceKey] = normalizedStore
    schemaCountKeyword[sourceKey] = normalizedKeyword
    return true
  } catch (error) {
    if (schemaCountRequestSeq[sourceKey] === requestSeq && !schemaCountStore[sourceKey]) {
      schemaCountStore[sourceKey] = {}
    }
    console.error('加载 schema 计数失败', error)
    return false
  } finally {
    if (schemaCountRequestSeq[sourceKey] === requestSeq) {
      schemaCountLoading[sourceKey] = false
    }
  }
}

const loadSchemas = async (sourceId, force = false) => {
  if (!sourceId) return false
  const key = String(sourceId)
  if (schemaStore[key] && !force) {
    activatedSources[key] = true
    await loadSchemaCounts(sourceId, searchKeyword.value)
    return true
  }
  schemaLoading[key] = true
  try {
    const activated = await activateDatasource(sourceId)
    if (!activated) return false
    const schemas = await dorisClusterApi.getDatabases(sourceId)
    schemaStore[key] = Array.isArray(schemas) ? schemas : []
    activatedSources[key] = true
    refreshDatasourceChildrenInTree(sourceId)
    await loadSchemaCounts(sourceId, searchKeyword.value, true)
    if (!activeSchema[key] && schemaStore[key].length) {
      activeSchema[key] = schemaStore[key][0]
    }
    return true
  } catch (error) {
    ElMessage.error('加载数据库列表失败')
    return false
  } finally {
    schemaLoading[key] = false
  }
}

const loadTables = async (sourceId, database, force = false, refreshTree = true) => {
  if (!sourceId || !database) return false
  const sourceKey = String(sourceId)
  const sourceType = String(getDatasourceById(sourceKey)?.sourceType || '').toUpperCase()
  tableStore[sourceKey] = tableStore[sourceKey] || {}
  if (tableStore[sourceKey][database] && !force) return true
  const loadingKey = `${sourceKey}::${database}`
  tableLoading[loadingKey] = true
  try {
    const activated = await activateDatasource(sourceId)
    if (!activated) return false
    const [tables, metaTables] = await Promise.all([
      dorisClusterApi.getTables(sourceId, database),
      tableApi.listByDatabase(database, sortField.value, sortOrder.value, sourceId).catch(() => [])
    ])
    const metaList = Array.isArray(metaTables) ? metaTables : []
    const metaMap = new Map(metaList.map((item) => [item.tableName, item]))
    const list = (Array.isArray(tables) ? tables : []).map((item) => {
      const tableName = item.tableName || item.TABLE_NAME || ''
      const meta = metaMap.get(tableName)
      const base = {
        ...item,
        sourceId: sourceKey,
        sourceType,
        dbName: database,
        tableName,
        tableType: item.tableType || item.TABLE_TYPE || '',
        tableComment: item.tableComment || item.TABLE_COMMENT || '',
        rowCount: item.tableRows ?? item.table_rows ?? item.rowCount,
        storageSize: item.dataLength ?? item.data_length ?? item.storageSize,
        createdAt: item.createTime || item.CREATE_TIME || item.createdAt || meta?.dorisCreateTime || meta?.createdAt,
        dorisCreateTime: item.createTime || item.CREATE_TIME || meta?.dorisCreateTime || null,
        dorisUpdateTime: item.updateTime || item.UPDATE_TIME || meta?.dorisUpdateTime || null
      }
      if (!meta) {
        return {
          ...base,
          id: undefined,
          metadataMissing: true,
          metadataStatus: 'missing'
        }
      }
      return {
        ...meta,
        ...base,
        id: meta.id,
        tableComment: base.tableComment || meta.tableComment,
        metadataMissing: false,
        metadataStatus: 'synced'
      }
    })
    tableStore[sourceKey][database] = list
    if (refreshTree) {
      refreshSchemaChildrenInTree(sourceId, database)
      refreshObjectGroupChildrenInTree(sourceId, database, 'table')
      refreshObjectGroupChildrenInTree(sourceId, database, 'view')
    }
    return true
  } catch (error) {
    ElMessage.error('加载表列表失败')
    return false
  } finally {
    tableLoading[loadingKey] = false
  }
}

const handleSourceChange = async (sourceId) => {
  if (!sourceId) return
  await loadSchemas(sourceId)
}

const handleSchemaChange = async (sourceId, database) => {
  if (!sourceId || !database) return
  await loadTables(sourceId, database)
}

const getFilteredTables = (sourceId, database) => {
  const sourceKey = String(sourceId || '')
  const list = tableStore[sourceKey]?.[database] || []
  if (!searchKeyword.value) return list
  const keyword = searchKeyword.value.toLowerCase()
  return list.filter((item) => {
    return (
      item.tableName?.toLowerCase().includes(keyword) ||
      item.tableComment?.toLowerCase().includes(keyword)
    )
  })
}

const getDisplayedTables = (sourceId, database) => {
  const list = [...getFilteredTables(sourceId, database)]
  const field = sortField.value
  const order = sortOrder.value
  list.sort((a, b) => {
    const aVal = a[field]
    const bVal = b[field]
    if (aVal == null && bVal == null) return 0
    if (aVal == null) return order === 'asc' ? -1 : 1
    if (bVal == null) return order === 'asc' ? 1 : -1
    if (typeof aVal === 'number' && typeof bVal === 'number') {
      return order === 'asc' ? aVal - bVal : bVal - aVal
    }
    return order === 'asc'
      ? String(aVal).localeCompare(String(bVal))
      : String(bVal).localeCompare(String(aVal))
  })
  return list
}

const getTableCount = (sourceId, database) => {
  if (isSchemaTablesLoaded(sourceId, database)) {
    return getFilteredTables(sourceId, database).length
  }
  return getSchemaCountSnapshot(sourceId, database).totalCount
}

const getTableCountByType = (sourceId, database, objectType) => {
  if (isSchemaTablesLoaded(sourceId, database)) {
    return getFilteredTables(sourceId, database).filter((item) =>
      objectType === 'view' ? isViewTable(item) : !isViewTable(item)
    ).length
  }
  const snapshot = getSchemaCountSnapshot(sourceId, database)
  return objectType === 'view' ? snapshot.viewCount : snapshot.tableCount
}

const setTableRef = (key, el, tableId) => {
  if (!key || !el) return
  tableRefs.value[key] = el
  if (tableId) {
    el.dataset.tableId = String(tableId)
  }
  if (tableObserver.value) {
    tableObserver.value.observe(el)
  }
}

const setLeftPaneRef = (key, el) => {
  if (!key || !el) return
  leftPaneRefs.value[key] = el
}

const getLeftPaneStyle = (key) => {
  const height = leftPaneHeights[key]
  if (!height) return {}
  return { '--left-top': `${height}px` }
}

const getTableKey = (table, fallbackDb = '', fallbackSource = '') => {
  if (!table) return ''
  const sourceId = table.sourceId || table.clusterId || fallbackSource || ''
  const dbName = table.dbName || table.databaseName || table.database || fallbackDb || ''
  const tableName = table.tableName || ''
  const core = dbName && tableName ? `${dbName}.${tableName}` : tableName || dbName
  return sourceId ? `${sourceId}::${core}` : core
}

const findCachedTableById = (tableId) => {
  const targetId = String(tableId || '')
  if (!targetId) return null
  for (const sourceId of Object.keys(tableStore)) {
    const dbMap = tableStore[sourceId]
    if (!dbMap || typeof dbMap !== 'object') continue
    for (const dbName of Object.keys(dbMap)) {
      const list = Array.isArray(dbMap[dbName]) ? dbMap[dbName] : []
      const found = list.find((item) => item && String(item.id) === targetId)
      if (!found) continue
      return {
        ...found,
        sourceId: String(found.sourceId || found.clusterId || sourceId),
        dbName: found.dbName || dbName
      }
    }
  }
  return null
}

const buildSchemaNode = (sourceId, schemaName) => ({
  nodeKey: getSchemaNodeKey(sourceId, schemaName),
  type: 'schema',
  name: schemaName,
  sourceId: String(sourceId),
  schemaName,
  leaf: false
})

const buildObjectGroupNode = (sourceId, schemaName, objectType) => ({
  nodeKey: getObjectGroupNodeKey(sourceId, schemaName, objectType),
  type: 'object_group',
  objectType,
  name: objectType === 'view' ? '视图' : '表',
  sourceId: String(sourceId),
  schemaName,
  leaf: false
})

const isDatasourceIconInactive = (nodeData) => {
  if (!nodeData || nodeData.type !== 'datasource') return false
  if (nodeData.status && nodeData.status !== 'active') return true
  return !activatedSources[String(nodeData.sourceId)]
}

const getDatasourceIconUrl = (sourceType) => {
  const type = String(sourceType || '').toUpperCase()
  if (type === 'MYSQL') return '/datasource-icons/mysql.svg'
  if (type === 'DORIS') return '/datasource-icons/doris.svg'
  return ''
}

const normalizeTableType = (tableType) => {
  const normalized = String(tableType || '').trim().toUpperCase()
  return normalized || 'BASE TABLE'
}

const isViewTableType = (tableType) => normalizeTableType(tableType).includes('VIEW')

const isViewTable = (table) => isViewTableType(table?.tableType)

const buildTableNode = (table, sourceId, schemaName) => {
  const key = getTableKey(table, schemaName, sourceId)
  return {
    nodeKey: key || `table:${String(sourceId)}::${schemaName}.${table?.tableName || ''}`,
    type: 'table',
    name: table?.tableName || '',
    sourceId: String(sourceId),
    schemaName,
    table,
    objectType: isViewTable(table) ? 'view' : 'table',
    leaf: true
  }
}

const parseTimeValue = (value) => {
  if (!value) return 0
  if (typeof value === 'number') return value
  const text = String(value)
  const parsed = Date.parse(text)
  if (!Number.isNaN(parsed)) return parsed
  const fallback = Date.parse(text.replace(' ', 'T'))
  return Number.isNaN(fallback) ? 0 : fallback
}

const getTableSortValue = (table) => {
  const field = sortField.value
  if (field === 'rowCount') return getTableRowCount(table)
  if (field === 'storageSize') return getTableStorageSize(table)
  if (field === 'dorisUpdateTime') {
    return parseTimeValue(table?.dorisUpdateTime)
  }
  if (field === 'createdAt') {
    return parseTimeValue(table?.dorisCreateTime ?? table?.createTime ?? table?.CREATE_TIME ?? table?.createdAt)
  }
  return String(table?.tableName || '').toLowerCase()
}

const getSortedTablesForTree = (sourceId, database, objectType = 'all') => {
  const sourceKey = String(sourceId || '')
  let list = [...(tableStore[sourceKey]?.[database] || [])]
  if (objectType === 'view') {
    list = list.filter((item) => isViewTable(item))
  } else if (objectType === 'table') {
    list = list.filter((item) => !isViewTable(item))
  }
  const order = sortOrder.value
  list.sort((a, b) => {
    const aVal = getTableSortValue(a)
    const bVal = getTableSortValue(b)
    if (aVal === bVal) return 0
    if (order === 'asc') return aVal > bVal ? 1 : -1
    return aVal < bVal ? 1 : -1
  })
  return list
}

const buildSchemaChildren = (sourceId, database) => ([
  buildObjectGroupNode(sourceId, database, 'table'),
  buildObjectGroupNode(sourceId, database, 'view')
])

const buildTableChildren = (sourceId, database, objectType = 'all') =>
  getSortedTablesForTree(sourceId, database, objectType).map((table) => buildTableNode(table, sourceId, database))

const refreshDatasourceChildrenInTree = (sourceId) => {
  const tree = catalogTreeRef.value
  if (!tree || !sourceId) return
  const key = getDatasourceNodeKey(sourceId)
  const node = tree.getNode(key)
  if (!node?.loaded) return
  const schemas = schemaStore[String(sourceId)] || []
  tree.updateKeyChildren(key, schemas.map((schemaName) => buildSchemaNode(sourceId, schemaName)))
  nextTick(() => tree.filter(searchKeyword.value))
}

const refreshSchemaChildrenInTree = (sourceId, database) => {
  const tree = catalogTreeRef.value
  if (!tree || !sourceId || !database) return
  const key = getSchemaNodeKey(sourceId, database)
  const node = tree.getNode(key)
  if (!node?.loaded) return
  tree.updateKeyChildren(key, buildSchemaChildren(sourceId, database))
  nextTick(() => tree.filter(searchKeyword.value))
}

const refreshObjectGroupChildrenInTree = (sourceId, database, objectType) => {
  const tree = catalogTreeRef.value
  if (!tree || !sourceId || !database || !objectType) return
  const key = getObjectGroupNodeKey(sourceId, database, objectType)
  const node = tree.getNode(key)
  if (!node?.loaded) return
  tree.updateKeyChildren(key, buildTableChildren(sourceId, database, objectType))
  nextTick(() => tree.filter(searchKeyword.value))
}

const refreshLoadedSchemaNodesInTree = () => {
  Object.keys(tableStore).forEach((sourceId) => {
    const dbMap = tableStore[sourceId]
    if (!dbMap || typeof dbMap !== 'object') return
    Object.keys(dbMap).forEach((schemaName) => {
      refreshSchemaChildrenInTree(sourceId, schemaName)
      refreshObjectGroupChildrenInTree(sourceId, schemaName, 'table')
      refreshObjectGroupChildrenInTree(sourceId, schemaName, 'view')
    })
  })
}

const reloadSchemaCountsForLoadedDatasources = async (keyword) => {
  const tree = catalogTreeRef.value
  if (!tree) return
  const loadedSources = dataSources.value
    .map((item) => String(item.id))
    .filter((sourceId) => tree.getNode(getDatasourceNodeKey(sourceId))?.loaded)
  if (!loadedSources.length) return
  await Promise.allSettled(
    loadedSources.map((sourceId) => loadSchemaCounts(sourceId, keyword, true))
  )
}

const filterCatalogNode = (value, data) => {
  if (!value) return true
  const keyword = String(value).toLowerCase()
  if (data?.type === 'datasource') {
    const nameMatched = String(data?.name || '').toLowerCase().includes(keyword)
    if (nameMatched) return true
    const schemas = schemaStore[String(data.sourceId)] || []
    return schemas.some((schemaName) => getTableCount(data.sourceId, schemaName) > 0)
  }
  if (data?.type === 'schema') {
    const nameMatched = String(data?.name || '').toLowerCase().includes(keyword)
    if (nameMatched) return true
    return getTableCount(data.sourceId, data.schemaName) > 0
  }
  if (data?.type === 'object_group') {
    const nameMatched = String(data?.name || '').toLowerCase().includes(keyword)
    if (nameMatched) return true
    return getTableCountByType(data.sourceId, data.schemaName, data.objectType) > 0
  }
  if (data?.type === 'table') {
    const name = String(data.table?.tableName || data.name || '').toLowerCase()
    const comment = String(data.table?.tableComment || '').toLowerCase()
    return name.includes(keyword) || comment.includes(keyword)
  }
  return String(data?.name || '').toLowerCase().includes(keyword)
}

const loadCatalogNode = async (node, resolve, reject) => {
  const data = node?.data
  if (!data?.type) {
    resolve([])
    return
  }

  if (data.type === 'datasource') {
    const ok = await loadSchemas(data.sourceId)
    if (!ok) {
      reject?.()
      return
    }
    const schemas = schemaStore[String(data.sourceId)] || []
    resolve(schemas.map((schemaName) => buildSchemaNode(data.sourceId, schemaName)))
    nextTick(() => catalogTreeRef.value?.filter(searchKeyword.value))
    return
  }

  if (data.type === 'schema') {
    resolve(buildSchemaChildren(data.sourceId, data.schemaName))
    nextTick(() => catalogTreeRef.value?.filter(searchKeyword.value))
    return
  }

  if (data.type === 'object_group') {
    // Keep current expand transition stable: do not rebuild schema/group nodes
    // while this group is being lazily expanded.
    const ok = await loadTables(data.sourceId, data.schemaName, false, false)
    if (!ok) {
      reject?.()
      return
    }
    resolve(buildTableChildren(data.sourceId, data.schemaName, data.objectType))
    nextTick(() => catalogTreeRef.value?.filter(searchKeyword.value))
    return
  }

  resolve([])
}

const isExpandIconClick = (event) => {
  const target = event?.target
  if (!target || typeof target.closest !== 'function') return false
  return Boolean(target.closest('.el-tree-node__expand-icon'))
}

const handleCatalogNodeClick = async (data, _node, _component, event) => {
  if (!data) return
  if (isExpandIconClick(event)) return
  const currentTab = activeTab.value
    ? openTabs.value.find((item) => String(item.id) === String(activeTab.value))
    : null

  if (currentTab?.kind === 'query') {
    if (data.type === 'datasource') {
      await handleQuerySourceSelect(currentTab.id, data.sourceId)
      return
    }
    if (data.type === 'schema') {
      await handleQuerySourceSelect(currentTab.id, data.sourceId)
      await handleQueryDatabaseSelect(currentTab.id, data.schemaName)
      return
    }
    if (data.type === 'object_group') {
      await handleQuerySourceSelect(currentTab.id, data.sourceId)
      await handleQueryDatabaseSelect(currentTab.id, data.schemaName)
      return
    }
  }
  if (data.type === 'table') {
    openTableTab(data.table, data.schemaName, data.sourceId)
    return
  }
}

const expandCatalogNode = (key) => {
  return new Promise((resolve) => {
    const tree = catalogTreeRef.value
    if (!tree || !key) {
      resolve(false)
      return
    }
    const node = tree.getNode(key)
    if (!node) {
      resolve(false)
      return
    }
    if (node.expanded) {
      resolve(true)
      return
    }
    node.expand(() => resolve(true), true)
  })
}

const ensureCatalogPathExpanded = async (sourceId, schemaName) => {
  if (!catalogTreeRef.value || !sourceId) return
  await expandCatalogNode(getDatasourceNodeKey(sourceId))
  await nextTick()
  if (schemaName) {
    await expandCatalogNode(getSchemaNodeKey(sourceId, schemaName))
    await nextTick()
  }
}

const getSourceName = (sourceId) => {
  if (!sourceId) return ''
  const source = dataSources.value.find((item) => String(item.id) === String(sourceId))
  return source?.clusterName || source?.name || ''
}

const getTabSubtitle = (tab) => {
  if (!tab) return ''
  const sourceName = getSourceName(tab.sourceId)
  const dbName = tab.dbName || ''
  if (sourceName && dbName) {
    return `${sourceName} / ${dbName}`
  }
  return sourceName || dbName || ''
}

const getLayerType = (layer) => {
  const map = {
    ODS: 'info',
    DWD: 'success',
    DIM: 'warning',
    DWS: 'primary',
    ADS: 'danger'
  }
  return map[layer] || 'info'
}

const formatNumber = (num) => {
  if (num === null || num === undefined) return '-'
  const value = Number(num)
  if (Number.isNaN(value)) return num
  return value.toLocaleString('zh-CN')
}

const formatRowCount = (rowCount) => {
  if (rowCount === null || rowCount === undefined) return '-'
  if (rowCount === 0) return '0'
  if (rowCount < 1000) return rowCount.toString()
  if (rowCount < 1000000) return (rowCount / 1000).toFixed(1) + 'K'
  if (rowCount < 1000000000) return (rowCount / 1000000).toFixed(1) + 'M'
  return (rowCount / 1000000000).toFixed(1) + 'B'
}

const ensureClusterSelected = (table) => {
  if (isDorisTable(table) && !clusterId.value) {
    ElMessage.warning('请选择 Doris 集群')
    return false
  }
  return true
}

const isReplicaWarning = (value) => {
  if (value === null || value === undefined || value === '') return false
  const num = Number(value)
  return Number.isFinite(num) && num > 0 && num < 3
}

const isAggregateTable = (table) => {
  if (!table?.tableModel) return false
  return String(table.tableModel).toUpperCase() === 'AGGREGATE'
}

const hasText = (value) => value !== null && value !== undefined && String(value).trim() !== ''
const hasPositiveNumber = (value) => {
  const num = Number(value)
  return Number.isFinite(num) && num > 0
}

const getTableSourceType = (table) => {
  if (!table) return ''
  const explicitType = String(table.sourceType || table.datasourceType || table.dataSourceType || '')
    .trim()
    .toUpperCase()
  if (explicitType) return explicitType
  const sourceId = table.sourceId || table.clusterId || table.datasourceId
  if (!sourceId) return ''
  return String(getDatasourceById(sourceId)?.sourceType || '')
    .trim()
    .toUpperCase()
}

const isDorisTable = (table) => {
  if (!table) return false
  const sourceType = getTableSourceType(table)
  if (sourceType === 'MYSQL') return false
  if (sourceType === 'DORIS') return true
  if (table.isSynced === 1) return true
  return (
    hasText(table.tableModel) ||
    hasPositiveNumber(table.bucketNum) ||
    hasPositiveNumber(table.replicaNum) ||
    hasText(table.distributionColumn) ||
    hasText(table.keyColumns) ||
    hasText(table.partitionColumn)
  )
}

const MISSING_PLATFORM_METADATA_MESSAGE = '该表未同步到平台，需同步后才能操作'

const isPlatformMetadataMissing = (table) => {
  if (!table) return false
  if (table.metadataMissing === true || table.metadataStatus === 'missing') return true
  return isDorisTable(table) && !table.id && hasText(table.dbName) && hasText(table.tableName)
}

const warnPlatformMetadataMissing = (table) => {
  if (!isPlatformMetadataMissing(table)) return false
  ElMessage.warning(MISSING_PLATFORM_METADATA_MESSAGE)
  return true
}

const formatStorageSize = (size) => {
  if (size === null || size === undefined) return '-'
  if (size === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  let value = size
  let unitIndex = 0
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex++
  }
  return value >= 10 ? `${value.toFixed(0)} ${units[unitIndex]}` : `${value.toFixed(1)} ${units[unitIndex]}`
}

const formatDuration = (ms) => {
  if (!ms) return '0ms'
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(2)}s`
}

const formatDateTime = (value) => {
  if (!value) return '-'
  return String(value).replace('T', ' ').split('.')[0]
}

const INFO_TAB_NAME = 'info'
const RESULT_TYPE_RESULT_SET = 'RESULT_SET'
const RESULT_TYPE_UPDATE_COUNT = 'UPDATE_COUNT'

const EMPTY_RESULT_SET = Object.freeze({
  index: 1,
  statementIndex: 1,
  status: 'SUCCESS',
  resultType: RESULT_TYPE_RESULT_SET,
  affectedRows: null,
  message: '',
  sqlSnippet: '',
  durationMs: 0,
  columns: [],
  rows: [],
  hasMore: false,
  previewRowCount: 0
})

const splitSqlStatements = (sqlText) => {
  const text = String(sqlText || '')
  const statements = []
  let current = ''
  let inSingle = false
  let inDouble = false
  let inLineComment = false
  let inHashComment = false
  let inBlockComment = false

  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i]
    const next = text[i + 1] || ''

    if (inLineComment) {
      current += ch
      if (ch === '\n' || ch === '\r') inLineComment = false
      continue
    }
    if (inHashComment) {
      current += ch
      if (ch === '\n' || ch === '\r') inHashComment = false
      continue
    }
    if (inBlockComment) {
      current += ch
      if (ch === '*' && next === '/') {
        current += next
        inBlockComment = false
        i += 1
      }
      continue
    }
    if (inSingle) {
      current += ch
      if (ch === '\'' && next === '\'') {
        current += next
        i += 1
        continue
      }
      if (ch === '\'') inSingle = false
      continue
    }
    if (inDouble) {
      current += ch
      if (ch === '"' && next === '"') {
        current += next
        i += 1
        continue
      }
      if (ch === '"') inDouble = false
      continue
    }

    if (ch === '-' && next === '-') {
      inLineComment = true
      current += ch + next
      i += 1
      continue
    }
    if (ch === '#') {
      inHashComment = true
      current += ch
      continue
    }
    if (ch === '/' && next === '*') {
      inBlockComment = true
      current += ch + next
      i += 1
      continue
    }
    if (ch === '\'') {
      inSingle = true
      current += ch
      continue
    }
    if (ch === '"') {
      inDouble = true
      current += ch
      continue
    }

    if (ch === ';') {
      const stmt = current.trim()
      if (stmt) statements.push(stmt)
      current = ''
      continue
    }
    current += ch
  }

  const tail = current.trim()
  if (tail) statements.push(tail)
  return statements
}

const buildRunningStatementInfos = (sqlText) => {
  const statements = splitSqlStatements(sqlText)
  return statements.map((statement, idx) => ({
    statementIndex: idx + 1,
    status: idx === 0 ? 'RUNNING' : 'PENDING',
    durationMs: 0,
    sqlSnippet: abbreviateSql(statement),
    resultInfo: idx === 0 ? '正在执行' : '等待执行'
  }))
}

const buildStatementInfosFromResultSets = (resultSets) => {
  const sets = Array.isArray(resultSets) ? resultSets : []
  return sets.map((set, idx) => {
    const status = String(set?.status || (set?.resultType === 'ERROR' ? 'ERROR' : 'SUCCESS')).toUpperCase()
    let resultInfo = set?.message || ''
    if (!resultInfo) {
      if (String(set?.resultType || '') === RESULT_TYPE_UPDATE_COUNT) {
        const affected = set?.affectedRows
        resultInfo = affected === null || affected === undefined ? '语句执行成功' : `影响 ${affected} 行`
      } else {
        const rows = Array.isArray(set?.rows) ? set.rows.length : 0
        resultInfo = `返回 ${rows} 行`
      }
    }
    return {
      statementIndex: Number(set?.statementIndex || idx + 1),
      status,
      durationMs: Number(set?.durationMs || 0),
      sqlSnippet: set?.sqlSnippet || '',
      resultInfo
    }
  })
}

const getStatementStatusTagType = (status) => {
  const value = String(status || '').toUpperCase()
  if (value === 'SUCCESS') return 'success'
  if (value === 'RUNNING') return 'info'
  if (value === 'BLOCKED' || value === 'ERROR') return 'danger'
  if (value === 'SKIPPED') return 'warning'
  return 'info'
}

const abbreviateSql = (sqlText) => {
  const text = String(sqlText || '').replace(/\s+/g, ' ').trim()
  if (!text) return ''
  return text.length > 180 ? `${text.slice(0, 180)}...` : text
}

const isResultSetType = (resultSet) => String(resultSet?.resultType || RESULT_TYPE_RESULT_SET) === RESULT_TYPE_RESULT_SET

const getResultSetCountText = (resultSet) => {
  const type = String(resultSet?.resultType || RESULT_TYPE_RESULT_SET)
  if (type === RESULT_TYPE_UPDATE_COUNT) {
    const affected = resultSet?.affectedRows
    return affected === null || affected === undefined ? '影响行数未知' : `影响 ${affected} 行`
  }
  return `${(resultSet?.rows || []).length} 行`
}

const getResultSetAlertType = (resultSet) => {
  const status = String(resultSet?.status || '').toUpperCase()
  if (status === 'ERROR' || status === 'BLOCKED') return 'error'
  if (status === 'SKIPPED') return 'warning'
  return 'success'
}

const getDisplayResultSets = (tabId) => {
  const state = tabStates[tabId]
  const sets = Array.isArray(state?.queryResult?.resultSets) ? state.queryResult.resultSets : []
  return sets.length ? sets : [EMPTY_RESULT_SET]
}

const getTableRowCount = (table) => {
  if (!table) return 0
  const value = table.rowCount ?? table.tableRows ?? table.table_rows
  if (value === null || value === undefined) return 0
  return Number(value) || 0
}

const getTableStorageSize = (table) => {
  if (!table) return 0
  const value = table.storageSize ?? table.dataLength ?? table.data_length
  if (value === null || value === undefined) return 0
  return Number(value) || 0
}

const getProgressWidth = (sourceId, database, table) => {
  const sourceKey = String(sourceId || '')
  const list = tableStore[sourceKey]?.[database] || []
  if (!list.length) return '0%'
  const currentRowCount = getTableRowCount(table)
  const maxRowCount = Math.max(...list.map((item) => getTableRowCount(item)))
  if (!Number.isFinite(maxRowCount) || maxRowCount <= 0) {
    return '0%'
  }
  const percentage = Math.max(10, (currentRowCount / maxRowCount) * 100)
  return percentage.toFixed(1) + '%'
}

const getUpstreamCount = (tableId) => {
  if (!tableId) return 0
  return lineageCache[tableId]?.upstreamTables?.length || 0
}

const getDownstreamCount = (tableId) => {
  if (!tableId) return 0
  return lineageCache[tableId]?.downstreamTables?.length || 0
}

const getFieldRows = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return []
  return state.fieldsEditing ? state.fieldsDraft : state.fields
}

const loadLineageForTable = async (tableId) => {
  if (!tableId || lineageCache[tableId]) return
  try {
    const lineageData = await tableApi.getLineage(tableId)
    lineageCache[tableId] = lineageData || { upstreamTables: [], downstreamTables: [] }
  } catch (error) {
    console.error('加载数据血缘失败', error)
  }
}

const observeExistingTableRefs = () => {
  if (!tableObserver.value) return
  Object.values(tableRefs.value).forEach((el) => {
    const tableId = el?.dataset?.tableId
    if (tableId) {
      tableObserver.value.observe(el)
    }
  })
}

const setupTableObserver = () => {
  if (tableObserver.value) {
    tableObserver.value.disconnect()
  }
  tableObserver.value = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return
        const tableId = Number(entry.target.dataset.tableId)
        if (Number.isFinite(tableId)) {
          loadLineageForTable(tableId)
        }
        tableObserver.value?.unobserve(entry.target)
      })
    },
    {
      root: null,
      rootMargin: '100px',
      threshold: 0.1
    }
  )
  observeExistingTableRefs()
}

	const createTabState = (table) => {
	  return reactive({
	    table: { ...table },
		    query: {
		      sql: buildDefaultSql(table),
		      limit: 200,
		      hasSelection: false,
		      selectionText: ''
		    },
		    queryLoading: false,
		    queryStopping: false,
		    queryCancelable: false,
		    queryAbortController: null,
		    queryRunId: 0,
		    queryTiming: {
		      startedAt: 0,
		      elapsedMs: 0
		    },
		    queryResult: {
	      resultSets: [],
	      columns: [],
	      rows: [],
      hasMore: false,
	      durationMs: 0,
	      executedAt: '',
	      cancelled: false,
	      statementInfos: [],
	      message: '',
	      errorMessage: ''
		    },
				    resultTab: 'result-0',
			    resultViewTabs: ['table'],
			    page: {
			      current: 1,
			      size: 20
			    },
    charts: [
      {
        type: 'bar',
        xAxis: '',
        yAxis: []
      }
    ],
    metaTab: 'basic',
    metaEditing: false,
    metaSaving: false,
    metaForm: {
      tableName: table.tableName || '',
      tableComment: table.tableComment || '',
      layer: table.layer || '',
      businessDomain: table.businessDomain || '',
      dataDomain: table.dataDomain || '',
      owner: table.owner || '',
      bucketNum: table.bucketNum ?? '',
      replicaNum: table.replicaNum ?? ''
    },
    metaOriginal: {},
    metaDataDomainOptions: [],
    metadataSyncing: false,
    fieldSubmitting: false,
    fieldsEditing: false,
    fieldsDraft: [],
    fieldsRemoved: [],
    fields: [],
    ddl: '',
    ddlLoading: false,
    accessLoading: false,
    accessStats: null,
    accessError: '',
    lineage: { upstreamTables: [], downstreamTables: [] },
    tasks: { writeTasks: [], readTasks: [] },
    dataLoading: false,
    dataLoaded: false
  })
}

const openTableTab = async (table, dbFallback = '', sourceFallback = '') => {
  if (!table) return
  let payload = table
  const tableId = payload?.id
  const hasSource = !!(payload?.sourceId || payload?.clusterId || sourceFallback)
  const hasDb = !!(payload?.dbName || payload?.databaseName || payload?.database || dbFallback)
  if (tableId && (!hasSource || !hasDb)) {
    const cached = findCachedTableById(tableId)
    if (cached) {
      payload = { ...cached, ...payload, id: tableId, dbName: cached.dbName, sourceId: cached.sourceId }
    } else {
      try {
        const tableInfo = await tableApi.getById(tableId)
        if (tableInfo) {
          const resolvedSourceId = String(
            payload?.sourceId || payload?.clusterId || tableInfo.clusterId || sourceFallback || ''
          )
          const resolvedDb =
            tableInfo.dbName ||
            payload?.dbName ||
            payload?.databaseName ||
            payload?.database ||
            dbFallback ||
            ''
          payload = {
            ...tableInfo,
            ...payload,
            id: tableId,
            dbName: resolvedDb,
            sourceId: resolvedSourceId
          }
        }
      } catch (error) {
        console.error('加载血缘表信息失败', error)
      }
    }
  }

  const sourceId = String(payload.sourceId || payload.clusterId || sourceFallback || '')
  if (sourceId) {
    clusterId.value = sourceId
  }
  const resolvedSourceType = String(payload.sourceType || getDatasourceById(sourceId)?.sourceType || '').toUpperCase()
  const resolvedDb = payload.dbName || payload.databaseName || payload.database || dbFallback || ''
  payload = { ...payload, dbName: resolvedDb, sourceId, sourceType: resolvedSourceType }
  const key = getTableKey(payload, resolvedDb, sourceId)
  if (!key) return

  selectedTableKey.value = key

  const existing = openTabs.value.find((item) => String(item.id) === String(key))
  if (existing) {
    activeTab.value = String(existing.id)
    const state = tabStates[String(existing.id)]
    if (state) {
      state.table = { ...state.table, ...payload }
      syncAutoSelectSqlIfSchemaMismatch(state)
    }
    if (existing.kind !== 'query') {
      existing.sourceId = sourceId || existing.sourceId
      existing.sourceType = resolvedSourceType || existing.sourceType
      existing.dbName = resolvedDb || existing.dbName
      existing.tableName = payload.tableName || existing.tableName
    }
    await focusTableInSidebar(payload, key, resolvedDb, sourceId)
    return
  }

  const existingById = tableId
    ? openTabs.value.find((item) => {
        if (!item || item.kind === 'query') return false
        const state = tabStates[String(item.id)]
        return state?.table?.id && String(state.table.id) === String(tableId)
      })
    : null
  if (existingById) {
    activeTab.value = String(existingById.id)
    const state = tabStates[String(existingById.id)]
    if (state) {
      state.table = { ...state.table, ...payload }
      syncAutoSelectSqlIfSchemaMismatch(state)
    }
    existingById.sourceId = sourceId || existingById.sourceId
    existingById.sourceType = resolvedSourceType || existingById.sourceType
    existingById.dbName = resolvedDb || existingById.dbName
    existingById.tableName = payload.tableName || existingById.tableName
    selectedTableKey.value = key
    await focusTableInSidebar(payload, key, resolvedDb, sourceId)
    return
  }

  const tabItem = {
    id: key,
    kind: 'table',
    tableName: payload.tableName,
    dbName: resolvedDb,
    sourceId,
    sourceType: resolvedSourceType
  }
  tabStates[key] = createTabState({ ...payload, dbName: resolvedDb, sourceId })
  openTabs.value.push(tabItem)
  activeTab.value = key

  await focusTableInSidebar(payload, key, resolvedDb, sourceId)
  await loadTabData(key)
}

const refreshCatalog = async () => {
  if (dbLoading.value) return
  dbLoading.value = true
  try {
    const clusters = await dorisClusterApi.list()
    dataSources.value = Array.isArray(clusters) ? clusters : []
    const ids = new Set(dataSources.value.map((item) => String(item.id)))
    if (clusterId.value && !ids.has(String(clusterId.value))) {
      const fallback =
        dataSources.value.find((item) => item.isDefault === 1) || dataSources.value[0] || null
      clusterId.value = fallback?.id || null
    }
    if (activeSource.value && !ids.has(String(activeSource.value))) {
      const fallback =
        dataSources.value.find((item) => item.isDefault === 1) || dataSources.value[0] || null
      activeSource.value = fallback?.id ? String(fallback.id) : ''
    }
    await nextTick()
    const tree = catalogTreeRef.value
    if (!tree) return
    const loadedSources = dataSources.value
      .map((item) => String(item.id))
      .filter((sourceId) => tree.getNode(getDatasourceNodeKey(sourceId))?.loaded)

    for (const sourceId of loadedSources) {
      const ok = await loadSchemas(sourceId, true)
      if (!ok) continue
      const schemas = schemaStore[String(sourceId)] || []
      for (const schemaName of schemas) {
        const tableGroupLoaded = tree.getNode(getObjectGroupNodeKey(sourceId, schemaName, 'table'))?.loaded
        const viewGroupLoaded = tree.getNode(getObjectGroupNodeKey(sourceId, schemaName, 'view'))?.loaded
        if (tableGroupLoaded || viewGroupLoaded) {
          await loadTables(sourceId, schemaName, true)
        }
      }
    }
  } catch (error) {
    ElMessage.error('刷新目录失败')
  } finally {
    dbLoading.value = false
  }
}

const refreshDatasourceNode = async (nodeData) => {
  const sourceId = nodeData?.sourceId
  if (!sourceId) return
  if (dbLoading.value || schemaLoading[String(sourceId)]) return
  await loadSchemas(sourceId, true)
}

const refreshSchemaNode = async (nodeData) => {
  const sourceId = nodeData?.sourceId
  const schemaName = nodeData?.schemaName
  if (!sourceId || !schemaName) return
  const key = `${String(sourceId)}::${schemaName}`
  if (dbLoading.value || schemaCountLoading[String(sourceId)] || tableLoading[key]) return
  await loadSchemaCounts(sourceId, searchKeyword.value, true)
  if (isSchemaTablesLoaded(sourceId, schemaName)) {
    await loadTables(sourceId, schemaName, true)
  } else {
    nextTick(() => catalogTreeRef.value?.filter(searchKeyword.value))
  }
}

const focusTableInSidebar = async (table, key, dbFallback = '', sourceFallback = '') => {
  if (!table) return
  const sourceId = table.sourceId || table.clusterId || sourceFallback
  const dbName = table.dbName || table.databaseName || table.database || dbFallback
  if (sourceId) {
    activeSource.value = String(sourceId)
    await loadSchemas(sourceId)
  }
  if (sourceId && dbName) {
    activeSchema[String(sourceId)] = dbName
  }
  await nextTick()
  await ensureCatalogPathExpanded(sourceId, dbName)
  if (sourceId && dbName) {
    await loadTables(sourceId, dbName)
    const objectType = isViewTable(table) ? 'view' : 'table'
    await expandCatalogNode(getObjectGroupNodeKey(sourceId, dbName, objectType))
    await nextTick()
    if (!catalogTreeRef.value?.getNode(key)) {
      const fallbackType = objectType === 'view' ? 'table' : 'view'
      await expandCatalogNode(getObjectGroupNodeKey(sourceId, dbName, fallbackType))
    }
  }
  catalogTreeRef.value?.setCurrentKey(key)
  await nextTick()
  const ref = tableRefs.value[key]
  if (ref?.scrollIntoView) {
    ref.scrollIntoView({ block: 'nearest' })
  }
}

const focusActiveTableInSidebar = async () => {
  const currentTab = openTabs.value.find((item) => String(item.id) === String(activeTab.value))
  if (!currentTab || currentTab.kind !== 'table') return
  const state = tabStates[String(currentTab.id)]
  const table = state?.table
  if (!table) return
  const sourceId = String(table.sourceId || table.clusterId || currentTab.sourceId || '')
  const dbName = table.dbName || table.databaseName || table.database || currentTab.dbName || ''
  const payload = { ...table, sourceId, dbName }
  const key = getTableKey(payload, dbName, sourceId)
  if (!key) return
  selectedTableKey.value = key
  await focusTableInSidebar(payload, key, dbName, sourceId)
}

const syncRouteWithTab = (tab, tabId) => {
  if (suppressRouteSync.value) return
  if (!tab) return
  const kind = tab.kind === 'query' ? 'query' : 'table'
  const id = String(tabId ?? tab.id ?? '')

  const nextQuery = { ...route.query }
  if (id) nextQuery.tab = id
  if (tab.sourceId) nextQuery.clusterId = String(tab.sourceId)
  if (tab.dbName) nextQuery.database = String(tab.dbName)

  if (kind === 'table') {
    const tableId = tabStates[id]?.table?.id
    if (tableId) nextQuery.tableId = String(tableId)
    else delete nextQuery.tableId
    if (tab.tableName) nextQuery.tableName = String(tab.tableName)
  } else {
    delete nextQuery.tableId
    delete nextQuery.tableName
  }

  router.replace({ path: route.path, query: nextQuery })
}

const clearRouteTabQuery = () => {
  if (suppressRouteSync.value) return
  const nextQuery = { ...route.query }
  delete nextQuery.tab
  delete nextQuery.tableId
  delete nextQuery.tableName
  router.replace({ path: route.path, query: nextQuery })
}

const clearCreateQuery = () => {
  if (!route.query.create) return
  const nextQuery = { ...route.query }
  delete nextQuery.create
  router.replace({ path: route.path, query: nextQuery })
}

const syncFromRoute = async () => {
  const { clusterId: routeClusterId, database, tableId, tableName } = route.query
  if (!routeClusterId || !database || (!tableId && !tableName)) return
  const currentTab = openTabs.value.find((item) => String(item.id) === String(activeTab.value))
  if (currentTab) {
    const sameSource = String(currentTab.sourceId || '') === String(routeClusterId)
    const sameDb = String(currentTab.dbName || '') === String(database)
    const sameName = !tableName || String(currentTab.tableName || '') === String(tableName)
    const currentId = tabStates[String(currentTab.id)]?.table?.id
    const sameId = !tableId || (currentId && String(currentId) === String(tableId))
    if (sameSource && sameDb && sameName && sameId) return
  }
  activeSource.value = String(routeClusterId)
  activeSchema[String(routeClusterId)] = database
  await loadSchemas(routeClusterId, true)
  await loadTables(routeClusterId, database, true)
  const list = tableStore[String(routeClusterId)]?.[database] || []
  let target = null
  if (tableId) {
    target = list.find((item) => String(item.id) === String(tableId))
  }
  if (!target && tableName) {
    target = list.find((item) => item.tableName === tableName)
  }
  if (!target && tableId) {
    try {
      const tableInfo = await tableApi.getById(tableId)
      if (tableInfo) {
        target = { ...tableInfo, sourceId: String(routeClusterId), dbName: database }
      }
    } catch (error) {
      console.error('路由表加载失败', error)
    }
  }
  if (!target) return
  suppressRouteSync.value = true
  await openTableTab(target, database, routeClusterId)
  suppressRouteSync.value = false
}

const loadTabData = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.table) return
  if (state.dataLoading || state.dataLoaded) return

  state.dataLoading = true
  if (!state.table.id && state.table.dbName && state.table.tableName) {
    try {
      const sourceId = state.table.sourceId || clusterId.value
      const options = await tableApi.searchOptions({
        keyword: state.table.tableName,
        limit: 20,
        dbName: state.table.dbName,
        clusterId: sourceId || undefined
      })
      const match = (options || []).find((item) => item.tableName === state.table.tableName)
      if (match?.id) {
        state.table.id = match.id
        state.table.tableComment = state.table.tableComment || match.tableComment
        state.table.layer = state.table.layer || match.layer
        state.table.metadataMissing = false
        state.table.metadataStatus = 'synced'
      }
    } catch (error) {
      console.error('解析表元数据失败', error)
    }
  }
  if (!state.table.id) {
    state.metaForm = {
      tableName: state.table.tableName || '',
      tableComment: state.table.tableComment || '',
      layer: state.table.layer || '',
      businessDomain: state.table.businessDomain || '',
      dataDomain: state.table.dataDomain || '',
      owner: state.table.owner || '',
      bucketNum: state.table.bucketNum ?? '',
      replicaNum: state.table.replicaNum ?? ''
    }
    state.metaOriginal = { ...state.metaForm }
    state.metaDataDomainOptions = []
    if (state.metaForm.businessDomain) {
      await loadMetaDataDomainOptions(tabId, state.metaForm.businessDomain)
    }
    state.fields = []
    state.fieldsEditing = false
    state.fieldsDraft = []
    state.fieldsRemoved = []
    state.lineage = {
      upstreamTables: [],
      downstreamTables: [],
      edges: []
    }
    state.tasks = {
      writeTasks: [],
      readTasks: []
    }
    state.accessLoading = false
    state.accessStats = null
    state.accessError = ''
    if (state.query.sql === '') {
      state.query.sql = buildDefaultSql(state.table)
    }
    state.dataLoaded = true
    state.dataLoading = false
    return
  }
  try {
    const [tableInfo, fieldList, lineageData, tasksData, lineageGraphData] = await Promise.all([
      tableApi.getById(state.table.id),
      tableApi.getFields(state.table.id),
      tableApi.getLineage(state.table.id),
      tableApi.getTasks(state.table.id),
      lineageApi.getLineageGraph({ tableId: state.table.id, depth: 1 }).catch(() => null)
    ])
    state.table = { ...state.table, ...tableInfo }
    state.metaForm = {
      tableName: state.table.tableName || '',
      tableComment: state.table.tableComment || '',
      layer: state.table.layer || '',
      businessDomain: state.table.businessDomain || '',
      dataDomain: state.table.dataDomain || '',
      owner: state.table.owner || '',
      bucketNum: state.table.bucketNum ?? '',
      replicaNum: state.table.replicaNum ?? ''
    }
    state.metaDataDomainOptions = []
    if (state.metaForm.businessDomain) {
      await loadMetaDataDomainOptions(tabId, state.metaForm.businessDomain)
    }
    state.metaOriginal = { ...state.metaForm }
    state.fields = Array.isArray(fieldList) ? fieldList : []
    state.fieldsEditing = false
    state.fieldsDraft = []
    state.fieldsRemoved = []
    state.lineage = {
      upstreamTables: lineageData?.upstreamTables || [],
      downstreamTables: lineageData?.downstreamTables || [],
      edges: lineageGraphData?.edges || []
    }
    lineageCache[state.table.id] = state.lineage
    state.tasks = {
      writeTasks: Array.isArray(tasksData?.writeTasks) ? tasksData.writeTasks : [],
      readTasks: Array.isArray(tasksData?.readTasks) ? tasksData.readTasks : []
    }
    state.accessLoading = false
    state.accessStats = null
    state.accessError = ''
    if (state.query.sql === '') {
      state.query.sql = buildDefaultSql(state.table)
    }
    state.dataLoaded = true
  } catch (error) {
    console.error('加载表详情失败', error)
    state.dataLoaded = false
  } finally {
    state.dataLoading = false
  }
}

const hydrateRestoredTableTabs = () => {
  const tableTabIds = openTabs.value
    .filter((tab) => tab?.kind === 'table')
    .map((tab) => String(tab.id || ''))
    .filter(Boolean)
  if (!tableTabIds.length) return
  void Promise.allSettled(tableTabIds.map((tabId) => loadTabData(tabId)))
}

	const disposeTabResources = (tabId) => {
	  const id = String(tabId || '')
	  if (!id) return
	  clearQueryTimer(id)
	  disposeChart(id)
  if (leftPaneRefs.value?.[id]) {
    delete leftPaneRefs.value[id]
  }
	  if (leftPaneHeights[id] !== undefined) {
	    delete leftPaneHeights[id]
	  }
	  delete tabStates[id]
	  stopNowTickerIfIdle()
	}

const handleTabRemove = (name) => {
  const idx = openTabs.value.findIndex((tab) => String(tab.id) === String(name))
  if (idx === -1) return
  const removed = openTabs.value.splice(idx, 1)[0]
  if (removed) {
    disposeTabResources(removed.id)
  }
  if (openTabs.value.length) {
    activeTab.value = String(openTabs.value[Math.max(idx - 1, 0)].id)
  } else {
    activeTab.value = ''
  }
}

const handleCloseLeft = (tabKey) => {
  const idx = openTabs.value.findIndex((tab) => String(tab.id) === String(tabKey))
  if (idx <= 0) return
  const removed = openTabs.value.splice(0, idx)
  removed.forEach((tab) => disposeTabResources(tab.id))
  const stillActive = openTabs.value.some((tab) => String(tab.id) === String(activeTab.value))
  if (!stillActive) {
    activeTab.value = String(tabKey)
  }
}

const handleCloseRight = (tabKey) => {
  const idx = openTabs.value.findIndex((tab) => String(tab.id) === String(tabKey))
  if (idx === -1 || idx >= openTabs.value.length - 1) return
  const removed = openTabs.value.splice(idx + 1)
  removed.forEach((tab) => disposeTabResources(tab.id))
  const stillActive = openTabs.value.some((tab) => String(tab.id) === String(activeTab.value))
  if (!stillActive) {
    activeTab.value = String(tabKey)
  }
}

const handleCloseAll = () => {
  const removed = openTabs.value.splice(0)
  removed.forEach((tab) => disposeTabResources(tab.id))
  activeTab.value = ''
}

const resolveQuerySourceId = () => {
  const current = openTabs.value.find((tab) => String(tab.id) === String(activeTab.value))
  if (current?.sourceId) return String(current.sourceId)
  return ''
}

const resolveQueryDatabase = (sourceId) => {
  const sid = String(sourceId || '')
  if (!sid) return ''
  const current = openTabs.value.find((tab) => String(tab.id) === String(activeTab.value))
  if (current?.dbName) return String(current.dbName)
  return ''
}

const getTabInsertIndex = () => {
  const idx = openTabs.value.findIndex((tab) => String(tab.id) === String(activeTab.value))
  return idx === -1 ? openTabs.value.length : idx + 1
}

const handleTabAdd = async () => {
  const sourceId = resolveQuerySourceId()
  let dbName = resolveQueryDatabase(sourceId)
  if (sourceId) {
    await loadSchemas(sourceId)
    if (dbName && !getSchemaOptions(sourceId).includes(dbName)) {
      dbName = ''
    }
  }

  const queryId = `query:${Date.now()}`
  const tabItem = {
    id: queryId,
    kind: 'query',
    tableName: '无标题 - 查询',
    dbName,
    sourceId
  }
  tabStates[queryId] = createTabState({ tableName: '', dbName, sourceId })
  tabStates[queryId].query.sql = ''
  openTabs.value.splice(getTabInsertIndex(), 0, tabItem)
  activeTab.value = queryId
}

const getSchemaOptions = (sourceId) => {
  const sid = String(sourceId || '')
  if (!sid) return []
  return schemaStore[sid] || []
}

const getCompletionTablesBySchema = (sourceId) => {
  const sourceKey = String(sourceId || '')
  if (!sourceKey) return {}
  return tableStore[sourceKey] || {}
}

const getColumnCacheKey = (sourceId, schema, tableName) =>
  `${String(sourceId || '')}::${String(schema || '')}::${String(tableName || '')}`

const loadCompletionTables = async (sourceId, schema) => {
  const sourceKey = String(sourceId || '')
  const schemaName = String(schema || '')
  if (!sourceKey || !schemaName) return []
  await loadTables(sourceKey, schemaName)
  return tableStore[sourceKey]?.[schemaName] || []
}

const loadCompletionColumns = async (sourceId, schema, tableName) => {
  const sourceKey = String(sourceId || '')
  const schemaName = String(schema || '')
  const objectName = String(tableName || '')
  if (!sourceKey || !schemaName || !objectName) return []
  const cacheKey = getColumnCacheKey(sourceKey, schemaName, objectName)
  if (Array.isArray(columnStore[cacheKey])) {
    return columnStore[cacheKey]
  }
  try {
    const activated = await activateDatasource(sourceKey)
    if (!activated) return []
    const columns = await dorisClusterApi.getColumns(sourceKey, schemaName, objectName)
    columnStore[cacheKey] = Array.isArray(columns) ? columns : []
    return columnStore[cacheKey]
  } catch (error) {
    console.error('加载 SQL 补全字段失败', error)
    columnStore[cacheKey] = []
    return []
  }
}

const searchCompletionTables = async (sourceId, keyword) => {
  const sourceKey = String(sourceId || '')
  const normalizedKeyword = String(keyword || '').trim()
  if (!sourceKey || normalizedKeyword.length < 2) return []
  try {
    const activated = await activateDatasource(sourceKey)
    if (!activated) return []
    const objects = await dorisClusterApi.searchSchemaObjects(sourceKey, {
      keyword: normalizedKeyword,
      limit: 50
    })
    return Array.isArray(objects) ? objects : []
  } catch (error) {
    console.error('搜索 SQL 补全表失败', error)
    return []
  }
}

const getSqlCompletionTables = (tabId) => {
  const state = tabStates[String(tabId || '')]
  if (!state) return []
  const sourceId = String(state.table?.sourceId || '')
  const dbName = String(state.table?.dbName || '')
  if (!sourceId || !dbName) return []
  const list = tableStore[sourceId]?.[dbName] || []
  return list.map((item) => item.tableName).filter(Boolean)
}

const getSqlCompletionContext = (tabId) => {
  const state = tabStates[String(tabId || '')]
  if (!state) return null
  const sourceId = String(state.table?.sourceId || '')
  if (!sourceId) return null
  const currentSchema = String(state.table?.dbName || '')
  return {
    sourceId,
    currentSchema,
    schemas: getSchemaOptions(sourceId),
    tablesBySchema: getCompletionTablesBySchema(sourceId),
    loadTables: (schema) => loadCompletionTables(sourceId, schema),
    loadColumns: ({ schema, table }) => loadCompletionColumns(sourceId, schema, table),
    searchTables: (keyword) => searchCompletionTables(sourceId, keyword)
  }
}

const getTabItemById = (tabId) => {
  return openTabs.value.find((tab) => String(tab.id) === String(tabId)) || null
}

const handleSqlSelectionChange = (tabId, payload) => {
  const state = tabStates[String(tabId || '')]
  if (!state) return
  state.query.selectionText = payload?.text ?? ''
  state.query.hasSelection = !!payload?.hasSelection
}

const handleQuerySourceSelect = async (tabId, value) => {
  const state = tabStates[tabId]
  const tab = getTabItemById(tabId)
  if (!state || !tab || tab.kind !== 'query') return

  const sourceId = value ? String(value) : ''
  state.table.sourceId = sourceId
  tab.sourceId = sourceId

  state.table.dbName = ''
  state.table.tableName = ''
  state.table.id = undefined
  tab.dbName = ''

  if (String(activeTab.value) === String(tabId)) {
    clusterId.value = sourceId || null
    activeSource.value = sourceId
  }

  if (!sourceId) {
    if (String(activeTab.value) === String(tabId)) {
      syncRouteWithTab(tab, tabId)
    }
    return
  }

  const ok = await loadSchemas(sourceId)
  if (!ok) return

  const nextDb = activeSchema[sourceId] || schemaStore[sourceId]?.[0] || ''
  if (nextDb) {
    state.table.dbName = nextDb
    tab.dbName = nextDb
    activeSchema[sourceId] = nextDb
    await loadTables(sourceId, nextDb)
  }

  if (String(activeTab.value) === String(tabId)) {
    syncRouteWithTab(tab, tabId)
  }
}

const handleQueryDatabaseSelect = async (tabId, value) => {
  const state = tabStates[tabId]
  const tab = getTabItemById(tabId)
  if (!state || !tab || tab.kind !== 'query') return

  const dbName = value ? String(value) : ''
  state.table.dbName = dbName
  tab.dbName = dbName

  state.table.tableName = ''
  state.table.id = undefined

  const sourceId = String(state.table.sourceId || tab.sourceId || '')
  if (sourceId && dbName) {
    activeSchema[sourceId] = dbName
    await loadTables(sourceId, dbName)
  }

  if (String(activeTab.value) === String(tabId)) {
    clusterId.value = sourceId || null
    activeSource.value = sourceId
    syncRouteWithTab(tab, tabId)
  }
}

const buildDefaultSql = (table) => {
  if (!table?.dbName || !table?.tableName) return ''
  return `SELECT *\nFROM \`${table.dbName}\`.\`${table.tableName}\`\nLIMIT 200;`
}

const parseAutoSelectSql = (sql) => {
  const text = String(sql || '').trim()
  if (!text) return null
  const match = text.match(/^select\s+\*\s+from\s+`([^`]+)`\.`([^`]+)`\s+limit\s+(\d+)\s*;?$/i)
  if (!match) return null
  return { schema: match[1], table: match[2], limit: Number(match[3]) }
}

const syncAutoSelectSqlIfSchemaMismatch = (state) => {
  if (!state?.table?.dbName || !state?.table?.tableName) return
  const nextDefault = buildDefaultSql(state.table)
  if (!String(state.query?.sql || '').trim()) {
    state.query.sql = nextDefault
    return
  }
  const parsed = parseAutoSelectSql(state.query.sql)
  if (!parsed) return
  if (parsed.table === state.table.tableName && parsed.schema !== state.table.dbName) {
    state.query.sql = nextDefault
  }
}

	const executeQuery = async (tabId) => {
	  const state = tabStates[tabId]
	  if (!state) return
	  const runId = Number(state.queryRunId || 0) + 1
	  state.queryRunId = runId
	  const selectedSql = String(state?.query?.selectionText || '')
	  const sqlToRun = selectedSql.trim() ? selectedSql : String(state?.query?.sql || '')
  if (!sqlToRun.trim()) {
    state.queryResult.errorMessage = '请输入 SQL'
    state.queryResult.message = ''
    state.resultTab = INFO_TAB_NAME
    return
  }
  if (!state.table?.dbName) {
    state.queryResult.errorMessage = '请先选择数据库'
    state.queryResult.message = ''
    state.resultTab = INFO_TAB_NAME
    return
  }
  const sourceId = state.table?.sourceId || clusterId.value
	  if (!sourceId) {
	    state.queryResult.errorMessage = '请选择数据源'
	    state.queryResult.message = ''
	    state.resultTab = INFO_TAB_NAME
	    return
	  }

  let analyzeRes = null
  try {
    analyzeRes = await dataQueryApi.analyze({
      clientQueryId: String(tabId),
      clusterId: sourceId || undefined,
      database: state.table.dbName || undefined,
      sql: sqlToRun
    })
  } catch (error) {
    const message = error?.response?.data?.message || error?.message || 'SQL 分析失败'
    state.queryResult = {
      resultSets: [],
      columns: [],
      rows: [],
      hasMore: false,
      durationMs: 0,
      executedAt: '',
      cancelled: false,
      statementInfos: [
        {
          statementIndex: 1,
          status: 'ERROR',
          durationMs: 0,
          sqlSnippet: abbreviateSql(sqlToRun),
          resultInfo: message
        }
      ],
      message: '',
      errorMessage: message
    }
    state.resultTab = INFO_TAB_NAME
    return
  }

  const blockedRiskItem = Array.isArray(analyzeRes?.riskItems)
    ? analyzeRes.riskItems.find((item) => item?.blocked)
    : null
  const blockedStatementIndex = Number(blockedRiskItem?.statementIndex || 0) || null
  const confirmChallenges = Array.isArray(analyzeRes?.confirmChallenges)
    ? [...analyzeRes.confirmChallenges]
      .filter((item) => {
        const idx = Number(item?.statementIndex || 0)
        return !blockedStatementIndex || (idx > 0 && idx < blockedStatementIndex)
      })
      .sort((a, b) => Number(a?.statementIndex || 0) - Number(b?.statementIndex || 0))
    : []
  const confirmations = []
  for (const challenge of confirmChallenges) {
    const expected = String(challenge?.targetObject || '').trim()
    try {
      const { value } = await ElMessageBox.prompt(
        `语句 #${challenge.statementIndex} 为高风险操作，请输入对象名确认执行：${expected}`,
        '高风险 SQL 强确认',
        {
          type: 'warning',
          confirmButtonText: '确认执行',
          cancelButtonText: '取消',
          inputValue: '',
          inputPlaceholder: expected,
          inputValidator: (input) => {
            if (String(input || '').trim() !== expected) {
              return `请输入对象名：${expected}`
            }
            return true
          }
        }
      )
      confirmations.push({
        statementIndex: Number(challenge?.statementIndex || 0),
        targetObject: expected,
        inputText: String(value || '').trim(),
        confirmToken: challenge?.confirmToken || ''
      })
    } catch (error) {
      if (error === 'cancel' || error === 'close') {
        break
      }
      const message = error?.response?.data?.message || error?.message || '强确认失败'
      state.queryResult = {
        resultSets: [],
        columns: [],
        rows: [],
        hasMore: false,
        durationMs: 0,
        executedAt: '',
        cancelled: false,
        statementInfos: [
          {
            statementIndex: Number(challenge?.statementIndex || 1),
            status: 'ERROR',
            durationMs: 0,
            sqlSnippet: challenge?.targetObject || abbreviateSql(sqlToRun),
            resultInfo: message
          }
        ],
        message: '',
        errorMessage: message
      }
      state.resultTab = INFO_TAB_NAME
      return
    }
  }

		  if (state.queryAbortController) {
	    try {
	      state.queryAbortController.abort()
	    } catch (_) {
	      // ignored
	    }
	  }
	  state.queryAbortController = new AbortController()
	  state.queryLoading = true
	  state.queryStopping = false
	  state.queryCancelable = true
	  startNowTicker()
	  state.queryResult.errorMessage = ''
	  state.queryResult.message = ''
	  state.queryResult.cancelled = false
	  state.queryResult.statementInfos = buildRunningStatementInfos(sqlToRun)
	  state.resultTab = INFO_TAB_NAME
	  startQueryTimer(tabId)
	  disposeChart(tabId)
	  try {
	    const res = await dataQueryApi.execute({
	      clientQueryId: String(tabId),
	      clusterId: sourceId || undefined,
	      database: state.table.dbName || undefined,
	      sql: sqlToRun,
	      limit: state.query.limit,
	      confirmations
	    }, { signal: state.queryAbortController?.signal })
	    if (state.queryRunId !== runId) return

	    const resultSets = Array.isArray(res.resultSets) ? res.resultSets : []
    const fallbackResultSet = {
      index: 1,
      statementIndex: 1,
      status: 'SUCCESS',
      resultType: RESULT_TYPE_RESULT_SET,
      affectedRows: null,
      message: res.message || '',
      sqlSnippet: abbreviateSql(sqlToRun),
      durationMs: Number(res.durationMs || 0),
      columns: res.columns || [],
      rows: res.rows || [],
      hasMore: !!res.hasMore,
      previewRowCount: (res.rows || []).length
    }
    const normalizedSets = normalizeResultSetsForDisplay(resultSets.length ? resultSets : [fallbackResultSet], tabId)
    const statementInfos = buildStatementInfosFromResultSets(normalizedSets)
    const hasFailure = normalizedSets.some((item) => {
      const status = String(item?.status || '').toUpperCase()
      return status === 'BLOCKED' || status === 'ERROR' || status === 'SKIPPED'
    })

		    state.queryResult = {
		      resultSets: normalizedSets,
		      columns: normalizedSets[0]?.columns || [],
		      rows: normalizedSets[0]?.rows || [],
      hasMore: res.hasMore,
      durationMs: res.durationMs,
      executedAt: res.executedAt,
      cancelled: !!res.cancelled,
		      statementInfos,
		      message: res.message || '',
		      errorMessage: ''
		    }
		    state.queryCancelable = false
		    state.queryAbortController = null
		    stopNowTickerIfIdle()
		    state.page.current = 1
		    state.resultTab = !res.cancelled && !hasFailure ? 'result-0' : INFO_TAB_NAME
			    state.charts = normalizedSets.map(() => ({
			      type: 'bar',
		      xAxis: '',
		      yAxis: []
		    }))
			    state.resultViewTabs = normalizedSets.map((_, idx) => state.resultViewTabs?.[idx] || 'table')
			    applyDefaultChartSelection(tabId)
			    await nextTick()
			    syncResultPaneLayout(tabId)
			    fetchHistory()
		  } catch (error) {
		    if (state.queryRunId !== runId) return
		    const isCanceled =
		      String(error?.code || '') === 'ERR_CANCELED' ||
		      String(error?.name || '') === 'CanceledError' ||
		      /canceled/i.test(String(error?.message || ''))
		    if (isCanceled) {
		      return
		    }
		    const message = error?.response?.data?.message || error?.message || '查询失败'
		    const hasResponse = !!error?.response
		    const maybeStillRunning = !hasResponse
		    if (!maybeStillRunning) {
		      state.queryCancelable = false
		    }
		    if (!state.queryCancelable) {
		      state.queryAbortController = null
		      stopNowTickerIfIdle()
		    }
		    state.queryResult = {
	      resultSets: [],
	      columns: [],
	      rows: [],
      hasMore: false,
	      durationMs: 0,
	      executedAt: '',
	      cancelled: false,
		      statementInfos: maybeStillRunning
		        ? (Array.isArray(state.queryResult?.statementInfos) ? state.queryResult.statementInfos : buildRunningStatementInfos(sqlToRun))
		        : [
		          {
		            statementIndex: 1,
		            status: 'ERROR',
		            durationMs: 0,
		            sqlSnippet: abbreviateSql(sqlToRun),
		            resultInfo: message
		          }
		        ],
		      message: maybeStillRunning ? '查询请求超时/网络异常，可能仍在执行，可点击“停止”' : '',
		      errorMessage: message
		    }
			    state.resultTab = INFO_TAB_NAME
		    state.charts = [
		      {
		        type: 'bar',
		        xAxis: '',
		        yAxis: []
		      }
			    ]
			    state.resultViewTabs = ['table']
				  } finally {
			    if (state.queryRunId !== runId) return
			    state.queryLoading = false
			    if (!state.queryCancelable) {
			      clearQueryTimer(tabId)
			    }
			  }
	}

	const stopQuery = async (tabId) => {
	  const state = tabStates[tabId]
	  if (!state?.queryCancelable || state.queryStopping) return
	  state.queryStopping = true
	  try {
	    state.queryAbortController?.abort()
	  } catch (_) {
	    // ignored
	  }
	  state.queryAbortController = null
	  try {
	    await dataQueryApi.stop({ clientQueryId: String(tabId) })
	    state.queryCancelable = false
	    state.queryLoading = false
	    state.queryStopping = false
	    clearQueryTimer(tabId)
	    stopNowTickerIfIdle()
	    state.queryResult.cancelled = true
	    state.queryResult.message = '查询已停止'
	    state.queryResult.errorMessage = ''
	    const existingInfos = Array.isArray(state.queryResult.statementInfos) ? state.queryResult.statementInfos : []
	    state.queryResult.statementInfos = existingInfos.map((item, idx) => {
	      const status = String(item?.status || '').toUpperCase()
	      if (status === 'SUCCESS' || status === 'ERROR' || status === 'BLOCKED') return item
	      return {
	        statementIndex: Number(item?.statementIndex || idx + 1),
	        status: 'SKIPPED',
	        durationMs: Number(item?.durationMs || 0),
	        sqlSnippet: item?.sqlSnippet || '',
	        resultInfo: '查询已停止'
	      }
	    })
	    state.resultTab = INFO_TAB_NAME
	  } catch (error) {
	    state.queryStopping = false
	    const message = error?.response?.data?.message || error?.message || '停止失败'
	    state.queryResult.errorMessage = message
	    state.queryResult.message = ''
	    state.resultTab = INFO_TAB_NAME
	  }
	}

	const getLiveDurationMs = (tabId) => {
	  const state = tabStates[String(tabId || '')]
	  if (!state) return 0
	  if (state.queryCancelable) {
	    const startedAt = Number(state.queryTiming?.startedAt || 0)
	    if (!Number.isFinite(startedAt) || startedAt <= 0) return 0
	    return Math.max(0, nowTick.value - startedAt)
	  }
	  return Number(state.queryResult?.durationMs || 0)
	}

const resetQuery = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return
  state.query.sql = buildDefaultSql(state.table)
  state.query.selectionText = ''
  state.query.hasSelection = false
}

const saveAsTask = (tabId) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('保存查询任务')
    return
  }
  const state = tabStates[tabId]
  if (!state?.query?.sql?.trim()) {
    ElMessage.warning('请先输入 SQL')
    return
  }
  const sourceId = state?.table?.sourceId || clusterId.value || ''
  taskDrawerRef.value?.open(null, {
    taskSql: state.query.sql,
    taskName: `新建查询任务_${Date.now()}`,
    taskDesc: `From DataStudio\nCluster: ${sourceId}\nDatabase: ${state.table.dbName || ''}`
  })
}

const exportResult = (tabId, resultIndex = 0) => {
  const state = tabStates[tabId]
  if (!state?.queryResult) return
  const idx = Number(resultIndex)
  const set = Array.isArray(state.queryResult.resultSets) ? state.queryResult.resultSets[idx] : null
  const columns = set?.columns || state.queryResult.columns || []
  const rows = set?.rows || state.queryResult.rows || []
  if (!rows.length || !columns.length) return

  const blob = new Blob([buildCsvContent(columns, rows)], { type: 'text/csv;charset=utf-8;' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `export_${Date.now()}.csv`
  link.click()
}

const fetchHistory = async () => {
  historyLoading.value = true
  try {
    const res = await dataQueryApi.history({
      pageNum: historyPager.pageNum,
      pageSize: historyPager.pageSize
    })
    historyData.value = res.records || []
    historyPager.total = res.total || 0
  } catch (error) {
    console.error('加载历史查询失败', error)
  } finally {
    historyLoading.value = false
  }
}

const applyHistory = (row, tabId) => {
  const state = tabStates[tabId]
  if (!state || !row) return
  state.query.sql = row.sqlText || ''
  if (row.clusterId) {
    const sourceId = String(row.clusterId)
    clusterId.value = row.clusterId
    activeSource.value = sourceId
    loadSchemas(row.clusterId)
    const tab = getTabItemById(tabId)
    if (tab?.kind === 'query') {
      tab.sourceId = sourceId
      state.table.sourceId = sourceId
      if (String(activeTab.value) === String(tabId)) {
        syncRouteWithTab(tab, tabId)
      }
    }
  }
}

const handleCreateTable = () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('新建表')
    return
  }
  createDrawerVisible.value = true
}

const handleDeleteTable = async () => {
  if (isDemoMode) {
    showDemoReadonlyMessage('删除表')
    return
  }
  const active = activeTab.value
  const state = active ? tabStates[active] : null
  const table = state?.table
  if (warnPlatformMetadataMissing(table)) {
    return
  }
  if (!table?.id) {
    ElMessage.warning('请先选择要删除的表')
    return
  }
  const dorisTable = isDorisTable(table)
  if (dorisTable && !clusterId.value) {
    ElMessage.warning('请选择 Doris 集群')
    return
  }

  try {
    const rawName = String(table.tableName || '').trim()
    const expectedName = dorisTable ? (rawName.includes('.') ? rawName.split('.').pop() : rawName) : rawName
    const message = dorisTable
      ? `确定要删除表 “${table.tableName}” 吗？删除后将重命名为 deprecated_时间戳，数据不会丢失。`
      : `确定要删除表 “${table.tableName}” 吗？将仅删除平台元数据记录。`
    const { value } = await ElMessageBox.prompt(
      `${message}\n请输入表名以确认：${expectedName}`,
      '删除表确认',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        inputPlaceholder: expectedName,
        inputValidator: (input) => {
          if (String(input || '').trim() !== expectedName) {
            return `请输入正确表名：${expectedName}`
          }
          return true
        }
      }
    )
    const confirmTableName = String(value || '').trim()
    if (dorisTable) {
      await tableApi.softDelete(table.id, clusterId.value || null, confirmTableName)
    } else {
      await tableApi.delete(table.id, confirmTableName)
    }
    ElMessage.success('删除表成功')
    const dbName = table.dbName || table.databaseName || table.database
    if (dbName) {
      const sourceId = table.sourceId || clusterId.value
      if (sourceId) {
        await loadTables(sourceId, dbName, true)
      }
    }
    handleTabRemove(active)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error('删除表失败')
    }
  }
}

const syncMissingTableMetadata = async (tabId) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('同步平台元数据')
    return
  }
  const state = tabStates[String(tabId || '')]
  const table = state?.table
  if (!isPlatformMetadataMissing(table)) return
  const sourceId = String(table.sourceId || table.clusterId || clusterId.value || '')
  const dbName = table.dbName || table.databaseName || table.database || ''
  const tableName = table.tableName || ''
  if (!sourceId || !dbName || !tableName) {
    ElMessage.warning('缺少数据源、数据库或表名，无法同步')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定将 “${dbName}.${tableName}” 同步到平台元数据吗？`,
      '同步平台元数据',
      {
        type: 'warning',
        confirmButtonText: '立即同步',
        cancelButtonText: '取消'
      }
    )
  } catch (error) {
    return
  }

  state.metadataSyncing = true
  try {
    const response = await tableApi.syncTableMetadataByName(dbName, tableName, sourceId)
    await loadTables(sourceId, dbName, true)
    const list = tableStore[String(sourceId)]?.[dbName] || []
    const synced = list.find((item) => {
      if (!item) return false
      if (response?.tableId && String(item.id) === String(response.tableId)) return true
      return item.tableName === tableName
    })
    const syncedTable = synced || {
      ...table,
      id: response?.tableId || table.id,
      metadataMissing: false,
      metadataStatus: 'synced'
    }
    state.table = {
      ...state.table,
      ...syncedTable,
      sourceId,
      dbName,
      metadataMissing: false,
      metadataStatus: 'synced'
    }
    const tab = openTabs.value.find((item) => String(item.id) === String(tabId))
    if (tab) {
      tab.sourceId = sourceId
      tab.dbName = dbName
      tab.tableName = tableName
    }
    state.dataLoaded = false
    await loadTabData(String(tabId))
    ElMessage.success('平台元数据已同步')
  } catch (error) {
    ElMessage.error('同步平台元数据失败')
  } finally {
    state.metadataSyncing = false
  }
}

const handleCreateSuccess = async (result) => {
  createDrawerVisible.value = false
  await loadClusters()
  const tableId = result?.id || result?.tableId
  if (!tableId) return
  try {
    const table = await tableApi.getById(tableId)
    const dbName = table?.dbName || table?.databaseName || table?.database || ''
    if (dbName) {
      if (table?.sourceId || clusterId.value) {
        const sourceId = table.sourceId || clusterId.value
        await loadTables(sourceId, dbName, true)
      }
    }
    await openTableTab(table, dbName, table?.sourceId || clusterId.value)
  } catch (error) {
    console.error('加载新建表失败', error)
  }
}


const getPaginatedRows = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return []
  const start = (state.page.current - 1) * state.page.size
  const end = start + state.page.size
  return state.queryResult.rows.slice(start, end)
}

const parseResultTabIndex = (value) => {
  const match = String(value || '').match(/^result-(\d+)$/)
  return match ? Number(match[1]) : null
}

const getChartKey = (tabId, resultIndex) => `${String(tabId)}::${Number(resultIndex)}`
const getResultRowKeyPrefix = (tabId, resultIndex) => `${String(tabId)}::${Number(resultIndex)}`

const normalizeResultSetForDisplay = (resultSet, tabId, resultIndex) => {
  const rows = Array.isArray(resultSet?.rows) ? resultSet.rows : []
  const columns = Array.isArray(resultSet?.columns) ? resultSet.columns : []
  return markRaw({
    ...resultSet,
    columns,
    rows: markRaw(buildResultGridRows(rows, getResultRowKeyPrefix(tabId, resultIndex))),
    hasMore: !!resultSet?.hasMore,
    previewRowCount: Number.isFinite(Number(resultSet?.previewRowCount))
      ? Number(resultSet.previewRowCount)
      : rows.length
  })
}

const normalizeResultSetsForDisplay = (resultSets, tabId) => {
  const sets = Array.isArray(resultSets) ? resultSets : []
  return markRaw(sets.map((set, idx) => normalizeResultSetForDisplay(set, tabId, idx)))
}

	const getResultSetByIndex = (tabId, resultIndex = 0) => {
	  const state = tabStates[tabId]
  const sets = Array.isArray(state?.queryResult?.resultSets) ? state.queryResult.resultSets : []
  const set = sets[resultIndex] || EMPTY_RESULT_SET
  return {
    columns: Array.isArray(set?.columns) ? set.columns : [],
    rows: Array.isArray(set?.rows) ? set.rows : [],
    hasMore: !!set?.hasMore
  }
}

const getNumericColumns = (tabId, resultIndex = 0) => {
  const set = getResultSetByIndex(tabId, resultIndex)
  if (!set.rows.length || !set.columns.length) return []
  const sample = set.rows.slice(0, 10)
  return set.columns.filter((col) => {
    return sample.every((row) => {
      const val = row?.[col]
      return val === null || val === '' || !Number.isNaN(Number(val))
    })
  })
}

const scoreColumnName = (name, keywords) => {
  if (!name) return 0
  const lower = String(name).toLowerCase()
  return keywords.reduce((score, keyword) => (lower.includes(keyword) ? score + 10 : score), 0)
}

const scoreDimensionColumn = (column) => {
  const keywords = [
    'dt', 'date', 'day', 'week', 'month', 'year', 'hour', 'time',
    'category', 'type', 'name', 'region', 'province', 'city', 'status'
  ]
  const suffixBoost = /(_dt|_date|_day|_month|_year|_time)$/i.test(String(column)) ? 15 : 0
  return scoreColumnName(column, keywords) + suffixBoost
}

const scoreMetricColumn = (column) => {
  const keywords = [
    'count', 'cnt', 'sum', 'avg', 'mean', 'max', 'min',
    'total', 'num', 'qty', 'amount', 'amt', 'value', 'rate', 'ratio', 'pct', 'percent',
    '数量', '金额', '总', '均', '最大', '最小', '比率', '比例'
  ]
  const suffixBoost = /(_cnt|_count|_sum|_avg|_max|_min|_total)$/i.test(String(column)) ? 15 : 0
  return scoreColumnName(column, keywords) + suffixBoost
}

const applyDefaultChartSelection = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return

  const sets = Array.isArray(state?.queryResult?.resultSets) ? state.queryResult.resultSets : []
  sets.forEach((set, idx) => {
    const columns = Array.isArray(set?.columns) ? set.columns : []
    const rows = Array.isArray(set?.rows) ? set.rows : []
    if (!columns.length || rows.length === 0) return

    const chart = state.charts?.[idx]
    if (!chart) return
    if (chart.xAxis || (Array.isArray(chart.yAxis) && chart.yAxis.length)) return

    const numericColumns = getNumericColumns(tabId, idx)
    if (!numericColumns.length || columns.length < 2) return

    const dimensionCandidates = columns.filter((col) => !numericColumns.includes(col))
    const xCandidates = dimensionCandidates.length ? dimensionCandidates : columns
    const xAxis = xCandidates
      .map((col, order) => ({ col, order, score: scoreDimensionColumn(col) }))
      .sort((a, b) => (b.score - a.score) || (a.order - b.order))[0]?.col

    const metricCandidates = numericColumns.filter((col) => col !== xAxis)
    if (!xAxis || !metricCandidates.length) return

    const yAxis = metricCandidates
      .map((col, order) => ({ col, order, score: scoreMetricColumn(col) }))
      .sort((a, b) => (b.score - a.score) || (a.order - b.order))[0]?.col

    if (!yAxis) return

    chart.xAxis = xAxis
    chart.yAxis = [yAxis]
  })
}

const canRenderChart = (tabId, resultIndex = 0) => {
  const state = tabStates[tabId]
  if (!state) return false
  const set = getResultSetByIndex(tabId, resultIndex)
  const chart = state.charts?.[resultIndex]
  return (
    set.rows.length > 0 &&
    !!chart?.xAxis &&
    Array.isArray(chart?.yAxis) &&
    chart.yAxis.length > 0
  )
}

const setChartRef = (tabId, resultIndex, el) => {
  if (!tabId || el == null) return
  const key = getChartKey(tabId, resultIndex)
  chartRefs.value[key] = el

  // ECharts may capture wheel events and block scrolling the result pane.
  // Stop propagation in capture phase so outer scroll can work naturally.
  if (el?.dataset?.scrollGuard !== '1') {
    el.dataset.scrollGuard = '1'
	    el.addEventListener(
	      'wheel',
	      (event) => {
	        event.stopPropagation()
	      },
	      { capture: true, passive: true }
	    )
	  }
	}

const syncResultPaneLayout = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return
  const idx = parseResultTabIndex(state?.resultTab)
  if (idx === null) return
  const view = state?.resultViewTabs?.[idx] || 'table'
  if (view === 'chart') {
    chartInstances.get(getChartKey(tabId, idx))?.resize()
  }
}

const renderChart = async (tabId, resultIndex = 0) => {
  const state = tabStates[tabId]
  if (!state) return
  const key = getChartKey(tabId, resultIndex)
  const container = chartRefs.value[key]
  if (!container) return

  const set = getResultSetByIndex(tabId, resultIndex)
  const chart = state.charts?.[resultIndex]
  if (!chart) return

  const shouldRender = canRenderChart(tabId, resultIndex)
  let instance = chartInstances.get(key)
  if (!shouldRender) {
    instance?.clear()
    return
  }
  if (!instance) {
    const echarts = await loadEcharts()
    if (!chartRefs.value[key] || chartRefs.value[key] !== container || !container.isConnected) {
      return
    }
    if (!canRenderChart(tabId, resultIndex)) {
      return
    }
    instance = echarts.init(container)
    chartInstances.set(key, instance)
  }

  if (chart.type === 'pie') {
    const xKey = chart.xAxis
    const yKey = chart.yAxis[0]
    if (!xKey || !yKey) {
      instance.clear()
      return
    }
    const data = set.rows.map((row) => ({
      name: row?.[xKey],
      value: Number(row?.[yKey] || 0)
    }))
    instance.clear()
    instance.setOption({
      tooltip: { trigger: 'item' },
      legend: { bottom: 0 },
      series: [
        {
          type: 'pie',
          radius: ['20%', '65%'],
          data
        }
      ]
    })
    instance.resize()
    return
  }

  const xData = set.rows.map((row) => row?.[chart.xAxis])
  const series = chart.yAxis.map((keyName) => ({
    name: keyName,
    type: chart.type,
    data: set.rows.map((row) => Number(row?.[keyName] || 0)),
    smooth: chart.type === 'line'
  }))
  instance.clear()
  instance.setOption({
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { top: 40, left: 50, right: 30, bottom: 60, containLabel: true },
    xAxis: { type: 'category', data: xData },
    yAxis: { type: 'value' },
    series
  })
  instance.resize()
}

const disposeChart = (tabId, resultIndex = null) => {
  const id = String(tabId || '')
  if (!id) return
  if (resultIndex !== null && resultIndex !== undefined) {
    const key = getChartKey(id, resultIndex)
    const instance = chartInstances.get(key)
    if (instance) {
      instance.dispose()
      chartInstances.delete(key)
    }
    if (chartRefs.value?.[key]) {
      delete chartRefs.value[key]
    }
    return
  }

  const prefix = `${id}::`
  Array.from(chartInstances.keys()).forEach((key) => {
    if (!String(key).startsWith(prefix)) return
    const instance = chartInstances.get(key)
    if (instance) {
      instance.dispose()
    }
    chartInstances.delete(key)
  })
  Object.keys(chartRefs.value).forEach((key) => {
    if (String(key).startsWith(prefix)) {
      delete chartRefs.value[key]
    }
  })
}

const handleResize = () => {
  normalizePaneRatios()
  const tabId = activeTab.value
  if (!tabId) return
  syncResultPaneLayout(tabId)
}

const startMetaEdit = async (tabId) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('编辑表信息')
    return
  }
  const state = tabStates[tabId]
  if (!state) return
  if (warnPlatformMetadataMissing(state.table)) return
  if (!ensureClusterSelected(state.table)) return
  if (!businessDomainOptions.value.length) {
    await loadBusinessDomains()
  }
  await loadMetaDataDomainOptions(tabId, state.metaForm.businessDomain)
  state.metaEditing = true
  state.metaForm = { ...state.metaForm }
}

const cancelMetaEdit = async (tabId) => {
  const state = tabStates[tabId]
  if (!state) return
  state.metaEditing = false
  state.metaForm = { ...state.metaOriginal }
  await loadMetaDataDomainOptions(tabId, state.metaForm.businessDomain)
}

const saveMetaEdit = async (tabId) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('保存表信息')
    return
  }
  const state = tabStates[tabId]
  if (warnPlatformMetadataMissing(state?.table)) return
  if (!state?.table?.id) return
  if (!ensureClusterSelected(state.table)) return
  if (!state.metaForm.layer) {
    ElMessage.warning('请选择数据分层')
    return
  }
  try {
    await ElMessageBox.confirm('确认保存表信息与 Doris 配置的修改吗？', '提示', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消'
    })
  } catch (error) {
    return
  }
  state.metaSaving = true
  try {
    const payload = {
      tableName: state.metaForm.tableName,
      tableComment: state.metaForm.tableComment,
      layer: state.metaForm.layer,
      businessDomain: state.metaForm.businessDomain,
      dataDomain: state.metaForm.dataDomain,
      owner: state.metaForm.owner,
      bucketNum: state.metaForm.bucketNum,
      replicaNum: state.metaForm.replicaNum
    }
    const updated = await tableApi.update(state.table.id, payload, clusterId.value || null)
    state.table = { ...state.table, ...updated }
    state.metaForm = {
      tableName: state.table.tableName || '',
      tableComment: state.table.tableComment || '',
      layer: state.table.layer || '',
      businessDomain: state.table.businessDomain || '',
      dataDomain: state.table.dataDomain || '',
      owner: state.table.owner || '',
      bucketNum: state.table.bucketNum ?? '',
      replicaNum: state.table.replicaNum ?? ''
    }
    state.metaDataDomainOptions = []
    if (state.metaForm.businessDomain) {
      await loadMetaDataDomainOptions(tabId, state.metaForm.businessDomain)
    }
    state.metaOriginal = { ...state.metaForm }
    state.metaEditing = false
    updateTableCache(state.table)
    const newKey = syncTabKey(tabId, state.table)
    const tab = openTabs.value.find((item) => String(item.id) === String(newKey))
    if (tab) {
      tab.tableName = state.table.tableName
      tab.dbName = state.table.dbName
    }
    ElMessage.success('表信息已更新')
  } catch (error) {
    ElMessage.error('更新失败')
  } finally {
    state.metaSaving = false
  }
}

const updateTableCache = (updated) => {
  if (!updated?.dbName) return
  const sourceId = updated.sourceId || clusterId.value
  if (!sourceId) return
  const sourceKey = String(sourceId)
  const list = tableStore[sourceKey]?.[updated.dbName] || []
  const idx = list.findIndex((item) => String(item.id) === String(updated.id))
  if (idx === -1) return
  const next = [...list]
  next[idx] = { ...next[idx], ...updated }
  tableStore[sourceKey][updated.dbName] = next
}

const refreshFields = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.table?.id) return
  try {
    const fieldList = await tableApi.getFields(state.table.id)
    state.fields = Array.isArray(fieldList) ? fieldList : []
  } catch (error) {
    console.error('刷新字段失败', error)
  }
}

const syncTabKey = (oldKey, updatedTable) => {
  const newKey = getTableKey(updatedTable, updatedTable?.dbName || '', updatedTable?.sourceId || clusterId.value)
  if (!newKey || newKey === oldKey) return oldKey
  const oldIndex = openTabs.value.findIndex((tab) => String(tab.id) === String(oldKey))
  const existingIndex = openTabs.value.findIndex((tab) => String(tab.id) === String(newKey))
  if (existingIndex !== -1 && existingIndex !== oldIndex) {
    if (oldIndex !== -1) {
      openTabs.value.splice(oldIndex, 1)
    }
    delete tabStates[oldKey]
    activeTab.value = String(newKey)
    selectedTableKey.value = String(newKey)
    return newKey
  }
  if (oldIndex !== -1) {
    openTabs.value[oldIndex].id = newKey
  }
  tabStates[newKey] = tabStates[oldKey]
  if (oldKey !== newKey) {
    delete tabStates[oldKey]
    delete tableRefs.value[oldKey]
  }
  if (String(activeTab.value) === String(oldKey)) {
    activeTab.value = String(newKey)
  }
  selectedTableKey.value = String(newKey)
  return newKey
}

const startFieldsEdit = (tabId) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('编辑字段')
    return
  }
  const state = tabStates[tabId]
  if (!state) return
  if (warnPlatformMetadataMissing(state.table)) return
  if (!ensureClusterSelected(state.table)) return
  state.fieldsEditing = true
  state.fieldsDraft = state.fields.map((item) => ({ ...item }))
  state.fieldsRemoved = []
}

const cancelFieldsEdit = (tabId) => {
  const state = tabStates[tabId]
  if (!state) return
  state.fieldsEditing = false
  state.fieldsDraft = []
  state.fieldsRemoved = []
}

const addField = (tabId, afterRow = null) => {
  const state = tabStates[tabId]
  if (!state) return
  if (isAggregateTable(state.table)) {
    ElMessage.warning('AGGREGATE 表仅支持修改注释，无法新增字段')
    return
  }
  const newRow = {
    id: null,
    fieldName: '',
    fieldType: '',
    fieldOrder: 0,
    isNullable: 1,
    isPrimary: 0,
    defaultValue: '',
    fieldComment: ''
  }
  if (!afterRow) {
    state.fieldsDraft.unshift(newRow)
    return
  }
  const index = state.fieldsDraft.indexOf(afterRow)
  if (index === -1) {
    state.fieldsDraft.unshift(newRow)
    return
  }
  state.fieldsDraft.splice(index + 1, 0, newRow)
}

const removeField = (tabId, row) => {
  const state = tabStates[tabId]
  if (!state) return
  if (isAggregateTable(state.table)) {
    ElMessage.warning('AGGREGATE 表仅支持修改注释，无法删除字段')
    return
  }
  if (row?.id) {
    state.fieldsRemoved = [...new Set([...(state.fieldsRemoved || []), row.id])]
  }
  state.fieldsDraft = state.fieldsDraft.filter((item) => item !== row)
}

const buildFieldPayload = (row) => ({
  fieldName: (row.fieldName || '').trim(),
  fieldType: (row.fieldType || '').trim(),
  fieldComment: row.fieldComment || '',
  isNullable: row.isNullable ?? 1,
  isPrimary: row.isPrimary ?? 0,
  defaultValue: row.defaultValue || '',
  fieldOrder: row.fieldOrder || 0
})

const isFieldChanged = (next, original) => {
  if (!original) return true
  const payload = buildFieldPayload(next)
  return (
    payload.fieldName !== (original.fieldName || '') ||
    payload.fieldType !== (original.fieldType || '') ||
    payload.fieldComment !== (original.fieldComment || '') ||
    Number(payload.isNullable ?? 1) !== Number(original.isNullable ?? 1) ||
    Number(payload.isPrimary ?? 0) !== Number(original.isPrimary ?? 0) ||
    payload.defaultValue !== (original.defaultValue || '') ||
    Number(payload.fieldOrder || 0) !== Number(original.fieldOrder || 0)
  )
}

const isOnlyCommentChanged = (next, original) => {
  if (!original) return false
  const payload = buildFieldPayload(next)
  return (
    payload.fieldName === (original.fieldName || '') &&
    payload.fieldType === (original.fieldType || '') &&
    Number(payload.isNullable ?? 1) === Number(original.isNullable ?? 1) &&
    Number(payload.isPrimary ?? 0) === Number(original.isPrimary ?? 0) &&
    payload.defaultValue === (original.defaultValue || '') &&
    Number(payload.fieldOrder || 0) === Number(original.fieldOrder || 0) &&
    payload.fieldComment !== (original.fieldComment || '')
  )
}

const saveFieldsEdit = async (tabId) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('保存字段')
    return
  }
  const state = tabStates[tabId]
  if (warnPlatformMetadataMissing(state?.table)) return
  if (!state?.table?.id) return
  if (!ensureClusterSelected(state.table)) return
  const draft = state.fieldsDraft || []
  const removedIds = [...new Set(state.fieldsRemoved || [])]
  for (const row of draft) {
    const payload = buildFieldPayload(row)
    if (!payload.fieldName || !payload.fieldType) {
      ElMessage.warning('请填写字段名和类型')
      return
    }
  }
  const originalMap = new Map(state.fields.map((item) => [item.id, item]))
  const createList = draft.filter((row) => !row.id)
  const updateList = draft.filter((row) => row.id && isFieldChanged(row, originalMap.get(row.id)))
  if (isAggregateTable(state.table)) {
    const invalidUpdates = updateList.filter(
      (row) => !isOnlyCommentChanged(row, originalMap.get(row.id))
    )
    if (createList.length || removedIds.length || invalidUpdates.length) {
      ElMessage.warning('AGGREGATE 表仅支持修改字段注释')
      return
    }
  }
  if (isDorisTable(state.table)) {
    const primaryChanged = updateList.some((row) => {
      const original = originalMap.get(row.id)
      return Number(row.isPrimary ?? 0) !== Number(original?.isPrimary ?? 0)
    })
    const primaryAdded = createList.some((row) => Number(row.isPrimary ?? 0) === 1)
    if (primaryChanged || primaryAdded) {
      ElMessage.warning('Doris 不支持在线修改主键列')
      return
    }
  }
  if (!createList.length && !updateList.length && !removedIds.length) {
    ElMessage.info('暂无字段变更')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认保存字段变更（新增 ${createList.length}、修改 ${updateList.length}、删除 ${removedIds.length}）吗？`,
      '提示',
      {
        type: 'warning',
        confirmButtonText: '确认',
        cancelButtonText: '取消'
      }
    )
  } catch (error) {
    return
  }
  state.fieldSubmitting = true
  try {
    for (const row of createList) {
      await tableApi.createField(state.table.id, buildFieldPayload(row), clusterId.value || null)
    }
    for (const row of updateList) {
      await tableApi.updateField(state.table.id, row.id, buildFieldPayload(row), clusterId.value || null)
    }
    for (const id of removedIds) {
      await tableApi.deleteField(state.table.id, id, clusterId.value || null)
    }
    await refreshFields(tabId)
    state.fieldsEditing = false
    state.fieldsDraft = []
    state.fieldsRemoved = []
    ElMessage.success('字段已保存')
  } catch (error) {
    ElMessage.error('字段保存失败')
  } finally {
    state.fieldSubmitting = false
  }
}

const loadDdl = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.table) return
  const sourceId = state.table.sourceId || clusterId.value
  if (!sourceId) {
    ElMessage.warning('请选择数据源')
    return
  }
  const dbName = state.table.dbName || state.table.databaseName || state.table.database || ''
  const tableName = state.table.tableName || ''
  if (!dbName || !tableName) {
    ElMessage.warning('缺少数据库或表名')
    return
  }
  state.ddlLoading = true
  try {
    const ddl = state.table.id
      ? await tableApi.getTableDdl(state.table.id, sourceId || null)
      : await tableApi.getTableDdlByName(sourceId || null, dbName, tableName)
    state.ddl = ddl || ''
  } catch (error) {
    ElMessage.error('加载 DDL 失败')
  } finally {
    state.ddlLoading = false
  }
}

const loadAccessStats = async (tabId, force = false) => {
  const state = tabStates[tabId]
  if (!state?.table?.id) return
  if (state.accessLoading) return
  if (!force && state.accessStats) return
  const sourceId = state.table.sourceId || clusterId.value || state.table.clusterId
  if (!sourceId) {
    state.accessError = '缺少集群信息，无法获取访问统计'
    state.accessStats = null
    return
  }

  state.accessLoading = true
  state.accessError = ''
  try {
    const stats = await tableApi.getAccessStats(state.table.id, {
      clusterId: sourceId,
      recentDays: 30,
      trendDays: 14,
      topUsers: 5
    })
    state.accessStats = stats || null
  } catch (error) {
    state.accessStats = null
    state.accessError = error?.message || '加载访问统计失败'
  } finally {
    state.accessLoading = false
  }
}

const copyDdl = async (tabId) => {
  const state = tabStates[tabId]
  if (!state?.ddl) return
  try {
    await copyText(state.ddl)
    ElMessage.success('已复制')
  } catch (error) {
    ElMessage.error('复制失败')
  }
}

const openTask = (taskId) => {
  if (!taskId) return
  if (isDemoMode) {
    showDemoReadonlyMessage('任务详情')
    return
  }
  taskDrawerRef.value?.open(taskId)
}

const goCreateRelatedTask = (tabId, relation) => {
  if (isDemoMode) {
    showDemoReadonlyMessage('新增关联任务')
    return
  }
  const state = tabStates[String(tabId || '')]
  if (warnPlatformMetadataMissing(state?.table)) return
  const tableId = state?.table?.id
  if (!tableId) {
    ElMessage.warning('请先选择表')
    return
  }
  taskDrawerRef.value?.open(null, { relation, tableId })
}

const handleTaskSuccess = async () => {
  const id = String(activeTab.value || '')
  const tab = getTabItemById(id)
  if (tab?.kind !== 'table') return
  if (tabStates[id]) {
    tabStates[id].dataLoaded = false
  }
  await loadTabData(id)
}

const goLineage = (tabId) => {
  const state = tabStates[tabId]
  if (warnPlatformMetadataMissing(state?.table)) return
  if (!state?.table?.id) return
  router.push({ path: '/lineage', query: { tableId: state.table.id } })
}

const startResize = (event) => {
  event.preventDefault()
  const startX = event.clientX
  const startWidth = getSidebarWidthPx()
  isResizing.value = true

  resizeMoveHandler = (moveEvent) => {
    const delta = moveEvent.clientX - startX
    const next = clampSidebarWidth(startWidth + delta)
    sidebarWidthRatio.value = next / getLayoutWidth()
  }
  resizeUpHandler = () => {
    isResizing.value = false
    window.removeEventListener('mousemove', resizeMoveHandler)
    window.removeEventListener('mouseup', resizeUpHandler)
    resizeMoveHandler = null
    resizeUpHandler = null
  }
  window.addEventListener('mousemove', resizeMoveHandler)
  window.addEventListener('mouseup', resizeUpHandler)
}

const startRightResize = (event) => {
  event.preventDefault()
  const startX = event.clientX
  const startWidth = getRightPanelWidthPx()
  isResizing.value = true

  resizeRightMoveHandler = (moveEvent) => {
    const delta = startX - moveEvent.clientX
    const next = clampRightWidth(startWidth + delta)
    rightPanelWidthRatio.value = next / getLayoutWidth()
  }
  resizeRightUpHandler = () => {
    isResizing.value = false
    window.removeEventListener('mousemove', resizeRightMoveHandler)
    window.removeEventListener('mouseup', resizeRightUpHandler)
    resizeRightMoveHandler = null
    resizeRightUpHandler = null
  }
  window.addEventListener('mousemove', resizeRightMoveHandler)
  window.addEventListener('mouseup', resizeRightUpHandler)
}

const startLeftResize = (tabId, event) => {
  event.preventDefault()
  const container = leftPaneRefs.value[tabId]
  if (!container) return
  const queryPanel = container.querySelector('.query-panel')
  const containerRect = container.getBoundingClientRect()
  const startY = event.clientY
  const startHeight = queryPanel?.getBoundingClientRect().height || 220
  const minTop = 160
  const minBottom = 220
  const resizerHeight = 6
  isResizing.value = true
  let layoutRaf = 0

  resizeLeftMoveHandler = (moveEvent) => {
    const delta = moveEvent.clientY - startY
    let next = startHeight + delta
    const maxTop = Math.max(minTop, containerRect.height - minBottom - resizerHeight)
    next = Math.max(minTop, Math.min(maxTop, next))
    leftPaneHeights[tabId] = next
    if (layoutRaf) cancelAnimationFrame(layoutRaf)
    layoutRaf = requestAnimationFrame(() => syncResultPaneLayout(tabId))
  }
  resizeLeftUpHandler = () => {
    isResizing.value = false
    window.removeEventListener('mousemove', resizeLeftMoveHandler)
    window.removeEventListener('mouseup', resizeLeftUpHandler)
    resizeLeftMoveHandler = null
    resizeLeftUpHandler = null
    if (layoutRaf) cancelAnimationFrame(layoutRaf)
    layoutRaf = requestAnimationFrame(() => syncResultPaneLayout(tabId))
  }
  window.addEventListener('mousemove', resizeLeftMoveHandler)
  window.addEventListener('mouseup', resizeLeftUpHandler)
}

watch(
  () => [historyPager.pageNum, historyPager.pageSize],
  () => {
    fetchHistory()
  }
)

watch(
  () => {
	    const tabId = activeTab.value
	    if (!tabId) return null
	    const state = tabStates[tabId]
	    const idx = parseResultTabIndex(state?.resultTab)
	    if (idx === null) return null
	    const view = state?.resultViewTabs?.[idx] || 'table'
	    const chart = state?.charts?.[idx]
	    const set = Array.isArray(state?.queryResult?.resultSets) ? state.queryResult.resultSets[idx] : null
	    const rowsLen = Array.isArray(set?.rows) ? set.rows.length : 0
	    return [tabId, idx, view, chart?.type, chart?.xAxis, chart?.yAxis?.join(','), rowsLen]
	  },
		  async (payload) => {
		    if (!payload) return
		    const [tabId, idx, view] = payload
		    await nextTick()
		    if (view === 'chart') {
		      void renderChart(tabId, idx)
		      return
		    }
		    if (view === 'table') {
		      syncResultPaneLayout(tabId)
		    }
		  }
		)

watch(
  () => [activeTab.value, tabStates[activeTab.value]?.metaTab],
  ([tabId, metaTab]) => {
    if (!tabId) return
    const state = tabStates[tabId]
    if (!state) return
    if (metaTab === 'ddl') {
      if (state.ddlLoading || state.ddl) return
      loadDdl(tabId)
      return
    }
    if (metaTab === 'access') {
      if (state.accessLoading || state.accessStats) return
      loadAccessStats(tabId)
    }
  }
)

watch(
  () => activeTab.value,
  (value) => {
    if (!value) {
      if (!openTabs.value.length) {
        clearRouteTabQuery()
      }
      return
    }
    const tab = openTabs.value.find((item) => String(item.id) === String(value))
    if (!tab) return
    if (tab.sourceId) {
      clusterId.value = tab.sourceId
    }
    if (tab.kind === 'table') {
      selectedTableKey.value = String(tab.id)
      const tabId = String(tab.id)
      const state = tabStates[tabId]
      if (state && !state.dataLoaded && !state.dataLoading) {
        loadTabData(tabId)
      }
    } else {
      selectedTableKey.value = ''
    }
    syncRouteWithTab(tab, value)
  }
)

watch(
  () => [route.query.clusterId, route.query.database, route.query.tableId, route.query.tableName],
  async () => {
    if (suppressRouteSync.value) return
    await syncFromRoute()
  }
)

watch(
  () => route.query.create,
  (value) => {
    if (!value) return
    createDrawerVisible.value = true
    clearCreateQuery()
  }
)

watch(searchKeyword, (value) => {
  catalogTreeRef.value?.filter(value)
  if (schemaCountReloadTimer) {
    clearTimeout(schemaCountReloadTimer)
  }
  schemaCountReloadTimer = setTimeout(() => {
    schemaCountReloadTimer = null
    void (async () => {
      await reloadSchemaCountsForLoadedDatasources(value)
      catalogTreeRef.value?.filter(value)
    })()
  }, 300)
})

watch([sortField, sortOrder], () => {
  refreshLoadedSchemaNodesInTree()
})

watch(selectedTableKey, (value) => {
  if (!value) return
  catalogTreeRef.value?.setCurrentKey(value, false)
})



  provide('dataStudioCtx', {
    clusterId,
    openTabs,
    activeTab,
    tabStates,
  layerOptions,
  businessDomainOptions,
  getMetaDataDomainOptions,
  handleMetaBusinessDomainChange,
  isDorisTable,
  isPlatformMetadataMissing,
  isAggregateTable,
  isReplicaWarning,
  getLayerType,
  getFieldRows,
  startMetaEdit,
  cancelMetaEdit,
  saveMetaEdit,
  handleDeleteTable,
  syncMissingTableMetadata,
  startFieldsEdit,
  cancelFieldsEdit,
  saveFieldsEdit,
  addField,
  removeField,
    copyDdl,
    loadAccessStats,
    formatDuration,
    formatDateTime,
    goLineage,
    goCreateRelatedTask,
    openTask,
    openTableTab
  })

onMounted(async () => {
  setupTableObserver()
  const restored = restoreTabsFromStorage()
  if (restored) {
    hydrateRestoredTableTabs()
  }
  loadBusinessDomains()
  await loadClusters()
  fetchHistory()
  await syncFromRoute()
  await focusActiveTableInSidebar()
  if (route.query.create) {
    createDrawerVisible.value = true
    clearCreateQuery()
  }
  await nextTick()
  normalizePaneRatios()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  flushPersistTabs()
  window.removeEventListener('resize', handleResize)
  if (schemaCountReloadTimer) {
    clearTimeout(schemaCountReloadTimer)
    schemaCountReloadTimer = null
  }
				chartInstances.forEach((instance) => instance.dispose())
				chartInstances.clear()
			queryTimerHandles.forEach((handle) => clearInterval(handle))
			queryTimerHandles.clear()
  if (tableObserver.value) {
    tableObserver.value.disconnect()
  }
  if (resizeMoveHandler) {
    window.removeEventListener('mousemove', resizeMoveHandler)
    resizeMoveHandler = null
  }
  if (resizeUpHandler) {
    window.removeEventListener('mouseup', resizeUpHandler)
    resizeUpHandler = null
  }
  if (resizeRightMoveHandler) {
    window.removeEventListener('mousemove', resizeRightMoveHandler)
    resizeRightMoveHandler = null
  }
  if (resizeRightUpHandler) {
    window.removeEventListener('mouseup', resizeRightUpHandler)
    resizeRightUpHandler = null
  }
  if (resizeLeftMoveHandler) {
    window.removeEventListener('mousemove', resizeLeftMoveHandler)
    resizeLeftMoveHandler = null
  }
  if (resizeLeftUpHandler) {
    window.removeEventListener('mouseup', resizeLeftUpHandler)
    resizeLeftUpHandler = null
  }
})
</script>

<style scoped>
.data-studio {
  height: calc(100vh - 84px);
  min-height: 0;
  padding: 8px;
  background: #f3f6fb;
  overflow: hidden;
}

.studio-layout {
  height: 100%;
  display: flex;
  gap: 0;
}

.studio-sidebar {
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid #e6e9ef;
}

.sidebar-resizer {
  width: 10px;
  cursor: col-resize;
  position: relative;
  background: transparent;
}

.workspace-resizer {
  width: 10px;
  cursor: col-resize;
  position: relative;
  background: transparent;
}

.sidebar-resizer::after,
.workspace-resizer::after {
  content: '⋮⋮';
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  font-size: 11px;
  line-height: 1;
  letter-spacing: -1px;
  color: #94a3b8;
  padding: 3px 4px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.12);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease, color 0.15s ease, box-shadow 0.15s ease;
}

.sidebar-resizer:hover::after,
.workspace-resizer:hover::after,
.data-studio.is-resizing .sidebar-resizer::after,
.data-studio.is-resizing .workspace-resizer::after {
  opacity: 1;
  color: #64748b;
}

.data-studio.is-resizing {
  user-select: none;
}

.sidebar-controls {
  padding: 12px 14px;
  border-bottom: 1px solid #eef1f6;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.search-input {
  width: 100%;
}

.search-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.search-row .search-input {
  flex: 1;
}

.sort-row {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: nowrap;
  overflow-x: auto;
  overflow-y: hidden;
}

.sort-group {
  display: inline-flex;
  flex-wrap: nowrap;
}

.db-tree {
  flex: 1;
  min-height: 0;
  padding: 8px 8px 12px;
  overflow: hidden;
}

.db-tree-scroll {
  height: 100%;
}

.catalog-tree {
  width: 100%;
}

:deep(.catalog-tree .el-tree-node__content) {
  height: auto;
  padding: 2px 6px;
}

:deep(.catalog-tree .el-tree-node__content:hover) {
  background-color: transparent;
}

:deep(.catalog-tree .el-tree-node.is-current > .el-tree-node__content) {
  background-color: transparent;
}

.source-type {
  margin-left: auto;
  border-radius: 6px;
}

.db-count {
  display: inline-flex;
}

.loading-icon {
  margin-left: 6px;
}

.refresh-icon {
  cursor: pointer;
  color: #64748b;
  transition: color 0.15s ease;
}

.refresh-icon:hover {
  color: #3b82f6;
}

.refresh-icon.is-disabled {
  cursor: not-allowed;
  color: #cbd5e1;
  pointer-events: none;
}

.catalog-node {
  width: 100%;
  border-radius: 8px;
}

.catalog-node--datasource,
.catalog-node--schema,
.catalog-node--object_group {
  padding: 6px 8px;
  transition: background-color 0.2s ease;
}

.catalog-node--table {
  padding: 6px 8px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background-color: #fff;
  transition: background-color 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
  position: relative;
  overflow: hidden;
}

:deep(.catalog-tree .el-tree-node__content:hover .catalog-node--datasource),
:deep(.catalog-tree .el-tree-node__content:hover .catalog-node--schema),
:deep(.catalog-tree .el-tree-node__content:hover .catalog-node--object_group) {
  background-color: var(--el-fill-color-light);
}

:deep(.catalog-tree .el-tree-node__content:hover .catalog-node--table) {
  border-color: #667eea;
  background-color: #f0f4ff;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.08);
}

:deep(.catalog-tree .el-tree-node.is-current > .el-tree-node__content .catalog-node--table) {
  border-color: #667eea;
  background-color: #f0f4ff;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.12);
}

.catalog-node-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  position: relative;
  z-index: 1;
}

.catalog-node--table .catalog-node-row {
  align-items: flex-start;
}

.node-icon {
  flex-shrink: 0;
}

.datasource-logo {
  width: 16px;
  height: 16px;
  display: block;
}

.datasource-logo.is-inactive {
  filter: grayscale(1) saturate(0) opacity(0.55);
}

.node-icon.datasource.is-inactive {
  color: #94a3b8;
}

.node-icon.datasource {
  color: #f59e0b;
}

.node-icon.schema {
  color: #3b82f6;
}

.node-icon.table {
  color: #667eea;
}

.node-icon.table-folder {
  color: #667eea;
}

.node-icon.view {
  color: #0ea5e9;
}

.node-icon.view-folder {
  color: #0ea5e9;
}

.node-name {
  font-weight: 600;
  color: #111827;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.table-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.table-title {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.table-progress-bg {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  background: linear-gradient(90deg, rgba(102, 126, 234, 0.08) 0%, rgba(102, 126, 234, 0.02) 100%);
  transition: width 0.3s ease;
  pointer-events: none;
  z-index: 0;
}

:deep(.catalog-tree .el-tree-node__content:hover .table-progress-bg) {
  background: linear-gradient(90deg, rgba(102, 126, 234, 0.12) 0%, rgba(102, 126, 234, 0.04) 100%);
}

:deep(.catalog-tree .el-tree-node.is-current > .el-tree-node__content .table-progress-bg) {
  background: linear-gradient(90deg, rgba(102, 126, 234, 0.18) 0%, rgba(102, 126, 234, 0.06) 100%);
}

.table-name {
  font-size: 13px;
  font-weight: 600;
  display: inline-block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
  max-width: 200px;
}

.metadata-warning-icon {
  color: #ef4444;
  flex-shrink: 0;
}

.table-comment {
  color: #909399;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

.table-meta-tags {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  margin-left: auto;
  justify-content: flex-end;
}

.row-count {
  font-size: 11px;
  color: #475569;
  font-weight: 500;
  padding: 2px 6px;
  background-color: rgba(102, 126, 234, 0.1);
  border-radius: 4px;
  min-width: 35px;
  text-align: center;
}

.storage-size {
  font-size: 11px;
  color: #475569;
  font-weight: 500;
  padding: 2px 6px;
  background-color: rgba(14, 165, 233, 0.1);
  border-radius: 4px;
  min-width: 56px;
  text-align: center;
}

.lineage-count {
  font-size: 11px;
  font-weight: 500;
  padding: 2px 5px;
  border-radius: 4px;
  min-width: 28px;
  text-align: center;
}

.lineage-count.upstream {
  color: #10b981;
  background-color: rgba(16, 185, 129, 0.1);
}

.lineage-count.downstream {
  color: #f59e0b;
  background-color: rgba(245, 158, 11, 0.1);
}

.lineage-count.is-zero {
  color: #94a3b8;
  background-color: rgba(148, 163, 184, 0.16);
}

.layer-tag {
  flex-shrink: 0;
}

.studio-workspace {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 10px;
  border: 1px solid #e6e9ef;
  overflow: hidden;
}

.studio-right {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
}


.workspace-body {
  height: 100%;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.workspace-tabs {
  height: 100%;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

:deep(.workspace-tabs > .el-tabs) {
  height: 100%;
  flex: 1;
  min-height: 0;
}

:deep(.workspace-tabs .el-tabs__header) {
  display: flex;
  align-items: center;
}

:deep(.workspace-tabs .el-tabs__nav-wrap) {
  flex: 0 1 auto;
  min-width: 0;
  max-width: calc(100% - 72px);
}

:deep(.workspace-tabs .el-tabs__new-tab) {
  width: 32px;
  height: 32px;
  padding: 0;
  margin: 4px 0 4px 6px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: center;
}

:deep(.workspace-tabs .el-tabs__new-tab:hover) {
  background: #f8fafc;
  border-color: #c7d2fe;
}

/* remove label, keep only "+" icon */
:deep(.workspace-tabs .el-tabs__new-tab::after) {
  content: none;
}

:deep(.workspace-tabs .el-tabs__content) {
  height: 100%;
  flex: 1;
  min-height: 0;
}

:deep(.workspace-tabs .el-tab-pane) {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

:deep(.workspace-tabs .el-tabs__card) {
  height: 100%;
}

.tab-label {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tab-title {
  font-size: 13px;
  font-weight: 600;
  color: #1f2f3d;
}

.tab-sub {
  font-size: 11px;
  color: #94a3b8;
}

.tab-grid {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 10px;
  box-sizing: border-box;
  min-height: 0;
}

.tab-left,
.tab-right {
  min-height: 0;
}

.tab-left {
  flex: 1;
  display: grid;
  grid-template-rows: var(--left-top, 220px) 6px minmax(220px, 1fr);
  gap: 0;
  min-height: 0;
}

.left-resizer {
  cursor: row-resize;
  position: relative;
  background: transparent;
}

.left-resizer::after {
  content: '⋯';
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  font-size: 14px;
  line-height: 1;
  color: #94a3b8;
  padding: 0 8px 2px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.12);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease, color 0.15s ease, box-shadow 0.15s ease;
}

.left-resizer:hover::after,
.data-studio.is-resizing .left-resizer::after {
  opacity: 1;
  color: #64748b;
}

.tab-right {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
}

.query-panel {
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 8px;
  padding: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.query-topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  background: #f8fafc;
  border-bottom: 1px solid #e2e8f0;
  gap: 10px;
  flex-wrap: nowrap;
}

.query-topbar__left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
  overflow: hidden;
}

.query-topbar__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.query-context {
  display: flex;
  gap: 6px;
  flex-wrap: nowrap;
  align-items: center;
  min-width: 0;
}

.query-divider {
  width: 1px;
  height: 20px;
  background: #e2e8f0;
  flex-shrink: 0;
}

.query-select {
  width: 160px;
  flex: 0 0 160px;
}

.query-select--source {
  width: 180px;
  flex: 0 0 180px;
}

.query-select--db {
  width: 180px;
  flex: 0 0 180px;
}

.query-select :deep(.el-select__selected-item) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.limit-label {
  font-size: 12px;
  color: #64748b;
}

.limit-input {
  width: 110px;
}

.sql-editor {
  flex: 1;
  min-height: 0;
}

.sql-editor :deep(.cm-editor) {
  height: 100%;
}

.result-panel {
  height: 100%;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.result-tabs {
  flex: 1;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: none;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.05);
}

:deep(.result-tabs .el-tabs__content) {
  flex: 1;
  padding: 0 !important;
  overflow: hidden;
  position: relative;
  min-height: 0;
}

:deep(.result-tabs .el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.result-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.table-toolbar {
  padding: 8px 12px;
  background-color: #fff;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.result-message {
  padding: 8px 12px;
  background-color: #fff;
  border-bottom: 1px solid #ebeef5;
}

.meta-message {
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #606266;
}

.meta-info {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #606266;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.export-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.truncate {
  color: #e6a23c;
}

.result-view-container {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.result-view-switch {
  flex-shrink: 0;
}

.view-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.result-chart {
  flex: 1;
  min-height: 0;
  background: #fff;
}

.table-wrapper {
  flex: 1;
  min-height: 0;
  padding: 0;
  background: #fff;
  overflow: hidden;
}

.table-wrapper :deep(.el-table) {
  height: 100%;
}

.pagination-bar {
  padding: 8px;
  background: #fff;
  border-top: 1px solid #ebeef5;
  display: flex;
  justify-content: flex-end;
}

.history-panel {
  flex: 1;
  min-height: 0;
  padding: 8px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.history-panel :deep(.el-table) {
  height: 100%;
}

.history-pagination {
  padding: 8px 12px;
  border-top: 1px solid #eef1f6;
  display: flex;
  justify-content: flex-end;
}


.chart-grid {
  display: flex;
  height: 100%;
  min-height: 0;
}

.chart-config {
  width: 220px;
  border-right: 1px solid #eef1f6;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.config-title {
  font-size: 12px;
  font-weight: 600;
  color: #1f2f3d;
}

.config-select {
  width: 100%;
}

.hint {
  font-size: 12px;
  color: #94a3b8;
}

.chart-canvas {
  flex: 1;
  position: relative;
  min-height: 0;
}

.chart-inner {
  width: 100%;
  height: 100%;
}

.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
}

.meta-panel {
  border: 1px solid #eef1f6;
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
  min-height: 0;
  flex: 1;
}

.meta-tabs {
  height: 100%;
}

:deep(.meta-tabs .el-tabs__content) {
  height: 100%;
  padding: 12px;
  box-sizing: border-box;
}

:deep(.meta-tabs .el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.meta-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.meta-section-fill {
  flex: 1;
  min-height: 0;
}

.meta-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding-right: 4px;
}

.meta-table {
  flex: 1;
  min-height: 0;
}

.meta-descriptions :deep(.el-descriptions__content) {
  width: 100%;
}

.meta-input {
  width: 100%;
}

.replica-edit {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.replica-warning {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #ef4444;
}

.replica-value {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.replica-danger {
  color: #ef4444;
  font-weight: 600;
}

.warning-icon {
  font-size: 12px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  color: #1f2f3d;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.section-header.small {
  font-size: 12px;
  color: #475569;
}

.section-divider {
  height: 1px;
  background: #eef1f6;
  margin: 12px 0;
}

.ddl-header {
  display: flex;
  gap: 8px;
}

.ddl-textarea {
  flex: 1;
  min-height: 0;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
}

.ddl-textarea :deep(.el-textarea__inner) {
  height: 100% !important;
  min-height: 160px;
}

.lineage-panel {
  border: 1px solid #eef1f6;
  border-radius: 8px;
  background: #fff;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  flex: 1;
}

.lineage-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.lineage-grid {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  min-height: 0;
}

.lineage-card {
  border: 1px solid #eef1f6;
  border-radius: 8px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow: hidden;
}

.lineage-title {
  font-weight: 600;
  font-size: 12px;
  color: #1f2f3d;
}

.task-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

	.task-title {
	  font-size: 12px;
	  color: #64748b;
	}

	.task-title-row {
	  display: flex;
	  align-items: center;
	  justify-content: space-between;
	  gap: 8px;
	}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.task-item {
  padding: 6px 8px;
  border-radius: 6px;
  background: #f8fafc;
  cursor: pointer;
}

.task-item:hover {
  background: #eef5ff;
}

.task-name {
  font-size: 12px;
  font-weight: 600;
  color: #1f2f3d;
}

.task-meta {
  font-size: 11px;
  color: #94a3b8;
}

.lineage-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow: auto;
}

.lineage-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
}

.lineage-item:hover {
  background: #f1f5f9;
}

.lineage-info {
  flex: 1;
  min-width: 0;
}

.lineage-name {
  font-size: 12px;
  font-weight: 600;
  color: #1f2f3d;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.lineage-desc {
  font-size: 11px;
  color: #94a3b8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f9fafb;
}

@media (max-width: 1200px) {
  .studio-layout {
    flex-direction: column;
  }

  .studio-sidebar {
    width: 100% !important;
    max-height: 320px;
  }

  .studio-right {
    width: 100% !important;
  }

  .sidebar-resizer,
  .workspace-resizer {
    display: none;
  }

  .left-resizer {
    display: none;
  }

  .lineage-grid {
    grid-template-columns: 1fr;
  }
}
</style>
