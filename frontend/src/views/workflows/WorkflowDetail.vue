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
            <el-table
              v-if="workflow?.recentInstances?.length"
              :data="workflow.recentInstances"
              border
              size="small"
            >
              <el-table-column prop="instanceId" label="实例ID" width="140">
                <template #default="{ row }">
                  <el-link type="primary" @click="openDolphinInstance(row)" :disabled="!buildDolphinInstanceUrl(row)">
                    #{{ row.instanceId }}
                  </el-link>
                </template>
              </el-table-column>
              <el-table-column prop="state" label="状态" width="120">
                <template #default="{ row }">
                  <el-tag size="small" :type="getInstanceStateType(row.state)">
                    {{ getInstanceStateText(row.state) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="triggerType" label="触发方式" width="120">
                <template #default="{ row }">
                  {{ getTriggerText(row.triggerType) }}
                </template>
              </el-table-column>
              <el-table-column prop="startTime" label="开始时间" width="170">
                <template #default="{ row }">
                  {{ formatDateTime(row.startTime) }}
                </template>
              </el-table-column>
              <el-table-column prop="endTime" label="结束时间" width="170">
                <template #default="{ row }">
                  {{ formatDateTime(row.endTime) }}
                </template>
              </el-table-column>
              <el-table-column prop="durationMs" label="耗时" width="120">
                <template #default="{ row }">
                  {{ formatDuration(row.durationMs, row.startTime, row.endTime) }}
                </template>
              </el-table-column>
            </el-table>
            <el-empty
              v-else
              description="暂无执行记录"
            />
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
  buildPublishRepairHtml,
  firstPreviewErrorMessage,
  isDialogCancel,
  resolvePublishVersionId,
  shouldPromptOnlineAfterDeploy
} from './publishPreviewHelper'
import QuartzCronBuilder from '@/components/QuartzCronBuilder.vue'

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
const changeMode = ref('list')
const compareLoading = ref(false)
const versionCompareResult = ref(null)
const leftVersionId = ref(null)
const rightVersionId = ref(null)
const versionHistoryTableRef = ref(null)
const selectedHistoryVersions = ref([])
const rollbackLoadingVersionId = ref(null)
const deleteLoadingVersionId = ref(null)
const versionPublishRecordDialogVisible = ref(false)
const activeVersionPublishRecords = ref([])
const activeVersionForRecords = ref(null)
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
const scheduleFormRef = ref(null)
const savingSchedule = ref(false)
const scheduleSwitchLoading = ref(false)
const scheduleEnabled = ref(false)
const scheduleSwitchMuted = ref(false)
const scheduleOptionsLoading = ref(false)
const scheduleOptionsLoaded = ref(false)
const schedulePreviewLoading = ref(false)
const schedulePreviewList = ref([])
const cronBuilderVisible = ref(false)
const workerGroupOptions = ref([])
const tenantOptions = ref([])
const alertGroupOptions = ref([])
const environmentOptions = ref([])
const isScheduleOnline = computed(() => {
  return (workflow.value?.workflow?.scheduleState || '').toUpperCase() === 'ONLINE'
})
const timezoneOptions = computed(() => {
  try {
    if (typeof Intl !== 'undefined' && typeof Intl.supportedValuesOf === 'function') {
      return Intl.supportedValuesOf('timeZone')
    }
  } catch {
    // ignore
  }
  return [
    'Asia/Shanghai',
    'UTC',
    'Asia/Hong_Kong',
    'Asia/Singapore',
    'Asia/Tokyo',
    'Europe/London',
    'America/New_York',
    'America/Los_Angeles'
  ]
})
const defaultTimezone = (() => {
  try {
    const tz = Intl?.DateTimeFormat?.().resolvedOptions?.().timeZone
    return tz || 'Asia/Shanghai'
  } catch {
    return 'Asia/Shanghai'
  }
})()
const defaultStartEndTime = (() => {
  const start = dayjs().startOf('day')
  return [
    start.format('YYYY-MM-DD HH:mm:ss'),
    start.add(100, 'year').format('YYYY-MM-DD HH:mm:ss')
  ]
})()
const scheduleForm = reactive({
  scheduleStartEndTime: defaultStartEndTime,
  scheduleCron: '0 0 * * * ? *',
  scheduleTimezone: defaultTimezone,
  scheduleProcessInstancePriority: 'MEDIUM',
  scheduleWorkerGroup: 'default',
  scheduleTenantCode: 'default',
  scheduleEnvironmentCode: -1,
  scheduleFailureStrategy: 'CONTINUE',
  scheduleWarningType: 'NONE',
  scheduleWarningGroupId: null,
  scheduleAutoOnline: false
})
const environmentFilteredOptions = computed(() => {
  const selectedWorkerGroup = scheduleForm.scheduleWorkerGroup
  if (!selectedWorkerGroup) {
    return []
  }
  return (environmentOptions.value || []).filter((env) => {
    const groups = env?.workerGroups || []
    return Array.isArray(groups) && groups.includes(selectedWorkerGroup)
  })
})
const scheduleRules = {
  scheduleStartEndTime: [
    { required: true, message: '请选择起止时间', trigger: 'change' },
    {
      validator: (_, value, callback) => {
        const start = Array.isArray(value) ? value?.[0] : null
        const end = Array.isArray(value) ? value?.[1] : null
        if (!start || !end) {
          callback(new Error('请选择起止时间'))
          return
        }
        const startTs = dayjs(start).valueOf()
        const endTs = dayjs(end).valueOf()
        if (Number.isFinite(startTs) && Number.isFinite(endTs) && endTs < startTs) {
          callback(new Error('结束时间需晚于开始时间'))
          return
        }
        callback()
      },
      trigger: 'change'
    }
  ],
  scheduleCron: [
    { required: true, message: '请输入 Cron 表达式', trigger: 'blur' },
    {
      validator: (_, value, callback) => {
        const parts = String(value || '')
          .trim()
          .split(/\s+/)
          .filter(Boolean)
        if (parts.length !== 7) {
          callback(new Error('Cron 需为 Quartz 7 段：秒 分 时 日 月 周 年'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  scheduleTimezone: [{ required: true, message: '请输入时区', trigger: 'blur' }],
  scheduleWarningGroupId: [
    {
      validator: (_, value, callback) => {
        if (scheduleForm.scheduleWarningType === 'NONE') {
          callback()
          return
        }
        if (!value || Number(value) <= 0) {
          callback(new Error('请选择告警组'))
          return
        }
        callback()
      },
      trigger: 'change'
    }
  ]
}

const buildDolphinConfigParams = () => {
  return currentDolphinConfigId.value
    ? { dolphinConfigId: currentDolphinConfigId.value }
    : {}
}

const loadScheduleOptions = async (force = false) => {
  if (scheduleOptionsLoaded.value && !force) {
    return
  }
  scheduleOptionsLoading.value = true
  try {
    const params = buildDolphinConfigParams()
    const [workerGroups, tenants, alertGroups, environments] = await Promise.all([
      taskApi.fetchWorkerGroups(params).catch(() => []),
      taskApi.fetchTenants(params).catch(() => []),
      taskApi.fetchAlertGroups(params).catch(() => []),
      taskApi.fetchEnvironments(params).catch(() => [])
    ])
    workerGroupOptions.value = workerGroups || []
    tenantOptions.value = tenants || []
    alertGroupOptions.value = alertGroups || []
    environmentOptions.value = environments || []
    scheduleOptionsLoaded.value = true
  } finally {
    scheduleOptionsLoading.value = false
  }
}

const handleWorkerGroupChange = () => {
  scheduleForm.scheduleEnvironmentCode = -1
}

const previewScheduleTimes = async () => {
  if (isScheduleOnline.value) {
    return
  }
  const [startTime, endTime] = Array.isArray(scheduleForm.scheduleStartEndTime)
    ? scheduleForm.scheduleStartEndTime
    : []
  if (!startTime || !endTime) {
    ElMessage.warning('请选择起止时间')
    return
  }
  if (!String(scheduleForm.scheduleCron || '').trim()) {
    ElMessage.warning('请输入 Cron 表达式')
    return
  }
  if (!String(scheduleForm.scheduleTimezone || '').trim()) {
    ElMessage.warning('请输入时区')
    return
  }

  schedulePreviewLoading.value = true
  try {
    const schedule = JSON.stringify({
      startTime,
      endTime,
      crontab: scheduleForm.scheduleCron,
      timezoneId: scheduleForm.scheduleTimezone
    })
    const res = await taskApi.previewSchedule({ schedule }, buildDolphinConfigParams())
    schedulePreviewList.value = Array.isArray(res) ? res : []
  } catch (error) {
    console.error('预览调度时间失败', error)
  } finally {
    schedulePreviewLoading.value = false
  }
}

watch(activeTab, async (tab) => {
  if (tab === 'schedule') {
    await loadScheduleOptions()
  }
})

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

// Inline editing states
const isEditingName = ref(false)
const isEditingDescription = ref(false)
const isEditingTaskGroup = ref(false)
const editingName = ref('')
const editingDescription = ref('')
const editingTaskGroup = ref('')
const savingField = ref(false)

const taskGroupsLoading = ref(false)
const taskGroupOptions = ref([])

// Global params state
const globalParamsList = ref([])
const savingParams = ref(false)

const cloneGlobalParamCore = (param = {}) => {
  return {
    prop: String(param?.prop ?? '').trim(),
    direct: param?.direct || 'IN',
    type: param?.type || 'VARCHAR',
    value: param?.value ?? ''
  }
}

const createGlobalParamRow = (param = {}, options = {}) => {
  return {
    ...cloneGlobalParamCore(param),
    __editing: Boolean(options.editing),
    __isNew: Boolean(options.isNew),
    __backup: options.backup || null
  }
}

const normalizeGlobalParams = (params) => {
  if (!Array.isArray(params)) {
    return []
  }
  return params.map(item => createGlobalParamRow(item))
}

const serializeGlobalParams = () => {
  return globalParamsList.value.map(item => cloneGlobalParamCore(item))
}

const isGlobalParamEmpty = (value) => {
  return value === null || value === undefined || value === ''
}

const formatGlobalParamDisplay = (value) => {
  return isGlobalParamEmpty(value) ? '-' : String(value)
}

// Computed workflow task IDs
const workflowTaskIds = computed(() => {
  const relations = workflow.value?.taskRelations || []
  return relations.map(r => Number(r.taskId)).filter(id => Number.isFinite(id))
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

const selectedHistoryVersionIds = computed(() => {
  return selectedHistoryVersions.value
    .map((item) => Number(item?.id))
    .filter((id) => Number.isFinite(id))
})

const canCompareSelected = computed(() => selectedHistoryVersionIds.value.length === 2)

const versionPublishRecordDialogTitle = computed(() => {
  const versionNo = activeVersionForRecords.value?.versionNo
  return Number.isFinite(Number(versionNo))
    ? `版本 ${versionNo} 发布记录`
    : '发布记录'
})

// Inline edit methods
const startEditName = () => {
  editingName.value = workflow.value?.workflow?.workflowName || ''
  isEditingName.value = true
}

const cancelEditName = () => {
  isEditingName.value = false
  editingName.value = ''
}

const saveNameField = async () => {
  if (!editingName.value.trim()) {
    ElMessage.warning('名称不能为空')
    return
  }
  savingField.value = true
  try {
    const wf = workflow.value?.workflow
    await workflowApi.update(wf.id, {
      workflowName: editingName.value.trim(),
      description: wf.description,
      taskGroupName: wf.taskGroupName || null,
      tasks: workflowTaskIds.value.map(taskId => ({ taskId })),
      globalParams: wf.globalParams,
      operator: 'portal-ui'
    })
    ElMessage.success('名称更新成功')
    isEditingName.value = false
    loadWorkflowDetail()
  } catch (error) {
    console.error('更新名称失败', error)
    ElMessage.error(error?.response?.data?.message || '更新失败')
  } finally {
    savingField.value = false
  }
}

const loadTaskGroupOptions = async () => {
  if (taskGroupOptions.value.length) {
    return
  }
  taskGroupsLoading.value = true
  try {
    const res = await taskApi.fetchTaskGroups(buildDolphinConfigParams())
    taskGroupOptions.value = res || []
  } catch (error) {
    console.error('加载任务组失败', error)
    ElMessage.warning('任务组目录加载失败，可继续编辑并保存')
  } finally {
    taskGroupsLoading.value = false
  }
}

const handleTaskGroupDropdown = async (visible) => {
  if (visible && !taskGroupOptions.value.length) {
    await loadTaskGroupOptions()
  }
}

const startEditTaskGroup = async () => {
  editingTaskGroup.value = workflow.value?.workflow?.taskGroupName || ''
  isEditingTaskGroup.value = true
  await loadTaskGroupOptions()
}

const cancelEditTaskGroup = () => {
  isEditingTaskGroup.value = false
  editingTaskGroup.value = ''
}

const saveTaskGroupField = async () => {
  savingField.value = true
  try {
    const wf = workflow.value?.workflow
    await workflowApi.update(wf.id, {
      workflowName: wf.workflowName,
      description: wf.description,
      taskGroupName: editingTaskGroup.value || null,
      tasks: workflowTaskIds.value.map(taskId => ({ taskId })),
      globalParams: wf.globalParams,
      operator: 'portal-ui'
    })
    ElMessage.success('任务组更新成功')
    isEditingTaskGroup.value = false
    loadWorkflowDetail()
  } catch (error) {
    console.error('更新任务组失败', error)
    ElMessage.error(error?.response?.data?.message || '更新失败')
  } finally {
    savingField.value = false
  }
}

const startEditDescription = () => {
  editingDescription.value = workflow.value?.workflow?.description || ''
  isEditingDescription.value = true
}

const cancelEditDescription = () => {
  isEditingDescription.value = false
  editingDescription.value = ''
}

const saveDescriptionField = async () => {
  savingField.value = true
  try {
    const wf = workflow.value?.workflow
    await workflowApi.update(wf.id, {
      workflowName: wf.workflowName,
      description: editingDescription.value,
      taskGroupName: wf.taskGroupName || null,
      tasks: workflowTaskIds.value.map(taskId => ({ taskId })),
      globalParams: wf.globalParams,
      operator: 'portal-ui'
    })
    ElMessage.success('描述更新成功')
    isEditingDescription.value = false
    loadWorkflowDetail()
  } catch (error) {
    console.error('更新描述失败', error)
    ElMessage.error(error?.response?.data?.message || '更新失败')
  } finally {
    savingField.value = false
  }
}

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

const clearVersionHistorySelection = () => {
  selectedHistoryVersions.value = []
  nextTick(() => {
    versionHistoryTableRef.value?.clearSelection()
  })
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

const formatDolphinConfigOption = (item) => {
  if (!item) {
    return '-'
  }
  const parts = [item.configName || `Dolphin #${item.id}`]
  if (item.isDefault === 1) {
    parts.push('默认')
  }
  if (!item.isActive) {
    parts.push('停用')
  }
  return parts.join(' / ')
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

const getWorkflowStatusType = (status) => {
  const map = {
    draft: 'info',
    online: 'success',
    offline: 'warning',
    failed: 'danger'
  }
  return map[status] || 'info'
}

const getWorkflowStatusText = (status) => {
  const map = {
    draft: '草稿',
    online: '在线',
    offline: '下线',
    failed: '失败'
  }
  return map[status] || status || '-'
}

const getInstanceStateType = (state) => {
  const map = {
    SUCCESS: 'success',
    FAILED: 'danger',
    RUNNING: 'warning',
    STOP: 'info',
    KILL: 'info'
  }
  return map[state] || 'info'
}

const getInstanceStateText = (state) => {
  const map = {
    SUCCESS: '成功',
    FAILED: '失败',
    RUNNING: '运行中',
    STOP: '终止',
    KILL: '被终止'
  }
  return map[state] || state || '-'
}

const getTriggerText = (type) => {
  const map = {
    manual: '手动',
    schedule: '调度',
    api: 'API'
  }
  return map[type] || type || '-'
}

const getOperationText = (operation) => {
  const map = {
    deploy: '部署',
    online: '上线',
    offline: '下线'
  }
  return map[operation] || operation || '-'
}

const getPublishRecordStatusType = (status) => {
  const map = {
    success: 'success',
    failed: 'danger',
    pending: 'info',
    pending_approval: 'warning',
    rejected: 'danger'
  }
  return map[status] || 'info'
}

const getPublishRecordStatusText = (status) => {
  const map = {
    success: '成功',
    failed: '失败',
    pending: '进行中',
    pending_approval: '待审批',
    rejected: '已拒绝'
  }
  return map[status] || status || '-'
}

const formatDateTime = (value) => {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
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

const syncScheduleForm = () => {
  const wf = workflow.value?.workflow
  if (!wf) return

  scheduleForm.scheduleCron = wf.scheduleCron || '0 0 * * * ? *'
  scheduleForm.scheduleTimezone = wf.scheduleTimezone || defaultTimezone
  const startTime = wf.scheduleStartTime
    ? dayjs(wf.scheduleStartTime).format('YYYY-MM-DD HH:mm:ss')
    : null
  const endTime = wf.scheduleEndTime
    ? dayjs(wf.scheduleEndTime).format('YYYY-MM-DD HH:mm:ss')
    : null
  scheduleForm.scheduleStartEndTime = startTime && endTime ? [startTime, endTime] : defaultStartEndTime
  scheduleForm.scheduleFailureStrategy = wf.scheduleFailureStrategy || 'CONTINUE'
  const warningType = (wf.scheduleWarningType || 'NONE').toUpperCase()
  scheduleForm.scheduleWarningType = warningType === 'SUCCESS_FAILURE' ? 'ALL' : warningType
  scheduleForm.scheduleWarningGroupId =
    wf.scheduleWarningGroupId === null || wf.scheduleWarningGroupId === undefined
      ? 0
      : wf.scheduleWarningGroupId
  scheduleForm.scheduleProcessInstancePriority = wf.scheduleProcessInstancePriority || 'MEDIUM'
  scheduleForm.scheduleWorkerGroup = wf.scheduleWorkerGroup || 'default'
  scheduleForm.scheduleTenantCode = wf.scheduleTenantCode || 'default'
  scheduleForm.scheduleEnvironmentCode =
    wf.scheduleEnvironmentCode === null || wf.scheduleEnvironmentCode === undefined
      ? -1
      : wf.scheduleEnvironmentCode
  scheduleForm.scheduleAutoOnline = Boolean(wf.scheduleAutoOnline)
  schedulePreviewList.value = []

  scheduleSwitchMuted.value = true
  scheduleEnabled.value = (wf.scheduleState || '').toUpperCase() === 'ONLINE'
  nextTick(() => {
    scheduleSwitchMuted.value = false
  })
  scheduleFormRef.value?.clearValidate()
}

const formatDuration = (durationMs, startTime, endTime) => {
  let duration = durationMs
  if (!duration && startTime && endTime) {
    duration = dayjs(endTime).diff(dayjs(startTime))
  }
  if (!duration) {
    return '-'
  }
  const seconds = Math.floor(duration / 1000)
  const minutes = Math.floor(seconds / 60)
  const remainSeconds = seconds % 60
  return minutes ? `${minutes}分${remainSeconds}秒` : `${remainSeconds}秒`
}

const formatLog = (log) => {
  if (!log) {
    return '-'
  }
  try {
    const parsed = JSON.parse(log)
    if (parsed && typeof parsed === 'object') {
      return Object.entries(parsed)
        .map(([key, value]) => `${key}: ${value}`)
        .join(', ')
    }
    return log
  } catch (error) {
    return log
  }
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

const handleVersionSelectionChange = (rows) => {
  if (!Array.isArray(rows)) {
    selectedHistoryVersions.value = []
    return
  }
  if (rows.length <= 2) {
    selectedHistoryVersions.value = rows
    return
  }
  ElMessage.warning('最多选择两个版本进行比较')
  const keepRows = rows.slice(0, 2)
  selectedHistoryVersions.value = keepRows
  nextTick(() => {
    versionHistoryTableRef.value?.clearSelection()
    keepRows.forEach((item) => {
      versionHistoryTableRef.value?.toggleRowSelection(item, true)
    })
  })
}

const resolveLeftVersionId = (targetRightVersionId) => {
  const rightId = Number(targetRightVersionId)
  const index = versionList.value.findIndex((item) => Number(item.id) === rightId)
  if (index <= 0) {
    return null
  }
  return versionList.value[index - 1]?.id || null
}

const loadVersionCompare = async (leftId, rightId) => {
  const wf = workflow.value?.workflow
  const normalizedRightId = Number(rightId)
  if (!wf?.id || !Number.isFinite(normalizedRightId)) {
    return
  }
  compareLoading.value = true
  try {
    const result = await workflowApi.compareVersions(wf.id, {
      leftVersionId: leftId ?? null,
      rightVersionId: normalizedRightId,
      operator: 'portal-ui'
    })
    versionCompareResult.value = result
    leftVersionId.value = result.leftVersionId ?? null
    rightVersionId.value = result.rightVersionId ?? normalizedRightId
    changeMode.value = 'compare'
  } catch (error) {
    console.error('加载版本差异失败', error)
    ElMessage.error(error.message || '加载版本差异失败')
  } finally {
    compareLoading.value = false
  }
}

const compareSelectedVersions = async () => {
  if (!canCompareSelected.value) {
    ElMessage.warning('请选择两个版本进行比较')
    return
  }
  if (selectedHistoryVersions.value.some((item) => !item?.isV3)) {
    ElMessage.warning('仅支持 V3，请先保存生成 V3 基线')
    return
  }
  const sorted = [...selectedHistoryVersions.value].sort((left, right) => {
    const leftNo = Number(left?.versionNo || 0)
    const rightNo = Number(right?.versionNo || 0)
    if (leftNo !== rightNo) {
      return leftNo - rightNo
    }
    return Number(left?.id || 0) - Number(right?.id || 0)
  })
  const leftVersion = sorted[0]
  const rightVersion = sorted[1]
  await loadVersionCompare(leftVersion?.id || null, rightVersion?.id || null)
}

const stepVersionCompare = async (direction) => {
  if (!rightVersionId.value || !versionList.value.length) {
    return
  }
  const currentRightId = Number(rightVersionId.value)
  const index = versionList.value.findIndex((item) => Number(item.id) === currentRightId)
  if (index < 0) {
    return
  }

  if (direction === 'left') {
    const nextRightIndex = index - 1
    if (nextRightIndex < 0) {
      return
    }
    const right = versionList.value[nextRightIndex]
    const left = nextRightIndex > 0 ? versionList.value[nextRightIndex - 1]?.id : null
    await loadVersionCompare(left, right.id)
    return
  }

  const nextRightIndex = index + 1
  if (nextRightIndex >= versionList.value.length) {
    return
  }
  const right = versionList.value[nextRightIndex]
  const left = versionList.value[index]?.id || null
  await loadVersionCompare(left, right.id)
}

const openVersionPublishRecords = (row) => {
  activeVersionForRecords.value = row || null
  const versionId = Number(row?.id)
  if (Number.isFinite(versionId)) {
    activeVersionPublishRecords.value = publishRecordsByVersionId.value[versionId] || []
  } else {
    activeVersionPublishRecords.value = []
  }
  versionPublishRecordDialogVisible.value = true
}

const rollbackToVersion = async (versionId) => {
  const wf = workflow.value?.workflow
  const normalizedVersionId = Number(versionId)
  if (!wf?.id || !Number.isFinite(normalizedVersionId)) {
    return
  }
  const version = versionById.value[normalizedVersionId]
  const rollbackDisabledReason = getRollbackDisabledReason(version)
  if (rollbackDisabledReason) {
    ElMessage.warning(rollbackDisabledReason)
    return
  }
  const label = version ? `版本 ${version.versionNo}` : `#${versionId}`
  try {
    await ElMessageBox.confirm(
      `确认恢复到${label}吗？恢复后会生成一个新版本。`,
      '确认恢复',
      {
        type: 'warning',
        confirmButtonText: '确认恢复',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  rollbackLoadingVersionId.value = normalizedVersionId
  try {
    const response = await workflowApi.rollbackVersion(wf.id, normalizedVersionId, {
      operator: 'portal-ui'
    })
    ElMessage.success(`恢复成功，已生成版本 v${response.newVersionNo}`)
    backToPublishRecords()
    await loadWorkflowDetail()
  } catch (error) {
    console.error('恢复版本失败', error)
    ElMessage.error(error.message || '恢复版本失败')
  } finally {
    rollbackLoadingVersionId.value = null
  }
}

const getRollbackDisabledReason = (row) => {
  if (!row) {
    return '无效版本'
  }
  const schemaVersion = Number(row?.snapshotSchemaVersion)
  const isV3 = row?.isV3 === true || (Number.isFinite(schemaVersion) ? schemaVersion === 3 : false)
  if (!isV3) {
    return '仅支持 V3，请先保存生成 V3 基线'
  }
  const rowVersionId = Number(row?.id)
  const currentVersionId = Number(workflow.value?.workflow?.currentVersionId)
  if (row?.isCurrent || (Number.isFinite(rowVersionId) && rowVersionId === currentVersionId)) {
    return '当前版本无需恢复'
  }
  return ''
}

const getVersionDeleteDisabledReason = (row) => {
  const versionId = Number(row?.id)
  if (!Number.isFinite(versionId)) {
    return '无效版本'
  }
  const currentVersionId = Number(workflow.value?.workflow?.currentVersionId)
  if (Number.isFinite(currentVersionId) && currentVersionId === versionId) {
    return '当前版本不可删除'
  }
  if (lastSuccessfulPublishedVersionId.value !== null
    && versionId === Number(lastSuccessfulPublishedVersionId.value)) {
    return '最后一次成功发布版本不可删除'
  }
  return ''
}

const deleteVersion = async (row) => {
  const wf = workflow.value?.workflow
  const versionId = Number(row?.id)
  if (!wf?.id || !Number.isFinite(versionId)) {
    return
  }
  const disabledReason = getVersionDeleteDisabledReason(row)
  if (disabledReason) {
    ElMessage.warning(disabledReason)
    return
  }
  const label = row?.versionNo ? `版本 ${row.versionNo}` : `#${versionId}`
  try {
    await ElMessageBox.confirm(
      `确认删除${label}吗？删除后不可恢复。`,
      '确认删除版本',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消'
      }
    )
  } catch {
    return
  }

  deleteLoadingVersionId.value = versionId
  try {
    await workflowApi.deleteVersion(wf.id, versionId)
    ElMessage.success('版本删除成功')
    await loadWorkflowDetail()
  } catch (error) {
    console.error('删除版本失败', error)
    ElMessage.error(error.message || '删除版本失败')
  } finally {
    deleteLoadingVersionId.value = null
  }
}

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
    ElMessage.success('导出成功')
  } catch (error) {
    console.error('导出工作流 JSON 失败', error)
  }
}

// Action Handlers
const getErrorMessage = (error) => {
  return error?.response?.data?.message || error?.message || '操作失败，请稍后重试'
}

const previewPublishAndConfirm = async (row) => {
  if (!row?.id) {
    return false
  }
  let preview = await workflowApi.previewPublish(row.id)
  if (!preview?.canPublish) {
    ElMessage.error(firstPreviewErrorMessage(preview))
    return false
  }
  const repairIssues = Array.isArray(preview?.repairIssues)
    ? preview.repairIssues.filter(issue => issue?.repairable !== false)
    : []
  if (repairIssues.length) {
    try {
      await ElMessageBox.confirm(
        buildPublishRepairHtml(preview),
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
      const unresolvedRepairIssues = Array.isArray(preview?.repairIssues)
        ? preview.repairIssues.filter(issue => issue?.repairable !== false)
        : []
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

const saveScheduleConfig = async () => {
  const wf = workflow.value?.workflow
  if (!wf?.id) return

  try {
    await scheduleFormRef.value?.validate()
  } catch {
    return
  }

  savingSchedule.value = true
  try {
    const [startTime, endTime] = Array.isArray(scheduleForm.scheduleStartEndTime)
      ? scheduleForm.scheduleStartEndTime
      : []
    await workflowApi.updateSchedule(wf.id, {
      scheduleCron: scheduleForm.scheduleCron,
      scheduleTimezone: scheduleForm.scheduleTimezone,
      scheduleStartTime: startTime,
      scheduleEndTime: endTime,
      scheduleFailureStrategy: scheduleForm.scheduleFailureStrategy,
      scheduleWarningType: scheduleForm.scheduleWarningType,
      scheduleWarningGroupId:
        scheduleForm.scheduleWarningType === 'NONE' ? 0 : scheduleForm.scheduleWarningGroupId,
      scheduleProcessInstancePriority: scheduleForm.scheduleProcessInstancePriority,
      scheduleWorkerGroup: scheduleForm.scheduleWorkerGroup || null,
      scheduleTenantCode: scheduleForm.scheduleTenantCode || null,
      scheduleEnvironmentCode:
        scheduleForm.scheduleEnvironmentCode === null || scheduleForm.scheduleEnvironmentCode === undefined
          ? -1
          : scheduleForm.scheduleEnvironmentCode,
      scheduleAutoOnline: scheduleForm.scheduleAutoOnline
    })
    ElMessage.success('调度配置已保存')
    loadWorkflowDetail()
  } catch (error) {
    console.error('保存调度配置失败', error)
    ElMessage.error(getErrorMessage(error))
  } finally {
    savingSchedule.value = false
  }
}

const handleToggleSchedule = async (val) => {
  if (scheduleSwitchMuted.value) {
    return
  }
  const wf = workflow.value?.workflow
  if (!wf?.id) return
  if (!wf.dolphinScheduleId) {
    ElMessage.warning('请先保存调度配置')
    scheduleEnabled.value = false
    return
  }
  if (val === true && wf.status !== 'online') {
    ElMessage.warning('工作流未上线，无法上线调度')
    scheduleEnabled.value = false
    return
  }

  scheduleSwitchLoading.value = true
  try {
    if (val) {
      await workflowApi.onlineSchedule(wf.id)
      ElMessage.success('调度已上线')
    } else {
      await workflowApi.offlineSchedule(wf.id)
      ElMessage.success('调度已下线')
    }
    loadWorkflowDetail()
  } catch (error) {
    console.error('切换调度状态失败', error)
    ElMessage.error(getErrorMessage(error))
    scheduleEnabled.value = !val
  } finally {
    scheduleSwitchLoading.value = false
  }
}

const consumePublishHint = () => {
  if (String(route.query.publishHint || '') !== '1') return
  ElMessage.warning('工作流有变化，请使用发布按钮将工作流发布到 Dolphin。')
  const nextQuery = { ...route.query }
  delete nextQuery.publishHint
  router.replace({ path: route.path, query: nextQuery })
}

const buildDolphinInstanceUrl = (instance) => {
  const wf = workflow.value?.workflow
  if (!wf || !dolphinWebuiUrl.value) {
    return ''
  }
  if (!wf.projectCode || !wf.workflowCode || !instance?.instanceId) {
    return ''
  }
  const base = dolphinWebuiUrl.value.replace(/\/+$/, '')
  return `${base}/ui/projects/${wf.projectCode}/workflow/instances/${instance.instanceId}?code=${wf.workflowCode}`
}

const openDolphinInstance = (instance) => {
  const url = buildDolphinInstanceUrl(instance)
  if (!url) {
    ElMessage.warning('无法跳转到实例详情')
    return
  }
  window.open(url, '_blank')
}

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
