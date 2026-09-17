<template>
  <div class="workflow-detail">
    <el-card>
      <template #header>
        <div class="header">
          <div class="title">
            <el-button link @click="$router.push('/workflows')">
              <el-icon><ArrowLeft /></el-icon>
            </el-button>
            <span class="name">{{ workflow?.workflow?.workflowName || '工作流详情' }}</span>
            <el-tag v-if="workflow?.workflow?.status" :type="getWorkflowStatusType(workflow.workflow.status)" size="small">
              {{ getWorkflowStatusText(workflow.workflow.status) }}
            </el-tag>
            <el-tag
               v-if="pendingApprovalFlags[workflow?.workflow?.id]"
               size="small"
               type="warning"
               effect="plain"
             >
               待审批
             </el-tag>
          </div>
          <div class="actions">
            <el-button
               v-if="workflow?.workflow"
               link
               type="primary"
               :loading="getActionLoading(workflow.workflow.id, 'deploy')"
               :disabled="isDeployDisabled(workflow.workflow)"
               @click="handleDeploy(workflow.workflow)"
             >
               发布
             </el-button>
             <el-button
               v-if="workflow?.workflow"
               link
               type="primary"
               :loading="getActionLoading(workflow.workflow.id, 'execute')"
               :disabled="isExecuteDisabled(workflow.workflow)"
               @click="handleExecute(workflow.workflow)"
             >
               执行
             </el-button>
             <el-button
               v-if="workflow?.workflow"
               link
               type="primary"
               :disabled="isBackfillDisabled(workflow.workflow)"
               @click="openBackfill(workflow.workflow)"
             >
               补数
             </el-button>
             <el-button
               v-if="workflow?.workflow"
               link
               type="success"
               :loading="getActionLoading(workflow.workflow.id, 'online')"
               :disabled="isOnlineDisabled(workflow.workflow)"
               @click="handleOnline(workflow.workflow)"
             >
               上线
             </el-button>
             <el-button
               v-if="workflow?.workflow"
               link
               type="warning"
               :loading="getActionLoading(workflow.workflow.id, 'offline')"
               :disabled="isOfflineDisabled(workflow.workflow)"
               @click="handleOffline(workflow.workflow)"
             >
               下线
             </el-button>
             <el-button
               v-if="workflow?.workflow"
               link
               type="info"
               :icon="Link"
               @click="openDolphin(workflow.workflow)"
               :disabled="!canJumpToDolphin(workflow.workflow)"
             >
               Dolphin
             </el-button>
             <el-button
               v-if="workflow?.workflow"
               link
               type="primary"
               @click="handleExportJson(workflow.workflow)"
             >
               导出 JSON
             </el-button>
             <el-button
               v-if="workflow?.workflow"
               link
               type="danger"
               :icon="Delete"
               @click="handleDelete(workflow.workflow)"
             >
               删除
             </el-button>
          </div>
        </div>
      </template>

      <div class="content" v-loading="loading">
        <!-- Basic Info Section with Inline Editing -->
        <div class="basic-info-section">
          <div class="section-title">基本信息</div>
            <el-descriptions :column="2" border>
            <!-- Editable Name Field -->
            <el-descriptions-item label="名称">
              <div v-if="!isEditingName" class="editable-field" @click="startEditName">
                <span>{{ workflow?.workflow?.workflowName }}</span>
                <el-icon class="edit-icon"><Edit /></el-icon>
              </div>
              <div v-else class="edit-field">
                <el-input 
                  v-model="editingName" 
                  size="small" 
                  maxlength="100"
                  style="width: 200px; margin-right: 8px;"
                />
                <el-button size="small" type="primary" :loading="savingField" @click="saveNameField">确认</el-button>
                <el-button size="small" @click="cancelEditName">取消</el-button>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="项目">{{ workflow?.workflow?.projectCode || '-' }}</el-descriptions-item>
            <el-descriptions-item label="调度引擎">
              <div class="scheduler-engine-cell">
                <el-tag size="small" :type="currentDolphinConfig?.isActive === false ? 'info' : 'success'">
                  {{ currentDolphinConfigName }}
                </el-tag>
                <el-button link type="primary" @click="openSchedulerSwitchDialog">切换</el-button>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="默认任务组">
              <div v-if="!isEditingTaskGroup" class="editable-field" @click="startEditTaskGroup">
                <span>{{ workflow?.workflow?.taskGroupName || '-' }}</span>
                <el-icon class="edit-icon"><Edit /></el-icon>
              </div>
              <div v-else class="edit-field">
                <el-select
                  v-model="editingTaskGroup"
                  size="small"
                  clearable
                  filterable
                  :loading="taskGroupsLoading"
                  style="width: 200px; margin-right: 8px;"
                  @visible-change="handleTaskGroupDropdown"
                >
                  <el-option
                    v-for="group in taskGroupOptions"
                    :key="group.id"
                    :label="group.name"
                    :value="group.name"
                  />
                </el-select>
                <el-button size="small" type="primary" :loading="savingField" @click="saveTaskGroupField">确认</el-button>
                <el-button size="small" @click="cancelEditTaskGroup">取消</el-button>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="Dolphin 流程编码">
              <div class="dolphin-code-cell">
                <el-link
                  v-if="canJumpToDolphin(workflow?.workflow)"
                  type="primary"
                  underline="never"
                  @click="openDolphin(workflow?.workflow)"
                >
                  {{ workflow?.workflow?.workflowCode }}
                  <el-icon class="el-icon--right"><Link /></el-icon>
                </el-link>
                <span v-else>{{ workflow?.workflow?.workflowCode || '-' }}</span>
              </div>
            </el-descriptions-item>
            <!-- Editable Description Field -->
            <el-descriptions-item label="描述" :span="2">
              <div v-if="!isEditingDescription" class="editable-field" @click="startEditDescription">
                <span>{{ workflow?.workflow?.description || '-' }}</span>
                <el-icon class="edit-icon"><Edit /></el-icon>
              </div>
              <div v-else class="edit-field">
                <el-input 
                  v-model="editingDescription" 
                  type="textarea"
                  :rows="2"
                  size="small" 
                  maxlength="300"
                  style="width: 400px; margin-right: 8px;"
                />
                <el-button size="small" type="primary" :loading="savingField" @click="saveDescriptionField">确认</el-button>
                <el-button size="small" @click="cancelEditDescription">取消</el-button>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDateTime(workflow?.workflow?.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ formatDateTime(workflow?.workflow?.updatedAt) }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <el-tabs v-model="activeTab" class="detail-tabs">
          <el-tab-pane label="Tasks" name="tasks">
          <div class="tab-content">
            <WorkflowTaskManager 
              v-if="workflow?.workflow?.id" 
              :workflow-id="workflow.workflow.id"
              :workflow-task-ids="workflowTaskIds"
              :dolphin-config-id="currentDolphinConfigId"
              @update="loadWorkflowDetail"
            />
          </div>
        </el-tab-pane>
          <el-tab-pane label="定时调度" name="schedule">
            <div class="tab-content">
              <el-alert
                title="Quartz CRON（7段）"
                description="DolphinScheduler 使用 Quartz CRON：秒 分 时 日 月 周 年，例如：0 0 * * * ? *。先保存配置创建调度，再上线调度生效。"
                type="info"
                show-icon
                :closable="false"
                class="tips"
              />

              <div v-if="workflow?.workflow" class="schedule-status">
                <el-space wrap>
                  <el-tag size="small" type="info">
                    ScheduleId: {{ workflow.workflow.dolphinScheduleId || '-' }}
                  </el-tag>
                  <el-tag
                    size="small"
                    :type="(workflow.workflow.scheduleState || '').toUpperCase() === 'ONLINE' ? 'success' : 'warning'"
                  >
                    {{ workflow.workflow.scheduleState || 'OFFLINE' }}
                  </el-tag>
                  <el-switch
                    v-model="scheduleEnabled"
                    :disabled="!workflow.workflow.dolphinScheduleId"
                    :loading="scheduleSwitchLoading"
                    active-text="上线"
                    inactive-text="下线"
                    @change="handleToggleSchedule"
                  />
                </el-space>
                <div v-if="workflow.workflow.status !== 'online'" class="text-gray schedule-hint">
                  工作流未上线，无法上线调度（可先保存配置，待工作流上线后再开启）。
                </div>
              </div>

              <el-form
                ref="scheduleFormRef"
                :model="scheduleForm"
                :rules="scheduleRules"
                label-width="110px"
                class="schedule-form"
              >
                <el-form-item label="起止时间" prop="scheduleStartEndTime">
                  <el-date-picker
                    v-model="scheduleForm.scheduleStartEndTime"
                    type="datetimerange"
                    value-format="YYYY-MM-DD HH:mm:ss"
                    start-placeholder="开始时间"
                    end-placeholder="结束时间"
                    style="width: 100%;"
                    :disabled="isScheduleOnline"
                  />
                </el-form-item>
                <el-form-item label="Cron" prop="scheduleCron">
                  <div class="cron-row">
                    <div class="cron-input-wrapper">
                      <el-popover
                        v-model:visible="cronBuilderVisible"
                        trigger="click"
                        placement="bottom-start"
                        :width="580"
                        :disabled="isScheduleOnline"
                      >
                        <template #reference>
                          <div class="cron-input-ref">
                            <el-input
                              v-model="scheduleForm.scheduleCron"
                              placeholder="0 0 * * * ? *"
                              readonly
                              :disabled="isScheduleOnline"
                            />
                          </div>
                        </template>
                        <QuartzCronBuilder
                          v-model="scheduleForm.scheduleCron"
                          @applied="cronBuilderVisible = false"
                          @cancel="cronBuilderVisible = false"
                        />
                      </el-popover>
                    </div>
                    <el-button
                      type="primary"
                      :loading="schedulePreviewLoading"
                      :disabled="isScheduleOnline"
                      @click="previewScheduleTimes"
                    >
                      预览
                    </el-button>
                  </div>
                </el-form-item>
                <el-form-item label="时区" prop="scheduleTimezone">
                  <el-select
                    v-model="scheduleForm.scheduleTimezone"
                    filterable
                    allow-create
                    default-first-option
                    placeholder="Asia/Shanghai"
                    style="width: 100%;"
                    :disabled="isScheduleOnline"
                  >
                    <el-option
                      v-for="tz in timezoneOptions"
                      :key="tz"
                      :label="tz"
                      :value="tz"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="schedulePreviewList.length" label="预览">
                  <div class="schedule-preview">
                    <div class="schedule-preview-title">未来 5 次执行时间</div>
                    <div v-for="(time, idx) in schedulePreviewList" :key="idx" class="schedule-preview-item">
                      {{ time }}
                    </div>
                  </div>
                </el-form-item>
	                <el-form-item label="失败策略">
	                  <el-radio-group v-model="scheduleForm.scheduleFailureStrategy" :disabled="isScheduleOnline">
	                    <el-radio value="CONTINUE">CONTINUE（继续）</el-radio>
	                    <el-radio value="END">END（结束）</el-radio>
	                  </el-radio-group>
	                </el-form-item>
                <el-form-item label="告警类型">
                  <el-select
                    v-model="scheduleForm.scheduleWarningType"
                    placeholder="NONE"
                    style="width: 100%;"
                    :disabled="isScheduleOnline"
                  >
                    <el-option label="NONE" value="NONE" />
                    <el-option label="SUCCESS" value="SUCCESS" />
                    <el-option label="FAILURE" value="FAILURE" />
                    <el-option label="ALL" value="ALL" />
                    <el-option label="SUCCESS_FAILURE（兼容）" value="SUCCESS_FAILURE" />
                  </el-select>
                </el-form-item>
                <el-form-item
                  v-if="scheduleForm.scheduleWarningType !== 'NONE'"
                  label="告警组"
                  prop="scheduleWarningGroupId"
                >
                  <el-select
                    v-model="scheduleForm.scheduleWarningGroupId"
                    filterable
                    clearable
                    placeholder="请选择告警组"
                    style="width: 100%;"
                    :loading="scheduleOptionsLoading"
                    :disabled="isScheduleOnline"
                  >
                    <el-option
                      v-for="group in alertGroupOptions"
                      :key="group.id"
                      :label="group.groupName"
                      :value="group.id"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="优先级">
                  <el-select
                    v-model="scheduleForm.scheduleProcessInstancePriority"
                    placeholder="MEDIUM"
                    style="width: 100%;"
                    :disabled="isScheduleOnline"
                  >
                    <el-option label="HIGHEST" value="HIGHEST" />
                    <el-option label="HIGH" value="HIGH" />
                    <el-option label="MEDIUM" value="MEDIUM" />
                    <el-option label="LOW" value="LOW" />
                    <el-option label="LOWEST" value="LOWEST" />
                  </el-select>
                </el-form-item>
                <el-form-item label="Worker 分组">
                  <el-select
                    v-model="scheduleForm.scheduleWorkerGroup"
                    filterable
                    allow-create
                    default-first-option
                    clearable
                    placeholder="default"
                    style="width: 100%;"
                    :loading="scheduleOptionsLoading"
                    :disabled="isScheduleOnline"
                    @change="handleWorkerGroupChange"
                  >
                    <el-option
                      v-for="group in workerGroupOptions"
                      :key="group"
                      :label="group"
                      :value="group"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="租户编码">
                  <el-select
                    v-model="scheduleForm.scheduleTenantCode"
                    filterable
                    allow-create
                    default-first-option
                    clearable
                    placeholder="default"
                    style="width: 100%;"
                    :loading="scheduleOptionsLoading"
                    :disabled="isScheduleOnline"
                  >
                    <el-option
                      v-for="tenant in tenantOptions"
                      :key="tenant"
                      :label="tenant"
                      :value="tenant"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="环境">
                  <el-select
                    v-model="scheduleForm.scheduleEnvironmentCode"
                    filterable
                    clearable
                    placeholder="默认(-1)"
                    style="width: 100%;"
                    :loading="scheduleOptionsLoading"
                    :disabled="isScheduleOnline"
                  >
                    <el-option label="默认(-1)" :value="-1" />
                    <el-option
                      v-for="env in environmentFilteredOptions"
                      :key="env.code"
                      :label="env.name"
                      :value="env.code"
                    />
                  </el-select>
                  <div class="form-tip">切换 Worker 分组后需重新选择环境；-1 表示默认/不指定环境</div>
                </el-form-item>
                <el-form-item label="自动上线">
                  <el-switch
                    v-model="scheduleForm.scheduleAutoOnline"
                    active-text="工作流上线后自动上线调度"
                    inactive-text="不自动"
                    :disabled="isScheduleOnline"
                  />
                </el-form-item>
                <el-form-item>
                  <el-button type="success" :loading="savingSchedule" :disabled="isScheduleOnline" @click="saveScheduleConfig">
                    保存配置
                  </el-button>
                </el-form-item>
              </el-form>
            </div>
          </el-tab-pane>
          <el-tab-pane label="执行历史" name="executions">
            <!-- 与执行监控同一组件、同一组列，只省掉「工作流」——整屏都是当前工作流，那一列是噪音。 -->
            <WorkflowInstanceTable
              :instances="executionInstances"
              :loading="executionInstancesLoading"
              expandable
              show-source
              show-error-message
            />
            <el-pagination
              v-model:current-page="executionQuery.pageNum"
              v-model:page-size="executionQuery.pageSize"
              :total="executionTotal"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next, jumper"
              class="execution-pagination"
              @size-change="loadExecutionInstances"
              @current-change="loadExecutionInstances"
            />
          </el-tab-pane>
          <el-tab-pane label="数据新鲜度" name="freshness">
            <div v-loading="freshnessLoading" class="freshness-pane">
              <div class="freshness-toolbar">
                <div class="freshness-summary" v-if="freshness">
                  <el-tag type="info" effect="plain">全部关联表 {{ freshness.summary.total }}</el-tag>
                  <el-tag effect="plain">写出 {{ freshness.summary.writeCount }}</el-tag>
                  <el-tag effect="plain">读取 {{ freshness.summary.readCount }}</el-tag>
                  <el-tag type="success" effect="plain">正常 {{ freshness.summary.pass }}</el-tag>
                  <el-tag type="warning" effect="plain">预警 {{ freshness.summary.warn }}</el-tag>
                  <el-tag type="danger" effect="plain">过期 {{ freshness.summary.error }}</el-tag>
                  <el-tag type="info" effect="plain">检查失败 {{ freshness.summary.runtimeError }}</el-tag>
                  <el-tag effect="plain">未配置 {{ freshness.summary.unconfigured }}</el-tag>
                </div>
                <el-button size="small" @click="loadWorkflowFreshness">刷新</el-button>
              </div>

              <div class="freshness-section-title">每次运行的问题表数</div>
              <el-table :data="freshness?.runs || []" border size="small">
                <el-table-column label="检查时间" min-width="170">
                  <template #default="{ row }">{{ formatDateTime(row.checkedAt) }}</template>
                </el-table-column>
                <el-table-column label="触发实例" min-width="110">
                  <template #default="{ row }">
                    <a
                      v-if="row.workflowInstanceId && buildDolphinInstanceUrl(row.workflowInstanceId)"
                      :href="buildDolphinInstanceUrl(row.workflowInstanceId)"
                      target="_blank"
                      rel="noopener"
                      class="instance-link"
                    >#{{ row.workflowInstanceId }}</a>
                    <span v-else-if="row.workflowInstanceId">#{{ row.workflowInstanceId }}</span>
                    <span v-else>-</span>
                  </template>
                </el-table-column>
                <el-table-column label="问题表数" min-width="130">
                  <template #default="{ row }">
                    <el-tag :type="row.problem > 0 ? 'danger' : 'success'" size="small" effect="plain">
                      {{ row.problem }} / {{ row.total }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty
                v-if="freshness && freshness.runs.length === 0"
                :image-size="60"
                description="暂无检查记录（工作流成功运行后自动检查其关联表）"
              />

              <div class="freshness-table-header">
                <div class="freshness-section-title">关联表最新状态</div>
                <el-radio-group v-model="freshnessRelationFilter" size="small" class="freshness-relation-filter">
                  <el-radio-button label="all">全部 ({{ freshness?.tables?.length || 0 }})</el-radio-button>
                  <el-radio-button label="write">写出 ({{ freshness?.summary?.writeCount || 0 }})</el-radio-button>
                  <el-radio-button label="read">读取 ({{ freshness?.summary?.readCount || 0 }})</el-radio-button>
                </el-radio-group>
              </div>
              <el-table :data="filteredFreshnessTables" border size="small">
                <el-table-column label="角色" width="90">
                  <template #default="{ row }">
                    <el-tag
                      size="small"
                      :type="freshnessRelationTagType(row.relationType)"
                      effect="plain"
                    >
                      {{ freshnessRelationLabel(row.relationType) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="表" min-width="180" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.tableName }}</template>
                </el-table-column>
                <el-table-column prop="dbName" label="库" min-width="120" show-overflow-tooltip />
                <el-table-column label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag v-if="row.status" :type="freshnessStatusType(row.status)" size="small" effect="plain">
                      {{ freshnessStatusLabel(row.status) }}
                    </el-tag>
                    <el-tag v-else type="info" size="small" effect="plain">
                      {{ row.configured ? '待检查' : '未配置' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="最新数据时间" min-width="170">
                  <template #default="{ row }">{{ row.maxLoadedAt ? formatDateTime(row.maxLoadedAt) : '-' }}</template>
                </el-table-column>
                <el-table-column label="数据年龄" min-width="120">
                  <template #default="{ row }">{{ humanizeAge(row.ageSeconds) }}</template>
                </el-table-column>
              </el-table>
              <el-empty
                v-if="freshness && filteredFreshnessTables.length === 0"
                :image-size="60"
                description="没有符合条件的关联表"
              />
            </div>
          </el-tab-pane>
          <el-tab-pane label="版本历史" name="changes">
            <div class="change-toolbar">
              <el-button
                v-if="changeMode === 'list'"
                type="primary"
                :disabled="!canCompareSelected"
                @click="compareSelectedVersions"
              >
                比较所选版本
              </el-button>
            </div>

            <template v-if="changeMode === 'list'">
              <el-table
                v-if="versionHistoryRows.length"
                ref="versionHistoryTableRef"
                :data="versionHistoryRows"
                border
                size="small"
                row-key="id"
                @selection-change="handleVersionSelectionChange"
              >
                <el-table-column
                  type="selection"
                  width="56"
                  :selectable="isVersionSelectable"
                />
                <el-table-column label="版本" min-width="200">
                  <template #default="{ row }">
                    <span class="version-label" :class="{ 'is-current': row.isCurrent }">
                      {{ row.versionLabel }}
                    </span>
                    <el-tag size="small" :type="row.schemaTagType" class="version-schema-tag">
                      {{ row.schemaLabel }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="createdAt" label="日期" width="180">
                  <template #default="{ row }">
                    {{ formatDateTime(row.createdAt) }}
                  </template>
                </el-table-column>
                <el-table-column prop="createdBy" label="变更者" width="160">
                  <template #default="{ row }">
                    {{ row.createdBy || '-' }}
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="150">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="openVersionPublishRecords(row)">
                      <el-tag size="small" :type="row.statusType">
                        {{ row.statusText }}
                      </el-tag>
                    </el-button>
                  </template>
                </el-table-column>
                <el-table-column label="评论" min-width="220" show-overflow-tooltip>
                  <template #default="{ row }">
                    {{ row.comment }}
                  </template>
                </el-table-column>
                <el-table-column label="行动" width="200" fixed="right" align="left">
                  <template #default="{ row }">
                    <el-tooltip
                      :disabled="!getRollbackDisabledReason(row)"
                      :content="getRollbackDisabledReason(row)"
                    >
                      <span>
                        <el-button
                          link
                          type="primary"
                          :disabled="Boolean(getRollbackDisabledReason(row))"
                          :loading="rollbackLoadingVersionId === Number(row.id)"
                          @click="rollbackToVersion(row.id)"
                        >
                          恢复
                        </el-button>
                      </span>
                    </el-tooltip>
                    <el-tooltip
                      :disabled="!getVersionDeleteDisabledReason(row)"
                      :content="getVersionDeleteDisabledReason(row)"
                    >
                      <span>
                        <el-button
                          link
                          type="danger"
                          :disabled="Boolean(getVersionDeleteDisabledReason(row))"
                          :loading="deleteLoadingVersionId === Number(row.id)"
                          @click="deleteVersion(row)"
                        >
                          删除
                        </el-button>
                      </span>
                    </el-tooltip>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-else description="暂无版本记录" />

              <div class="change-toolbar change-toolbar-bottom">
                <el-button
                  type="primary"
                  :disabled="!canCompareSelected"
                  @click="compareSelectedVersions"
                >
                  比较所选版本
                </el-button>
              </div>
            </template>

            <WorkflowVersionComparePanel
              v-else
              :versions="versionListDesc"
              :left-version-id="leftVersionId"
              :right-version-id="rightVersionId"
              :compare-result="versionCompareResult"
              :loading="compareLoading"
              @back="backToPublishRecords"
              @step="stepVersionCompare"
            />
          </el-tab-pane>
          <el-tab-pane label="全局变量" name="globals">
            <div class="global-params-section">
                <div class="params-header">
                    <el-button type="primary" size="small" @click="addGlobalParam">
                        <el-icon><Plus /></el-icon> 添加变量
                    </el-button>
                    <el-button type="success" size="small" @click="saveGlobalParams" :loading="savingParams">
                        保存配置
                    </el-button>
                </div>
                <el-table :data="globalParamsList" border size="small" style="width: 100%; margin-top: 10px">
                    <el-table-column label="变量名 (Prop)" prop="prop" width="200" show-overflow-tooltip>
                        <template #default="{ row }">
                            <el-input v-if="row.__editing" v-model="row.prop" placeholder="prop" size="small" />
                            <span
                              v-else
                              :class="['global-param-display', { 'is-empty': isGlobalParamEmpty(row.prop) }]"
                            >
                              {{ formatGlobalParamDisplay(row.prop) }}
                            </span>
                        </template>
                    </el-table-column>
                    <el-table-column label="方向 (Direct)" prop="direct" width="120">
                        <template #default="{ row }">
                            <el-select v-if="row.__editing" v-model="row.direct" size="small">
                                <el-option label="IN" value="IN" />
                                <el-option label="OUT" value="OUT" />
                            </el-select>
                            <span v-else class="global-param-display">{{ row.direct || 'IN' }}</span>
                        </template>
                    </el-table-column>
                    <el-table-column label="类型 (Type)" prop="type" width="120">
                        <template #default="{ row }">
                            <el-select v-if="row.__editing" v-model="row.type" size="small">
                                <el-option label="VARCHAR" value="VARCHAR" />
                                <el-option label="INTEGER" value="INTEGER" />
                                <el-option label="LONG" value="LONG" />
                                <el-option label="FLOAT" value="FLOAT" />
                                <el-option label="DOUBLE" value="DOUBLE" />
                                <el-option label="DATE" value="DATE" />
                                <el-option label="TIME" value="TIME" />
                                <el-option label="TIMESTAMP" value="TIMESTAMP" />
                                <el-option label="BOOLEAN" value="BOOLEAN" />
                            </el-select>
                            <span v-else class="global-param-display">{{ row.type || 'VARCHAR' }}</span>
                        </template>
                    </el-table-column>
                    <el-table-column label="变量值 (Value)" prop="value" show-overflow-tooltip>
                        <template #default="{ row }">
                            <el-input v-if="row.__editing" v-model="row.value" placeholder="value" size="small" />
                            <span
                              v-else
                              :class="['global-param-display', { 'is-empty': isGlobalParamEmpty(row.value) }]"
                            >
                              {{ formatGlobalParamDisplay(row.value) }}
                            </span>
                        </template>
                    </el-table-column>
                    <el-table-column label="操作" width="120" align="center">
                        <template #default="{ row, $index }">
                            <div class="global-param-actions">
                                <template v-if="row.__editing">
                                    <el-tooltip content="取消编辑" placement="top">
                                        <el-button link @click="cancelEditGlobalParam(row, $index)">
                                            <el-icon><Close /></el-icon>
                                        </el-button>
                                    </el-tooltip>
                                    <el-tooltip content="删除变量" placement="top">
                                        <el-button type="danger" link @click="removeGlobalParam($index)">
                                            <el-icon><Delete /></el-icon>
                                        </el-button>
                                    </el-tooltip>
                                </template>
                                <template v-else>
                                    <el-tooltip content="编辑变量" placement="top">
                                        <el-button type="primary" link @click="startEditGlobalParam(row)">
                                            <el-icon><Edit /></el-icon>
                                        </el-button>
                                    </el-tooltip>
                                    <el-tooltip content="删除变量" placement="top">
                                        <el-button type="danger" link @click="removeGlobalParam($index)">
                                            <el-icon><Delete /></el-icon>
                                        </el-button>
                                    </el-tooltip>
                                </template>
                            </div>
                        </template>
                    </el-table-column>
                </el-table>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-card>

    <el-dialog
      v-model="versionPublishRecordDialogVisible"
      :title="versionPublishRecordDialogTitle"
      width="900px"
    >
      <el-table
        v-if="activeVersionPublishRecords.length"
        :data="activeVersionPublishRecords"
        border
        size="small"
      >
        <el-table-column label="版本" width="120">
          <template #default="{ row }">
            {{ renderVersionLabel(row.versionId) }}
          </template>
        </el-table-column>
        <el-table-column prop="operation" label="操作" width="120">
          <template #default="{ row }">
            {{ getOperationText(row.operation) }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="getPublishRecordStatusType(row.status)">
              {{ getPublishRecordStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="发布人" width="140" />
        <el-table-column prop="createdAt" label="发布时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            {{ formatLog(row.log) }}
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="该版本暂无发布记录" />
    </el-dialog>

    <el-dialog
      v-model="schedulerSwitchDialogVisible"
      title="切换调度引擎"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-alert
        title="旧 Dolphin 不会自动下线或删除，目标 Dolphin 需要重新发布后生成新的运行态工作流。"
        type="warning"
        show-icon
        :closable="false"
        class="scheduler-switch-alert"
      />
      <el-form label-width="110px" class="scheduler-switch-form">
        <el-form-item label="当前环境">
          <span>{{ currentDolphinConfigName }}</span>
        </el-form-item>
        <el-form-item label="目标环境" required>
          <el-select
            v-model="schedulerSwitchForm.dolphinConfigId"
            placeholder="请选择 Dolphin 环境"
            filterable
            :loading="dolphinConfigsLoading"
            style="width: 100%"
          >
            <el-option
              v-for="item in dolphinConfigs"
              :key="item.id"
              :label="formatDolphinConfigOption(item)"
              :value="item.id"
              :disabled="!item.isActive"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="schedulerSwitchDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="schedulerSwitchSaving" @click="switchSchedulerEngine">
          确认切换
        </el-button>
      </template>
    </el-dialog>

    <WorkflowBackfillDialog
      v-model="backfillDialogVisible"
      :workflow="backfillTarget"
      @submitted="handleBackfillSubmitted"
    />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick, watch, h } from 'vue'
import { useRoute } from 'vue-router'
import { useRouter } from 'vue-router'
import { ArrowLeft, Link, Delete, Edit, Plus, Close } from '@element-plus/icons-vue'
import { workflowApi } from '@/api/workflow'
import { taskApi } from '@/api/task'
import { settingsApi } from '@/api/settings'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import WorkflowTaskManager from './WorkflowTaskManager.vue'
import WorkflowBackfillDialog from './WorkflowBackfillDialog.vue'
import WorkflowVersionComparePanel from './WorkflowVersionComparePanel.vue'
import WorkflowPublishPreviewDialog from './WorkflowPublishPreviewDialog.vue'
import {
  buildConsistencyIssueHtml,
  buildPublishRepairHtml,
  buildPublishBlockedHtml,
  splitRepairIssues,
  firstPreviewErrorMessage,
  isDialogCancel,
  resolvePublishVersionId,
  shouldPromptOnlineAfterDeploy
} from './publishPreviewHelper'
import QuartzCronBuilder from '@/components/QuartzCronBuilder.vue'
import WorkflowInstanceTable from '@/components/WorkflowInstanceTable.vue'
import { getWorkflowExecutionInstances } from '@/api/execution'
import {
  getWorkflowStatusType,
  getWorkflowStatusText,
  getOperationText,
  getPublishRecordStatusType,
  getPublishRecordStatusText,
  formatDateTime,
  formatLog,
  getErrorMessage
} from './workflowDisplay'
import {
  cloneGlobalParamCore,
  createGlobalParamRow,
  normalizeGlobalParams,
  isGlobalParamEmpty,
  formatGlobalParamDisplay
} from './globalParams'
import {
  formatDolphinConfigOption,
  rollbackDisabledReason,
  versionDeleteDisabledReason
} from './workflowVersion'
import { useInlineFieldEditing } from './composables/useInlineFieldEditing'
import { useScheduleForm } from './composables/useScheduleForm'
import { useVersionCompare } from './composables/useVersionCompare'
import { useVersionMutations } from './composables/useVersionMutations'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const workflow = ref(null)
const activeTab = ref('tasks')
const dolphinWebuiUrl = ref('')
const dolphinConfigs = ref([])
const dolphinConfigsLoading = ref(false)
const pendingApprovalFlags = reactive({})
const actionLoading = reactive({})
const backfillDialogVisible = ref(false)
const backfillTarget = ref(null)
// 执行历史：作用域恒定为当前工作流，workflowId 来自路由、不暴露给用户修改。
const executionInstances = ref([])
const executionInstancesLoading = ref(false)
const executionInstancesLoaded = ref(false)
const executionTotal = ref(0)

// 数据新鲜度页签
const freshness = ref(null)
const freshnessLoading = ref(false)
const freshnessLoaded = ref(false)
const freshnessRelationFilter = ref('all')
const filteredFreshnessTables = computed(() => {
  const list = freshness.value?.tables || []
  if (freshnessRelationFilter.value === 'all') return list
  if (freshnessRelationFilter.value === 'write') {
    return list.filter((t) => t.relationType === 'write' || t.relationType === 'both')
  }
  if (freshnessRelationFilter.value === 'read') {
    return list.filter((t) => t.relationType === 'read' || t.relationType === 'both')
  }
  return list
})
const freshnessRelationLabel = (type) => {
  if (type === 'both') return '读写'
  if (type === 'read') return '读取'
  return '写出'
}
const freshnessRelationTagType = (type) => {
  if (type === 'both') return 'warning'
  if (type === 'read') return 'info'
  return 'success'
}
const FRESHNESS_STATUS = {
  pass: { label: '正常', type: 'success' },
  warn: { label: '预警', type: 'warning' },
  error: { label: '过期', type: 'danger' },
  runtime_error: { label: '检查失败', type: 'info' }
}
const freshnessStatusLabel = (s) => FRESHNESS_STATUS[s]?.label || s || '-'
const freshnessStatusType = (s) => FRESHNESS_STATUS[s]?.type || 'info'
const humanizeAge = (seconds) => {
  if (seconds === null || seconds === undefined) return '-'
  let s = Math.max(0, Number(seconds))
  const d = Math.floor(s / 86400); s -= d * 86400
  const h = Math.floor(s / 3600); s -= h * 3600
  const m = Math.floor(s / 60)
  if (d > 0) return h > 0 ? `${d} 天 ${h} 小时` : `${d} 天`
  if (h > 0) return m > 0 ? `${h} 小时 ${m} 分钟` : `${h} 小时`
  return `${m} 分钟`
}
const loadWorkflowFreshness = async () => {
  const id = workflow.value?.workflow?.id
  if (!id) return
  freshnessLoading.value = true
  try {
    freshness.value = await workflowApi.freshness(id)
    freshnessLoaded.value = true
  } catch (e) {
    // 拦截器已提示错误
  } finally {
    freshnessLoading.value = false
  }
}
const executionQuery = reactive({
  pageNum: 1,
  pageSize: 10
})
const changeMode = ref('list')
const schedulerSwitchDialogVisible = ref(false)
const schedulerSwitchSaving = ref(false)
const schedulerSwitchForm = reactive({
  dolphinConfigId: null
})

const defaultDolphinConfig = computed(() => {
  return dolphinConfigs.value.find(item => item?.isDefault === 1) || null
})

const currentDolphinConfigId = computed(() => {
  const boundId = workflow.value?.workflow?.dolphinConfigId
  if (boundId) {
    return boundId
  }
  return defaultDolphinConfig.value?.id || null
})

const currentDolphinConfig = computed(() => {
  const id = currentDolphinConfigId.value
  if (!id) {
    return null
  }
  return dolphinConfigs.value.find(item => item?.id === id) || null
})

const currentDolphinConfigName = computed(() => {
  const config = currentDolphinConfig.value
  if (!config) {
    return currentDolphinConfigId.value ? `Dolphin #${currentDolphinConfigId.value}` : '默认 Dolphin'
  }
  const suffix = config.isDefault === 1 ? '（默认）' : ''
  return `${config.configName || `Dolphin #${config.id}`}${suffix}`
})

// Schedule states
// 调度表单/选项/预览/上下线（W5）：buildDolphinConfigParams/loadWorkflowDetail/getErrorMessage 惰性前向引用
const {
  scheduleFormRef,
  savingSchedule,
  scheduleSwitchLoading,
  scheduleEnabled,
  scheduleSwitchMuted,
  scheduleOptionsLoading,
  scheduleOptionsLoaded,
  schedulePreviewLoading,
  schedulePreviewList,
  cronBuilderVisible,
  workerGroupOptions,
  tenantOptions,
  alertGroupOptions,
  environmentOptions,
  isScheduleOnline,
  timezoneOptions,
  scheduleForm,
  environmentFilteredOptions,
  scheduleRules,
  loadScheduleOptions,
  handleWorkerGroupChange,
  previewScheduleTimes,
  syncScheduleForm,
  saveScheduleConfig,
  handleToggleSchedule,
} = useScheduleForm({
  workflow,
  activeTab,
  buildDolphinConfigParams: (...args) => buildDolphinConfigParams(...args),
  loadWorkflowDetail: (...args) => loadWorkflowDetail(...args),
  getErrorMessage: (...args) => getErrorMessage(...args),
})

const buildDolphinConfigParams = () => {
  return currentDolphinConfigId.value
    ? { dolphinConfigId: currentDolphinConfigId.value }
    : {}
}


watch(
  () => scheduleForm.scheduleWarningType,
  (val, prev) => {
    if (val === 'NONE') {
      scheduleForm.scheduleWarningGroupId = 0
      return
    }
    if (prev === 'NONE') {
      scheduleForm.scheduleWarningGroupId = null
    }
  }
)

watch(
  () => [scheduleForm.scheduleCron, scheduleForm.scheduleTimezone, scheduleForm.scheduleStartEndTime],
  () => {
    schedulePreviewList.value = []
  },
  { deep: true }
)

// Global params state
const globalParamsList = ref([])
const savingParams = ref(false)

const serializeGlobalParams = () => {
  return globalParamsList.value.map(item => cloneGlobalParamCore(item))
}

// Computed workflow task IDs
const workflowTaskIds = computed(() => {
  const relations = workflow.value?.taskRelations || []
  return relations.map(r => Number(r.taskId)).filter(id => Number.isFinite(id))
})

// 名称/任务组/描述行内编辑（W4）：loadWorkflowDetail/buildDolphinConfigParams 惰性前向引用
const {
  isEditingName,
  isEditingDescription,
  isEditingTaskGroup,
  editingName,
  editingDescription,
  editingTaskGroup,
  savingField,
  taskGroupsLoading,
  taskGroupOptions,
  startEditName,
  cancelEditName,
  saveNameField,
  loadTaskGroupOptions,
  handleTaskGroupDropdown,
  startEditTaskGroup,
  cancelEditTaskGroup,
  saveTaskGroupField,
  startEditDescription,
  cancelEditDescription,
  saveDescriptionField,
} = useInlineFieldEditing({
  workflow,
  workflowTaskIds,
  loadWorkflowDetail: (...args) => loadWorkflowDetail(...args),
  buildDolphinConfigParams: (...args) => buildDolphinConfigParams(...args),
})

const versionList = computed(() => {
  const list = workflow.value?.versions || []
  return [...list].sort((a, b) => (a.versionNo || 0) - (b.versionNo || 0))
})

const versionListDesc = computed(() => {
  return [...versionList.value].reverse()
})

const versionById = computed(() => {
  return versionList.value.reduce((acc, version) => {
    if (version?.id) {
      acc[version.id] = version
    }
    return acc
  }, {})
})

const publishRecords = computed(() => workflow.value?.publishRecords || [])

const publishRecordsSorted = computed(() => {
  return [...publishRecords.value].sort((left, right) => {
    const leftTs = dayjs(left?.createdAt).valueOf()
    const rightTs = dayjs(right?.createdAt).valueOf()
    if (leftTs !== rightTs) {
      return rightTs - leftTs
    }
    return Number(right?.id || 0) - Number(left?.id || 0)
  })
})

const publishRecordsByVersionId = computed(() => {
  return publishRecordsSorted.value.reduce((acc, item) => {
    const versionId = Number(item?.versionId)
    if (!Number.isFinite(versionId)) {
      return acc
    }
    if (!acc[versionId]) {
      acc[versionId] = []
    }
    acc[versionId].push(item)
    return acc
  }, {})
})

const latestPublishRecordByVersionId = computed(() => {
  return Object.entries(publishRecordsByVersionId.value).reduce((acc, [versionId, items]) => {
    if (Array.isArray(items) && items.length) {
      acc[Number(versionId)] = items[0]
    }
    return acc
  }, {})
})

const lastSuccessfulPublishedVersionId = computed(() => {
  const record = publishRecordsSorted.value.find((item) => {
    return item?.status === 'success' && Number.isFinite(Number(item?.versionId))
  })
  return record ? Number(record.versionId) : null
})

const versionHistoryRows = computed(() => {
  const currentVersionId = Number(workflow.value?.workflow?.currentVersionId)
  return versionListDesc.value.map((version) => {
    const versionId = Number(version?.id)
    const schemaVersion = Number(version?.snapshotSchemaVersion)
    const normalizedSchemaVersion = Number.isFinite(schemaVersion) ? schemaVersion : 1
    const isV3 = normalizedSchemaVersion === 3
    const latestRecord = Number.isFinite(versionId)
      ? latestPublishRecordByVersionId.value[versionId]
      : null
    const statusType = latestRecord ? getPublishRecordStatusType(latestRecord.status) : 'info'
    const statusText = latestRecord ? getPublishRecordStatusText(latestRecord.status) : '未发布'
    const isCurrent = Number.isFinite(currentVersionId) && currentVersionId === versionId
    return {
      ...version,
      isCurrent,
      statusType,
      statusText,
      latestPublishRecord: latestRecord || null,
      versionLabel: isCurrent ? `当前（版本 ${version.versionNo}）` : `版本 ${version.versionNo}`,
      comment: version?.changeSummary || '-',
      snapshotSchemaVersion: normalizedSchemaVersion,
      isV3,
      schemaLabel: `V${normalizedSchemaVersion}`,
      schemaTagType: isV3 ? 'success' : 'warning'
    }
  })
})

const {
  compareLoading,
  versionCompareResult,
  leftVersionId,
  rightVersionId,
  versionHistoryTableRef,
  selectedHistoryVersions,
  selectedHistoryVersionIds,
  canCompareSelected,
  clearVersionHistorySelection,
  handleVersionSelectionChange,
  loadVersionCompare,
  compareSelectedVersions,
  stepVersionCompare,
} = useVersionCompare({ workflow, versionList, changeMode })

const {
  rollbackLoadingVersionId,
  deleteLoadingVersionId,
  versionPublishRecordDialogVisible,
  activeVersionPublishRecords,
  activeVersionForRecords,
  versionPublishRecordDialogTitle,
  rollbackToVersion,
  deleteVersion,
  openVersionPublishRecords,
} = useVersionMutations({
  workflow,
  versionById,
  publishRecordsByVersionId,
  getRollbackDisabledReason: (...args) => getRollbackDisabledReason(...args),
  getVersionDeleteDisabledReason: (...args) => getVersionDeleteDisabledReason(...args),
  loadWorkflowDetail: (...args) => loadWorkflowDetail(...args),
  backToPublishRecords: (...args) => backToPublishRecords(...args),
})

// Inline edit methods
const addGlobalParam = () => {
    globalParamsList.value.push(createGlobalParamRow({}, { editing: true, isNew: true }))
}

const removeGlobalParam = (index) => {
    globalParamsList.value.splice(index, 1)
}

const startEditGlobalParam = (row) => {
    if (row.__editing) {
        return
    }
    row.__backup = cloneGlobalParamCore(row)
    row.__editing = true
}

const cancelEditGlobalParam = (row, index) => {
    if (row.__isNew) {
        removeGlobalParam(index)
        return
    }
    Object.assign(row, createGlobalParamRow(row.__backup || row), {
        __editing: false,
        __isNew: false,
        __backup: null
    })
}

const saveGlobalParams = async () => {
    savingParams.value = true
    try {
        const wf = workflow.value?.workflow
        await workflowApi.update(wf.id, {
            workflowName: wf.workflowName,
            description: wf.description,
            taskGroupName: wf.taskGroupName || null,
            tasks: workflowTaskIds.value.map(taskId => ({ taskId })),
            globalParams: JSON.stringify(serializeGlobalParams()),
            operator: 'portal-ui'
        })
        ElMessage.success('全局变量保存成功')
        loadWorkflowDetail()
    } catch (error) {
        console.error('保存全局变量失败', error)
        ElMessage.error(error?.response?.data?.message || '保存失败')
    } finally {
        savingParams.value = false
    }
}

const loadWorkflowDetail = async () => {
  const id = route.params.id
  if (!id) return
  
  loading.value = true
  try {
    const res = await workflowApi.detail(id)
    workflow.value = res // logic in WorkflowList suggests res is directly the detail object
    // Check if the API returns wrapped object
    if (res && res.workflow) {
        workflow.value = res
    } else {
        // Fallback or error handling if needed, but based on WorkflowList: workflowDetail.value = await workflowApi.detail(workflowId)
        workflow.value = { workflow: res } // Wrap it if it's flat
    }
    
    // Parse global params
    if (workflow.value?.workflow?.globalParams) {
        try {
            globalParamsList.value = normalizeGlobalParams(JSON.parse(workflow.value.workflow.globalParams))
        } catch (e) {
            console.error('Failed to parse global params', e)
            globalParamsList.value = []
        }
    } else {
        globalParamsList.value = []
    }

    syncScheduleForm()
    syncPendingFlag(workflow.value?.workflow?.id, workflow.value?.publishRecords || [])
    clearVersionHistorySelection()
  } catch (error) {
    console.error('加载工作流详情失败', error)
    ElMessage.error('加载工作流详情失败')
  } finally {
    loading.value = false
  }
}

const syncPendingFlag = (workflowId, records) => {
  if (!workflowId) {
    return
  }
  const hasPending = Array.isArray(records)
    && records.some((record) => record.status === 'pending_approval')
  if (hasPending) {
    pendingApprovalFlags[workflowId] = true
  } else {
    delete pendingApprovalFlags[workflowId]
  }
}

const loadDolphinConfig = async () => {
  try {
    const config = await taskApi.getDolphinWebuiConfig(buildDolphinConfigParams())
    dolphinWebuiUrl.value = config?.webuiUrl || ''
  } catch (error) {
    console.warn('加载 Dolphin 配置失败', error)
  }
}

const loadDolphinConfigs = async () => {
  dolphinConfigsLoading.value = true
  try {
    const list = await settingsApi.listDolphinConfigs()
    dolphinConfigs.value = Array.isArray(list) ? list : []
  } catch (error) {
    console.warn('加载 Dolphin 环境列表失败', error)
  } finally {
    dolphinConfigsLoading.value = false
  }
}


const openSchedulerSwitchDialog = async () => {
  if (!workflow.value?.workflow?.id) {
    return
  }
  if (!dolphinConfigs.value.length) {
    await loadDolphinConfigs()
  }
  schedulerSwitchForm.dolphinConfigId = currentDolphinConfigId.value
  schedulerSwitchDialogVisible.value = true
}

const switchSchedulerEngine = async () => {
  const wf = workflow.value?.workflow
  if (!wf?.id) {
    return
  }
  const targetId = schedulerSwitchForm.dolphinConfigId
  if (!targetId) {
    ElMessage.warning('请选择目标 Dolphin 环境')
    return
  }
  if (targetId === currentDolphinConfigId.value) {
    ElMessage.warning('目标环境与当前绑定环境一致')
    return
  }

  const target = dolphinConfigs.value.find(item => item.id === targetId)
  if (target && !target.isActive) {
    ElMessage.warning('目标 Dolphin 环境未启用')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确认切换到「${target?.configName || targetId}」吗？切换后旧 Dolphin 不会自动下线，当前工作流需要重新发布到目标 Dolphin。`,
      '确认切换调度引擎',
      {
        type: 'warning',
        confirmButtonText: '确认切换',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  schedulerSwitchSaving.value = true
  try {
    await workflowApi.switchSchedulerEngine(wf.id, {
      dolphinConfigId: targetId,
      operator: 'portal-ui'
    })
    schedulerSwitchDialogVisible.value = false
    scheduleOptionsLoaded.value = false
    taskGroupOptions.value = []
    ElMessage.success('调度引擎已切换，请重新发布工作流')
    await loadWorkflowDetail()
    await loadDolphinConfigs()
    await loadDolphinConfig()
  } catch (error) {
    console.error('切换调度引擎失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    schedulerSwitchSaving.value = false
  }
}

const renderVersionLabel = (versionId) => {
  const id = Number(versionId)
  if (!Number.isFinite(id)) {
    return '-'
  }
  const version = versionById.value[id]
  if (version?.versionNo !== null && version?.versionNo !== undefined) {
    return `v${version.versionNo}`
  }
  return `#${id}`
}

const backToPublishRecords = () => {
  changeMode.value = 'list'
}

const isVersionSelectable = (row) => {
  if (!row?.isV3) {
    return false
  }
  const versionId = Number(row?.id)
  if (!Number.isFinite(versionId)) {
    return false
  }
  if (selectedHistoryVersionIds.value.length < 2) {
    return true
  }
  return selectedHistoryVersionIds.value.includes(versionId)
}

const getRollbackDisabledReason = (row) =>
  rollbackDisabledReason(row, workflow.value?.workflow?.currentVersionId)

const getVersionDeleteDisabledReason = (row) =>
  versionDeleteDisabledReason(
    row,
    workflow.value?.workflow?.currentVersionId,
    lastSuccessfulPublishedVersionId.value
  )

const buildDolphinWorkflowUrl = (workflow) => {
  if (!dolphinWebuiUrl.value || !workflow?.projectCode || !workflow?.workflowCode) {
    return ''
  }
  const base = dolphinWebuiUrl.value.replace(/\/+$/, '')
  return `${base}/ui/projects/${workflow.projectCode}/workflow/definitions/${workflow.workflowCode}`
}

const canJumpToDolphin = (workflow) => {
  return Boolean(
    dolphinWebuiUrl.value
    && workflow?.workflowCode
    && workflow?.projectCode
  )
}

// Dolphin 工作流实例详情地址，供新鲜度检查记录的「触发实例」链接使用。
// 与后端 DolphinExecutionMapper.workflowInstanceUrl 保持同一路径形态。
const buildDolphinInstanceUrl = (instanceId) => {
  const wf = workflow.value?.workflow
  if (!dolphinWebuiUrl.value || !wf?.projectCode || !wf?.workflowCode || !instanceId) {
    return ''
  }
  const base = dolphinWebuiUrl.value.replace(/\/+$/, '')
  return `${base}/ui/projects/${wf.projectCode}/workflow/instances/${instanceId}?code=${wf.workflowCode}`
}

const openDolphin = (workflow) => {
  const url = buildDolphinWorkflowUrl(workflow)
  if (!url) {
    ElMessage.warning('尚未配置 Dolphin WebUI 地址')
    return
  }
  window.open(url, '_blank')
}

const handleExportJson = async (row) => {
  if (!row?.id) return
  try {
    const result = await workflowApi.exportJson(row.id)
    const content = result?.content || ''
    if (!content) {
      ElMessage.warning('当前工作流没有可导出的定义内容')
      return
    }
    const fileName = result?.fileName || `workflow_${row.id}.json`
    const blob = new Blob([content], { type: 'application/json;charset=utf-8' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(link.href)
    // 一致性问题只提示不阻断：坏定义恰恰是最需要导出来排查的。
    const consistencyIssues = Array.isArray(result?.consistencyIssues) ? result.consistencyIssues : []
    if (consistencyIssues.length) {
      ElMessage.success('导出成功')
      try {
        await ElMessageBox.alert(
          buildConsistencyIssueHtml(
            consistencyIssues,
            '文件已下载。导出的定义存在以下血缘一致性问题，导入到其他环境前建议先修复。'
          ),
          '导出完成，但检测到血缘一致性问题',
          {
            type: 'warning',
            customClass: 'workflow-publish-message-box',
            confirmButtonText: '知道了',
            dangerouslyUseHTMLString: true
          }
        )
      } catch (error) {
        // 文件此时已经下载完成，关闭提示没有任何可中止的动作，静默吞掉即可。
        if (!isDialogCancel(error)) {
          throw error
        }
      }
      return
    }
    ElMessage.success('导出成功')
  } catch (error) {
    console.error('导出工作流 JSON 失败', error)
  }
}

// Action Handlers
const previewPublishAndConfirm = async (row) => {
  if (!row?.id) {
    return false
  }
  let preview = await workflowApi.previewPublish(row.id)
  if (!preview?.canPublish) {
    // block-missing 模式下每个缺边任务都会进 errors。只弹首条的话用户得反复
    // "修一个再发一次"，helper 会把血缘问题连同其他发布错误一次列全。
    const blockedHtml = buildPublishBlockedHtml(preview)
    if (blockedHtml) {
      try {
        await ElMessageBox.alert(blockedHtml, '血缘缺失，无法发布', {
          type: 'error',
          customClass: 'workflow-publish-message-box',
          confirmButtonText: '知道了',
          dangerouslyUseHTMLString: true
        })
      } catch (error) {
        if (!isDialogCancel(error)) {
          throw error
        }
      }
      return false
    }
    ElMessage.error(firstPreviewErrorMessage(preview))
    return false
  }
  const { repairable: repairIssues, advisory: advisoryIssues } = splitRepairIssues(preview)
  if (advisoryIssues.length) {
    // repairable=false 的问题（血缘一致性告警）修复动作解决不了，只做只读提示后继续。
    // 需要真正阻断时服务端会置 canPublish=false，已在上面的 errors 分支拦下。
    try {
      await ElMessageBox.alert(
        buildConsistencyIssueHtml(
          advisoryIssues,
          '检测到血缘一致性问题。发布不会被阻断，但建议打开相关任务，按 SQL 分析结果补齐血缘后重新保存。'
        ),
        '血缘一致性提醒',
        {
          type: 'warning',
          customClass: 'workflow-publish-message-box',
          confirmButtonText: '知道了',
          dangerouslyUseHTMLString: true
        }
      )
    } catch (error) {
      // alert 在 ESC / 关闭按钮时 reject。不接住会一路冒泡到 handleDeploy 的 catch，
      // 弹出 ElMessage.error('close') 这种无意义提示。
      // 唯一的按钮是"知道了"=已知悉并继续，所以关闭动作按"先不发布"处理，静默中止。
      if (isDialogCancel(error)) {
        return false
      }
      throw error
    }
  }
  if (repairIssues.length) {
    try {
      await ElMessageBox.confirm(
        // 只把可修复的问题喂给修复弹窗：helper 会渲染传入对象的全部 repairIssues，
        // 直接传 preview 会让血缘告警在只读提示之外再出现一次，而"修复元数据"根本修不了它们。
        buildPublishRepairHtml({ ...preview, repairIssues }),
        '检测到可修复元数据问题',
        {
          type: 'warning',
          customClass: 'workflow-publish-message-box',
          confirmButtonText: '修复元数据并重试',
          cancelButtonText: '继续发布',
          distinguishCancelAndClose: true,
          dangerouslyUseHTMLString: true
        }
      )
      const repaired = await workflowApi.repairPublishMetadata(row.id, {
        operator: 'portal-ui'
      })
      const changedTaskCount = repaired?.updatedTaskCount ?? 0
      const changedWorkflowCount = Array.isArray(repaired?.updatedWorkflowFields)
        ? repaired.updatedWorkflowFields.length
        : 0
      ElMessage.success(`元数据修复完成：工作流字段 ${changedWorkflowCount} 项，任务 ${changedTaskCount} 个`)
      preview = await workflowApi.previewPublish(row.id)
      if (!preview?.canPublish) {
        ElMessage.error(firstPreviewErrorMessage(preview))
        return false
      }
      const { repairable: unresolvedRepairIssues } = splitRepairIssues(preview)
      if (unresolvedRepairIssues.length) {
        const unresolvedFields = unresolvedRepairIssues
          .map(issue => issue?.field)
          .filter(Boolean)
          .slice(0, 3)
        const fieldTip = unresolvedFields.length
          ? `（${unresolvedFields.join(', ')}${unresolvedRepairIssues.length > 3 ? ' 等' : ''}）`
          : ''
        ElMessage.error(`元数据修复未完成，仍有 ${unresolvedRepairIssues.length} 项问题${fieldTip}，请先补全并保存后重试发布`)
        return false
      }
    } catch (error) {
      if (error === 'cancel') {
        // Continue publish without repair
      } else if (error === 'close') {
        return false
      } else {
        throw error
      }
    }
  }
  if (!preview?.requireConfirm) {
    return true
  }
  try {
    await ElMessageBox.confirm(
      h(WorkflowPublishPreviewDialog, { preview }),
      '发布变更确认',
      {
        type: 'warning',
        customClass: 'workflow-publish-message-box workflow-publish-message-box--preview',
        confirmButtonText: '确认发布',
        cancelButtonText: '取消'
      }
    )
    return true
  } catch (error) {
    if (isDialogCancel(error)) {
      return false
    }
    throw error
  }
}

const setActionLoading = (workflowId, action, value) => {
  if (!workflowId) return
  if (!actionLoading[workflowId]) {
    actionLoading[workflowId] = {}
  }
  if (value) {
    actionLoading[workflowId][action] = true
  } else {
    delete actionLoading[workflowId][action]
    if (Object.keys(actionLoading[workflowId]).length === 0) {
      delete actionLoading[workflowId]
    }
  }
}

const getActionLoading = (workflowId, action) => {
  return Boolean(actionLoading[workflowId]?.[action])
}

const updatePendingFlag = (workflowId, status) => {
  if (!workflowId) return
  if (status === 'pending_approval') {
    pendingApprovalFlags[workflowId] = true
  } else {
    delete pendingApprovalFlags[workflowId]
  }
}

const resolveDeployOnlineVersionId = (row, record) => {
  return record?.versionId || record?.workflowVersionId || record?.targetVersionId || resolvePublishVersionId(row)
}

const promptOnlineAfterDeploy = async (row, record) => {
  if (!shouldPromptOnlineAfterDeploy(row, record)) return
  try {
    await ElMessageBox.confirm(
      '发布已成功，是否立即上线该工作流？',
      '立即上线',
      {
        type: 'warning',
        confirmButtonText: '立即上线',
        cancelButtonText: '稍后处理'
      }
    )
  } catch (error) {
    return
  }

  setActionLoading(row.id, 'online', true)
  try {
    const onlineRecord = await workflowApi.publish(row.id, {
      operation: 'online',
      versionId: resolveDeployOnlineVersionId(row, record),
      requireApproval: false,
      operator: 'portal-ui'
    })
    updatePendingFlag(row.id, onlineRecord?.status)
    if (onlineRecord?.status === 'pending_approval') {
      ElMessage.warning('上线已提交审批，等待审批通过')
    } else {
      ElMessage.success('上线成功')
    }
  } catch (error) {
    console.error('上线失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'online', false)
  }
}

const isDeployDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  return getActionLoading(row.id, 'deploy')
}

const isExecuteDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  if (getActionLoading(row.id, 'execute')) return true
  return !row.workflowCode
}

const isBackfillDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  return !row.workflowCode
}

const isOnlineDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  if (getActionLoading(row.id, 'online')) return true
  return row.status === 'online'
}

const isOfflineDisabled = (row) => {
  if (!row) return true
  if (pendingApprovalFlags[row.id]) return true
  if (getActionLoading(row.id, 'offline')) return true
  return row.status !== 'online'
}

const handleDeploy = async (row) => {
  if (!row?.id) return
  setActionLoading(row.id, 'deploy', true)
  try {
    const canPublish = await previewPublishAndConfirm(row)
    if (!canPublish) {
      return
    }
    const record = await workflowApi.publish(row.id, {
      operation: 'deploy',
      requireApproval: false,
      operator: 'portal-ui',
      confirmDiff: true
    })
    updatePendingFlag(row.id, record?.status)
    if (record?.status === 'pending_approval') {
      ElMessage.warning('发布已提交审批，等待审批通过')
    } else {
      ElMessage.success('发布成功')
      await promptOnlineAfterDeploy(row, record)
    }
    loadWorkflowDetail()
  } catch (error) {
    console.error('发布失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'deploy', false)
  }
}

const handleExecute = async (row) => {
  if (!row?.id) return
  if (row.status !== 'online') {
    ElMessage.warning('工作流未上线，请先上线后再执行')
    return
  }
  setActionLoading(row.id, 'execute', true)
  try {
    const executionId = await workflowApi.execute(row.id)
    ElMessage.success(`已触发执行，实例ID：${executionId || '-'}`)
    loadWorkflowDetail()
  } catch (error) {
    console.error('执行失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'execute', false)
  }
}

const openBackfill = (row) => {
  if (row?.status !== 'online') {
    ElMessage.warning('工作流未上线，请先上线后再补数')
    return
  }
  backfillTarget.value = row || null
  backfillDialogVisible.value = true
}

const handleBackfillSubmitted = () => {
  loadWorkflowDetail()
}

const handleOnline = async (row) => {
  if (!row?.id) return
  if (!row?.workflowCode) {
    ElMessage.warning('工作流尚未发布，请先执行发布后再上线')
    return
  }
  setActionLoading(row.id, 'online', true)
  try {
    const onlineRecord = await workflowApi.publish(row.id, {
      operation: 'online',
      versionId: resolvePublishVersionId(row),
      requireApproval: false,
      operator: 'portal-ui'
    })
    updatePendingFlag(row.id, onlineRecord?.status)
    if (onlineRecord?.status === 'pending_approval') {
      ElMessage.warning('上线已提交审批，等待审批通过')
    } else {
      ElMessage.success('上线成功')
    }
    loadWorkflowDetail()
  } catch (error) {
    console.error('上线失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'online', false)
  }
}

const handleOffline = async (row) => {
  if (!row?.id) return
  setActionLoading(row.id, 'offline', true)
  try {
    const record = await workflowApi.publish(row.id, {
      operation: 'offline',
      versionId: resolvePublishVersionId(row),
      requireApproval: false,
      operator: 'portal-ui'
    })
    updatePendingFlag(row.id, record?.status)
    if (record?.status === 'pending_approval') {
      ElMessage.warning('下线已提交审批')
    } else {
      ElMessage.success('下线成功')
    }
    loadWorkflowDetail()
  } catch (error) {
    console.error('下线失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'offline', false)
  }
}

const handleDelete = async (row) => {
  if (!row?.id) return

  try {
    await ElMessageBox.confirm(
      `确定要删除工作流"${row.workflowName}"吗？<br/><br/>
      <div style="color: #666; font-size: 12px;">
      • 将删除工作流相关的所有数据（版本、发布记录、执行历史等）<br/>
      • 任务定义默认保留，下一步可选择是否级联删除<br/>
      • 此操作不可恢复
      </div>`,
      '确认删除',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: true
      }
    )
  } catch (error) {
    if (!isDialogCancel(error)) {
      ElMessage.error(getErrorMessage(error))
    }
    return
  }

  let cascadeDeleteTasks = false
  try {
    await ElMessageBox.confirm(
      `是否同时级联软删除工作流下的任务？<br/><br/>
      <div style="color: #666; font-size: 12px;">
      • 选择“级联删除任务”：将软删除工作流和其绑定任务<br/>
      • 选择“仅删除工作流”：仅软删除工作流，任务会保留
      </div>`,
      '删除范围确认',
      {
        confirmButtonText: '级联删除任务',
        cancelButtonText: '仅删除工作流',
        type: 'warning',
        distinguishCancelAndClose: true,
        dangerouslyUseHTMLString: true
      }
    )
    cascadeDeleteTasks = true
  } catch (error) {
    if (error === 'cancel') {
      cascadeDeleteTasks = false
    } else {
      return
    }
  }

  setActionLoading(row.id, 'delete', true)
  try {
    await workflowApi.delete(row.id, cascadeDeleteTasks)
    ElMessage.success(cascadeDeleteTasks ? '工作流和任务已删除' : '工作流删除成功，任务已保留')
    router.push('/workflows')
  } catch (error) {
    console.error('删除失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    setActionLoading(row.id, 'delete', false)
  }
}

const consumePublishHint = () => {
  if (String(route.query.publishHint || '') !== '1') return
  ElMessage.warning('工作流有变化，请使用发布按钮将工作流发布到 Dolphin。')
  const nextQuery = { ...route.query }
  delete nextQuery.publishHint
  router.replace({ path: route.path, query: nextQuery })
}

const loadExecutionInstances = async () => {
  const workflowId = workflow.value?.workflow?.id
  if (!workflowId) {
    return
  }
  executionInstancesLoading.value = true
  try {
    const response = await getWorkflowExecutionInstances({
      workflowId,
      pageNum: executionQuery.pageNum,
      pageSize: executionQuery.pageSize
    })
    executionInstances.value = response.records || []
    executionTotal.value = response.total || 0
    executionInstancesLoaded.value = true
  } catch (error) {
    ElMessage.error(`加载执行历史失败：${getErrorMessage(error)}`)
  } finally {
    executionInstancesLoading.value = false
  }
}

// 首次切到执行历史时才拉取，不跟着工作流详情一起打。
watch(activeTab, (tab) => {
  if (tab === 'executions' && !executionInstancesLoaded.value) {
    loadExecutionInstances()
  }
  if (tab === 'freshness' && !freshnessLoaded.value) {
    loadWorkflowFreshness()
  }
})

watch(currentDolphinConfigId, async (nextId, prevId) => {
  if (nextId === prevId) {
    return
  }
  dolphinWebuiUrl.value = ''
  scheduleOptionsLoaded.value = false
  taskGroupOptions.value = []
  if (workflow.value?.workflow?.id) {
    await loadDolphinConfig()
  }
})

onMounted(async () => {
  await Promise.all([loadDolphinConfigs(), loadWorkflowDetail()])
  await loadDolphinConfig()
  consumePublishHint()
})

watch(
  () => route.query.publishHint,
  () => consumePublishHint()
)
</script>

<style scoped>
/* 与执行监控的 .pagination 保持一致 */
.execution-pagination {
  justify-content: flex-end;
  margin-top: 20px;
}

.freshness-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.freshness-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.freshness-section-title {
  font-weight: 600;
  margin: 16px 0 8px;
}
.freshness-section-title:first-of-type {
  margin-top: 0;
}
.freshness-table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 16px 0 8px;
}
.freshness-table-header .freshness-section-title {
  margin: 0;
}
.instance-link {
  color: var(--el-color-primary);
  text-decoration: none;
}
.instance-link:hover {
  text-decoration: underline;
}

.workflow-detail {
  padding: 20px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.title {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 18px;
  font-weight: bold;
}

.name {
  margin-right: 8px;
}

.dolphin-code-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  width: 100%;
}

.scheduler-engine-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.scheduler-switch-alert {
  margin-bottom: 16px;
}

.scheduler-switch-form {
  margin-top: 4px;
}

.change-toolbar {
  display: flex;
  justify-content: flex-start;
  align-items: center;
  margin-bottom: 10px;
}

.change-toolbar-bottom {
  margin-top: 10px;
  margin-bottom: 0;
}

.version-label {
  color: #409eff;
  font-weight: 500;
}

.version-label.is-current {
  color: #303133;
  font-weight: 600;
}

.version-schema-tag {
  margin-left: 8px;
}

.basic-info-section {
  margin-bottom: 20px;
}

.section-title {
  font-size: 16px;
  font-weight: bold;
  margin-bottom: 12px;
  color: #303133;
}

.editable-field {
  display: inline-flex;
  align-items: center;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.editable-field:hover {
  background-color: #f5f7fa;
}

.editable-field .edit-icon {
  margin-left: 8px;
  color: #909399;
  font-size: 14px;
  opacity: 0;
  transition: opacity 0.2s;
}

.editable-field:hover .edit-icon {
  opacity: 1;
}

.params-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.edit-field {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.global-param-display {
  display: inline-block;
  width: 100%;
  min-height: 24px;
  line-height: 24px;
  color: #303133;
  word-break: break-all;
}

.global-param-display.is-empty {
  color: #909399;
}

.global-param-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-top: 4px;
}

.schedule-preview {
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
  width: 100%;
}

.schedule-preview-title {
  color: #606266;
  font-size: 12px;
  margin-bottom: 6px;
}

.schedule-preview-item {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
  font-size: 12px;
  line-height: 1.6;
}

.cron-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.cron-input-wrapper {
  flex: 1;
}

.cron-input-ref {
  width: 100%;
}

.cron-input-ref :deep(.el-input__inner) {
  cursor: pointer;
}

</style>
