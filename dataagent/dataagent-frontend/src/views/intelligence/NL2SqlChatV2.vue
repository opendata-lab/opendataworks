<template>
  <div class="v2-workbench" :class="{ 'artifacts-open': artifactsPanelOpen && !isWidgetMode }">
    <!-- Sidebar: topic list + agent selector -->
    <aside class="v2-sidebar">
      <div class="v2-sidebar-head">
        <el-select
          v-model="agentSelectValue"
          class="v2-agent-select"
          :disabled="!agents.length"
          @change="handleAgentChange"
        >
          <template #prefix>
            <svg class="v2-agent-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2V4" />
              <rect x="4" y="6" width="16" height="12" rx="2" />
              <circle cx="9" cy="12" r="1.5" fill="currentColor" stroke="none" />
              <circle cx="15" cy="12" r="1.5" fill="currentColor" stroke="none" />
              <path d="M9 16c1.5 1 4.5 1 6 0" />
            </svg>
          </template>
          <el-option v-for="a in agents" :key="a.agent_id" :label="a.name" :value="a.agent_id" />
        </el-select>
        <button v-if="!isWidgetMode" class="v2-btn-new" @click="handleNewTopic">新建</button>
      </div>

      <div class="v2-sidebar-toolbar">
        <input v-model="searchKeyword" class="v2-search-input" type="text" placeholder="搜索话题">
        <el-popover
          v-model:visible="filterPopoverVisible"
          placement="bottom-end"
          trigger="click"
          :width="240"
          popper-class="v2-filter-popper"
        >
          <template #reference>
            <button
              type="button"
              class="v2-filter-btn"
              :class="{ active: hasActiveFilters }"
              title="筛选与排序"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="15" height="15"><polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3" /></svg>
            </button>
          </template>
          <div class="v2-filter-panel">
            <div class="v2-filter-group">
              <div class="v2-filter-label">来源</div>
              <div class="v2-source-tabs" role="tablist">
                <button
                  type="button"
                  class="v2-source-tab"
                  :class="{ active: sourceMode === 'portal' }"
                  @click="handleSourceChange('portal')"
                >门户</button>
                <button
                  type="button"
                  class="v2-source-tab"
                  :class="{ active: sourceMode === 'widget' }"
                  @click="handleSourceChange('widget')"
                >Widget</button>
              </div>
            </div>
            <div v-if="isWidgetMode" class="v2-filter-group">
              <div class="v2-filter-label">用户</div>
              <el-select
                v-model="filterUser"
                size="small"
                clearable
                filterable
                remote
                reserve-keyword
                :remote-method="fetchWidgetUsers"
                :loading="widgetUsersLoading"
                placeholder="全部用户"
                class="v2-filter-select"
                @visible-change="handleUserSelectVisible"
              >
                <el-option v-for="u in widgetUserOptions" :key="u.value" :label="u.label" :value="u.value">
                  <span class="v2-user-opt-label">{{ u.label }}</span>
                  <span v-if="u.count" class="v2-user-opt-count">{{ u.count }}</span>
                </el-option>
              </el-select>
            </div>
            <div class="v2-filter-group">
              <div class="v2-filter-label">状态</div>
              <el-radio-group v-model="filterStatus" size="small" class="v2-filter-radios">
                <el-radio label="">全部</el-radio>
                <el-radio label="running">进行中</el-radio>
                <el-radio label="error">失败</el-radio>
                <el-radio label="suspended">已取消</el-radio>
                <el-radio label="finished">完成</el-radio>
              </el-radio-group>
            </div>
            <div class="v2-filter-group">
              <div class="v2-filter-label">排序</div>
              <el-radio-group v-model="sortOrder" size="small" class="v2-filter-radios">
                <el-radio label="updated_desc">最近更新</el-radio>
                <el-radio label="created_desc">最近创建</el-radio>
                <el-radio label="title_asc">标题 A-Z</el-radio>
              </el-radio-group>
            </div>
            <div class="v2-filter-actions">
              <button type="button" class="v2-filter-reset" @click="resetFilters">重置</button>
            </div>
          </div>
        </el-popover>
      </div>

      <el-scrollbar class="v2-session-scroll">
        <div class="v2-session-list">
          <button
            v-for="topic in filteredTopics"
            :key="topic.topic_id"
            class="v2-session-item"
            :class="{ active: topic.topic_id === activeTopicId }"
            @click="handleSelectTopic(topic.topic_id)"
          >
            <span class="v2-session-title">{{ topic.title || '新话题' }}</span>
            <span v-if="isTopicWorking(topic)" class="v2-session-loading" title="正在分析中...">
              <svg class="v2-session-spinner" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                <circle class="v2-session-spinner-track" cx="12" cy="12" r="10" stroke-width="3" />
                <path class="v2-session-spinner-head" d="M12 2a10 10 0 0 1 10 10" stroke-width="3" stroke-linecap="round" />
              </svg>
            </span>
            <span v-else class="v2-session-meta">
              <span v-if="topicBadgeKind(topic) === 'error'" class="v2-session-dot is-error" title="执行失败" />
              <span v-else-if="topicBadgeKind(topic) === 'suspended'" class="v2-session-dot is-suspended" title="已取消" />
              {{ formatTime(topic.updated_at || topic.created_at) }}
            </span>
          </button>
          <div v-if="!filteredTopics.length" class="v2-empty-sessions">暂无话题</div>
        </div>
      </el-scrollbar>
    </aside>

    <!-- Main chat area -->
    <main class="v2-main">
      <div v-if="messages.length" class="v2-main-top-bar">
        <h4 class="v2-topic-title">{{ activeTopic?.title }}</h4>
        <button
          v-if="!isWidgetMode"
          type="button"
          class="v2-artifacts-toggle"
          :class="{ active: artifactsPanelOpen }"
          title="会话文件 / 产物"
          @click="toggleArtifactsPanel"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M4 5h6l2 2h8v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5z" /></svg>
          <span>文件</span>
        </button>
      </div>

      <el-scrollbar v-show="messages.length" ref="messagesScrollbarRef" class="v2-messages" @scroll="handleScroll">
        <div class="v2-messages-inner">
          <!-- Message loop -->
          <template v-for="msg in messages" :key="msg.id">
            <!-- User message -->
            <div
              v-if="msg.role === 'user'"
              class="v2-msg-row v2-msg-user"
              :class="{ 'is-target-message': msg.id === targetMessageId }"
              :data-message-id="msg.id"
            >
              <div class="v2-user-shell">
                <div class="v2-user-bubble">{{ msg.content }}</div>
                <div class="v2-msg-footer">
                  <span v-if="msg.created_at" class="v2-msg-time">{{ formatMessageTime(msg.created_at) }}</span>
                  <button type="button" class="v2-message-tool" title="复制" aria-label="复制消息" @click.stop="handleCopyMessage(msg)">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><rect x="9" y="9" width="10" height="10" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v1" /></svg>
                  </button>
                </div>
              </div>
            </div>

            <!-- Assistant message -->
            <div
              v-else
              class="v2-msg-row v2-msg-assistant"
              :class="{ 'is-target-message': msg.id === targetMessageId }"
              :data-message-id="msg.id"
            >
              <div class="v2-assistant-body">
                <!-- Streaming: render turns from v2 state -->
                <template v-if="msg._v2state">
                  <!-- Loading indicator: waiting for first block -->
                  <div v-if="!msg._v2state.turns.length && isStreaming" class="v2-typing-indicator">
                    <span /><span /><span />
                  </div>

                  <template v-for="(turn, ti) in msg._v2state.turns" :key="ti">
                    <template v-for="block in turn.blocks" :key="block.blockIndex + '-' + ti">
                      <!-- Thinking block -->
                      <div v-if="block.type === 'thinking'" class="v2-process-panel">
                        <button
                          class="v2-process-summary"
                          type="button"
                          @click="toggleThinking(msg.id + '-' + ti + '-' + block.blockIndex)"
                        >
                          <span class="v2-process-label">
                            <span v-if="block.status === 'streaming'" class="v2-badge-dot" />
                            深度思考
                          </span>
                          <span v-if="!thinkingExpanded[msg.id + '-' + ti + '-' + block.blockIndex]" class="v2-process-preview">
                            {{ block.content.slice(0, 80) }}
                          </span>
                          <svg class="v2-chevron" :class="{ expanded: thinkingExpanded[msg.id + '-' + ti + '-' + block.blockIndex] }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9l6 6 6-6" /></svg>
                        </button>
                        <el-scrollbar v-if="thinkingExpanded[msg.id + '-' + ti + '-' + block.blockIndex]" class="v2-process-content">
                          <div class="v2-process-thought" v-html="renderMarkdown(block.content)" />
                          <span v-if="block.status === 'streaming'" class="v2-cursor">|</span>
                        </el-scrollbar>
                      </div>

                      <!-- Tool use block (chart-producing tools render their chart directly below the block) -->
                      <div v-else-if="block.type === 'tool_use'" class="v2-tool-row">
                        <ToolOutputRenderer :tool="blockToToolProp(block)" />
                      </div>

                      <!-- Text block (inline chart_spec rendered as a real chart) -->
                      <div v-else-if="block.type === 'text' && block.content" class="v2-text-block">
                        <template v-for="(seg, si) in answerSegments(block.content)" :key="si">
                          <div v-if="seg.type === 'text'" v-html="renderMarkdown(seg.value)" />
                          <ChartSpecView v-else :spec="seg.spec" />
                        </template>
                        <span v-if="block.status === 'streaming'" class="v2-cursor">|</span>
                      </div>
                    </template>
                  </template>

                  <!-- Error from stream -->
                  <div v-if="msg._v2state.status === 'error'" class="v2-error-card">
                    <span class="v2-error-label">错误</span>
                    {{ msg._v2state.errorText || '流式处理出错' }}
                  </div>
                </template>

                <!-- Fallback for non-assistant or empty v2state -->
                <template v-else>
                  <div v-if="msg.content" class="v2-text-block">
                    <template v-for="(seg, si) in answerSegments(msg.content)" :key="si">
                      <div v-if="seg.type === 'text'" v-html="renderMarkdown(seg.value)" />
                      <ChartSpecView v-else :spec="seg.spec" />
                    </template>
                  </div>
                </template>

                <!-- Message footer -->
                <div class="v2-msg-footer">
                  <span v-if="msg.created_at" class="v2-msg-time">{{ formatMessageTime(msg.created_at) }}</span>
                  <button type="button" class="v2-message-tool" title="复制" aria-label="复制消息" @click.stop="handleCopyMessage(msg)">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><rect x="9" y="9" width="10" height="10" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v1" /></svg>
                  </button>
                  <button type="button" class="v2-message-tool v2-message-feedback-like" :class="{ active: msg.feedback === 'like' }" title="有帮助" aria-label="有帮助" @click.stop="toggleMessageFeedback(msg, 'like')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="M7 11v10H4a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2h3Z" /><path d="M7 11 12 2a3 3 0 0 1 3 3v4h4a2 2 0 0 1 2 2l-1 8a2 2 0 0 1-2 2H7" /></svg>
                  </button>
                  <button type="button" class="v2-message-tool v2-message-feedback-dislike" :class="{ active: msg.feedback === 'dislike' }" title="没帮助" aria-label="没帮助" @click.stop="toggleMessageFeedback(msg, 'dislike')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true"><path d="M17 13V3h3a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-3Z" /><path d="M17 13 12 22a3 3 0 0 1-3-3v-4H5a2 2 0 0 1-2-2l1-8a2 2 0 0 1 2-2h11" /></svg>
                  </button>
                </div>
              </div>
            </div>
          </template>
        </div>
      </el-scrollbar>

      <!-- Read-only banner for widget sessions -->
      <div v-if="isWidgetMode" class="v2-readonly-bar">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><rect x="3" y="11" width="18" height="11" rx="2" /><path d="M7 11V7a5 5 0 0 1 10 0v4" /></svg>
        <span>Widget 会话为只读审计视图，不能发送消息</span>
      </div>

      <!-- Composer -->
      <div v-else class="v2-composer-bar" :class="{ 'is-landing': !messages.length }">
        <div class="v2-composer-wrap">
          <template v-if="!messages.length">
            <div v-if="!settings.providers.length" class="v2-config-empty">
              <div class="v2-config-empty-title">还没有可用的模型</div>
              <div class="v2-config-empty-text">请先完成模型配置。</div>
            </div>
            <div class="v2-landing-greeting">您好，我是{{ currentAgentName }}。</div>
          </template>

          <!-- Attachment chips -->
          <div v-if="pendingAttachments.length" class="v2-attach-chips">
            <span
              v-for="(att, i) in pendingAttachments"
              :key="i"
              class="v2-attach-chip"
              :class="{ 'is-error': att.error, 'is-uploading': att.uploading }"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><path d="M21 12.5 12.5 21a4 4 0 0 1-5.66-5.66l8.49-8.49a2.5 2.5 0 0 1 3.54 3.54l-8.49 8.49a1 1 0 0 1-1.41-1.41l7.78-7.78" /></svg>
              <span class="v2-attach-name">{{ att.name }}</span>
              <span v-if="att.uploading" class="v2-attach-state">上传中…</span>
              <span v-else-if="att.error" class="v2-attach-state">失败</span>
              <button type="button" class="v2-attach-remove" title="移除" @click="removeAttachment(att)">×</button>
            </span>
          </div>

          <!-- Input bar -->
          <div class="v2-composer" :class="{ 'is-focused': inputText }">
            <input
              ref="fileInputRef"
              type="file"
              multiple
              class="v2-file-input"
              @change="handleFilesSelected"
            />
            <button
              type="button"
              class="v2-attach-btn"
              :disabled="isStreaming"
              title="上传文件"
              @click="triggerFilePicker"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><path d="M21 12.5 12.5 21a4 4 0 0 1-5.66-5.66l8.49-8.49a2.5 2.5 0 0 1 3.54 3.54l-8.49 8.49a1 1 0 0 1-1.41-1.41l7.78-7.78" /></svg>
            </button>
            <textarea
              ref="textareaRef"
              v-model="inputText"
              class="v2-textarea"
              :placeholder="isStreaming ? '正在回复中…' : '输入数据问题…'"
              :disabled="isStreaming || !availableModels.length"
              rows="1"
              @keydown.enter="onEnterKey"
              @input="autoResize"
            />
            <button
              type="button"
              class="v2-send-btn"
              :class="{ 'v2-cancel-btn': activeTaskId }"
              :disabled="activeTaskId ? false : !canSendV2"
              @click="activeTaskId ? handleCancel() : handleSend()"
            >
              <svg v-if="activeTaskId" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="15" height="15"><rect x="8" y="8" width="8" height="8" rx="1.5" /></svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="15" height="15"><line x1="12" y1="19" x2="12" y2="5" /><polyline points="5 12 12 5 19 12" /></svg>
            </button>
          </div>
          <!-- Bottom toolbar -->
          <div class="v2-composer-toolbar">
            <div class="v2-composer-toolbar-left">
              <span class="v2-composer-hint">Enter 发送，Shift + Enter 换行</span>
            </div>
            <div class="v2-composer-toolbar-right">
              <el-dropdown trigger="click" @command="handleModelCommand">
                <button type="button" class="v2-model-btn" title="切换模型">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="13" height="13"><path d="M12 2V4" /><rect x="4" y="6" width="16" height="12" rx="2" /><circle cx="9" cy="12" r="1.5" fill="currentColor" stroke="none" /><circle cx="15" cy="12" r="1.5" fill="currentColor" stroke="none" /></svg>
                  <span class="v2-model-label">{{ selectedModel || '默认' }}</span>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <template v-for="provider in settings.providers" :key="provider.provider_id">
                      <el-dropdown-item
                        v-for="model in provider.models"
                        :key="model"
                        :command="provider.provider_id + '::' + model"
                        :class="{ active: selectedProvider === provider.provider_id && selectedModel === model }"
                      >
                        {{ model }}
                      </el-dropdown-item>
                    </template>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>

          <template v-if="!messages.length">
            <div class="v2-landing-suggestions-title">您可以问我以下问题</div>
            <div class="v2-landing-suggestions">
              <button
                v-for="s in suggestions"
                :key="s"
                class="v2-suggestion-card"
                :disabled="isStreaming"
                @click="handleSuggestion(s)"
              >{{ s }}</button>
            </div>
          </template>
        </div>
      </div>
    </main>

    <!-- Right-side conversation artifact panel -->
    <aside v-if="artifactsPanelOpen && !isWidgetMode" class="v2-artifacts-panel">
      <div class="v2-artifacts-head">
        <span class="v2-artifacts-title">会话文件</span>
        <span class="v2-artifacts-actions">
          <button type="button" title="刷新" @click="refreshArtifacts">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M21 12a9 9 0 1 1-2.64-6.36M21 4v5h-5" /></svg>
          </button>
          <button type="button" title="收起" @click="toggleArtifactsPanel">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><line x1="6" y1="6" x2="18" y2="18" /><line x1="18" y1="6" x2="6" y2="18" /></svg>
          </button>
        </span>
      </div>

      <div v-if="previewArtifact" class="v2-artifact-preview">
        <div class="v2-artifact-preview-head">
          <button type="button" class="v2-artifact-back" @click="closeArtifactPreview">← 返回</button>
          <span class="v2-artifact-preview-name" :title="previewArtifact.name">{{ previewArtifact.name }}</span>
          <a class="v2-artifact-dl-link" :href="artifactDownloadUrl(previewArtifact)" download>下载</a>
        </div>
        <div class="v2-artifact-preview-body">
          <div v-if="previewError" class="v2-artifact-empty">{{ previewError }}</div>
          <iframe
            v-else-if="isHtmlArtifact(previewArtifact)"
            class="v2-artifact-frame"
            sandbox=""
            referrerpolicy="no-referrer"
            :srcdoc="previewText"
          ></iframe>
          <img
            v-else-if="isImageArtifact(previewArtifact)"
            class="v2-artifact-img"
            :src="artifactInlineUrl(previewArtifact)"
            :alt="previewArtifact.name"
          />
          <pre v-else-if="isTextArtifact(previewArtifact)" class="v2-artifact-text">{{ previewText }}</pre>
          <div v-else class="v2-artifact-empty">该文件不支持预览，请下载查看。</div>
        </div>
      </div>

      <el-scrollbar v-else class="v2-artifacts-scroll">
        <div v-if="artifactsLoading" class="v2-artifacts-empty">加载中…</div>
        <div v-else-if="!artifacts.length" class="v2-artifacts-empty">暂无文件</div>
        <button
          v-for="file in artifacts"
          v-else
          :key="file.rel_path"
          type="button"
          class="v2-artifact-item"
          @click="openArtifact(file)"
        >
          <span class="v2-artifact-name" :title="file.name">{{ file.name }}</span>
          <span class="v2-artifact-meta">
            <span class="v2-artifact-tag" :class="file.kind">{{ file.kind === 'input' ? '上传' : '生成' }}</span>
            <span class="v2-artifact-size">{{ formatBytes(file.size) }}</span>
          </span>
          <a
            class="v2-artifact-row-dl"
            :href="artifactDownloadUrl(file)"
            download
            title="下载"
            @click.stop
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M12 3v12m0 0 4-4m-4 4-4-4M5 21h14" /></svg>
          </a>
        </button>
      </el-scrollbar>
    </aside>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createNl2SqlApiClient } from '@/api/nl2sql'
import { dataagentApi } from '@/api/dataagent'
import ToolOutputRenderer from './ToolOutputRenderer.vue'
import ChartSpecView from './ChartSpecView.vue'
import { blockToToolProp } from './v2StreamParser'
import { splitChartSpecText, stripChartSpecsFromText } from './chartSpec'
import { topicStatusKind } from './topicStatus'
import { hydrateMessageFromApi, isPlainEnterSubmit, renderMarkdown } from './chatMessage'
import { useNl2SqlChat } from './useNl2SqlChat'
import { useChatMessageActions } from './useChatMessageActions'

const route = useRoute()
const router = useRouter()

const api = createNl2SqlApiClient({ timeout: 300000 })
const { topicApi, agentApi } = api

// ── State ────────────────────────────────────────────────────────────────────
const agents = ref([])
const settings = reactive({ providers: [], default_provider_id: '', default_model: '' })
const agentSelectValue = ref('')
const searchKeyword = ref('')
const autoScroll = ref(true)

// Shared NL2SQL conversation engine. The portal keeps its own routing, session
// audit facets, agent selector, feedback, copy, and scroll; the engine owns the
// send -> deliver -> stream -> reconcile -> detach/cancel lifecycle and the
// shared conversation refs.
const chat = useNl2SqlChat({
  api,
  getAgentId: () => agentSelectValue.value || '',
  topicTitleLength: 60,
  afterRun: () => loadTopics(),
  onTopicEnsured: (id) => { if (!isWidgetMode.value) replaceRouteTopic(id) },
  notifyError: (message) => ElMessage.error('请求失败: ' + message),
})
const {
  topics, topicId: activeTopicId, messages, inputText,
  providers, defaultProviderId, defaultModel, selectedProvider, selectedModel,
  availableModels, canSend, isBusy: isStreaming, activeTaskId,
  thinkingExpanded, toggleThinking,
  send: engineSend, cancel: engineCancel, detach,
  loadConfig,
} = chat
const { handleCopyMessage, toggleMessageFeedback } = useChatMessageActions({
  api,
  topicId: activeTopicId,
  cleanText: cleanTextForDisplay,
  notifyCopied: (message) => ElMessage.success(message),
  notifyError: (message) => ElMessage.error(message),
})

// Session-list source / filter / sort. Portal sessions stay editable; widget
// sessions are a read-only audit view served by the admin endpoint.
const sourceMode = ref('portal')        // 'portal' | 'widget'
const filterStatus = ref('')            // '' | 'running' | 'error' | 'suspended' | 'finished'
const filterUser = ref('')              // widget only: 'ext:<id>' | 'vis:<id>'
const sortOrder = ref('updated_desc')   // 'updated_desc' | 'created_desc' | 'title_asc'
const filterPopoverVisible = ref(false)
const isWidgetMode = computed(() => sourceMode.value === 'widget')
const hasActiveFilters = computed(() =>
  sourceMode.value !== 'portal' || filterStatus.value !== '' || sortOrder.value !== 'updated_desc' || filterUser.value !== ''
)

const currentAgentName = computed(() => {
  const currentId = agentSelectValue.value
  const found = agents.value.find((a) => a.agent_id === currentId)
  return found?.name || '智能数据助手'
})
const messagesScrollbarRef = ref(null)
const textareaRef = ref(null)
const targetMessageId = ref('')

// ── Computed ─────────────────────────────────────────────────────────────────
// Status filter values map to topicStatusKind(): '' (finished/none) is the
// terminal-success bucket, the rest mirror the badge kinds.
const STATUS_FILTER_KIND = { running: 'running', error: 'error', suspended: 'suspended', finished: '' }

const filteredTopics = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase()
  let list = kw
    ? topics.value.filter((t) => (t.title || '').toLowerCase().includes(kw))
    : topics.value.slice()

  if (filterStatus.value) {
    const wanted = STATUS_FILTER_KIND[filterStatus.value]
    list = list.filter((t) => topicStatusKind(t.current_task_status) === wanted)
  }

  const order = sortOrder.value
  return list.slice().sort((a, b) => {
    if (order === 'title_asc') {
      return String(a.title || '').localeCompare(String(b.title || ''), 'zh-CN')
    }
    const av = order === 'created_desc' ? a.created_at : (a.updated_at || a.created_at)
    const bv = order === 'created_desc' ? b.created_at : (b.updated_at || b.created_at)
    return new Date(bv || 0).getTime() - new Date(av || 0).getTime()
  })
})

// User filter options resolved server-side (admin facet) so the dropdown can
// search the full widget user set, not just users on the loaded session page.
const widgetUserOptions = ref([])
const widgetUsersLoading = ref(false)

function userOptionFromRow(row) {
  const id = String(row?.user_id || '').trim()
  const kind = row?.kind === 'ext' ? 'ext' : 'vis'
  return {
    value: kind + ':' + id,
    label: (kind === 'ext' ? '用户 ' : '访客 ') + id,
    count: Number(row?.topic_count || 0),
  }
}

async function fetchWidgetUsers(query = '') {
  if (!isWidgetMode.value) return
  widgetUsersLoading.value = true
  try {
    const data = await dataagentApi.listWidgetUsers({ keyword: String(query || '').trim(), limit: 100 })
    const options = (Array.isArray(data?.items) ? data.items : []).map(userOptionFromRow)
    // Keep the active selection visible even if it falls outside this result set.
    if (filterUser.value && !options.some((o) => o.value === filterUser.value)) {
      const prev = widgetUserOptions.value.find((o) => o.value === filterUser.value)
      if (prev) options.unshift(prev)
    }
    widgetUserOptions.value = options
  } catch {
    widgetUserOptions.value = []
  } finally {
    widgetUsersLoading.value = false
  }
}

function handleUserSelectVisible(visible) {
  if (visible && isWidgetMode.value) fetchWidgetUsers('')
}

const activeTopic = computed(() => topics.value.find((t) => t.topic_id === activeTopicId.value) || null)

// Session-list status badge: the active topic shows the spinner while streaming
// locally; any topic whose server status is waiting/running also shows it.
const isTopicWorking = (topic) =>
  (topic?.topic_id === activeTopicId.value && isStreaming.value) ||
  topicStatusKind(topic?.current_task_status) === 'running'
const topicBadgeKind = (topic) => topicStatusKind(topic?.current_task_status)

const agentSelectOptions = computed(() => agents.value.map((a) => ({ label: a.name, value: a.agent_id })))

// ── Time formatting ────────────────────────────────────────────────────────
function formatTime(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return ''
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}

function formatMessageTime(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return ''
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

// ── Auto-resize textarea ───────────────────────────────────────────────────
function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

// ── Scroll ─────────────────────────────────────────────────────────────────
function scrollToBottom(force = false) {
  if (!force && !autoScroll.value) return
  nextTick(() => {
    const sb = messagesScrollbarRef.value
    if (sb?.setScrollTop) {
      sb.setScrollTop(999999)
    }
  })
}

function handleScroll({ scrollTop, scrollHeight, clientHeight }) {
  autoScroll.value = scrollHeight - scrollTop - clientHeight < 60
}

function normalizeQueryValue(value) {
  const first = Array.isArray(value) ? value[0] : value
  return String(first || '').trim()
}

function routeTopicId() {
  return normalizeQueryValue(route.query.topic_id)
}

function routeMessageId() {
  return normalizeQueryValue(route.query.message_id)
}

function replaceRouteTopic(topicId, messageId = '') {
  const query = { ...route.query, tab: 'chat-v2' }
  const normalizedTopicId = normalizeQueryValue(topicId)
  const normalizedMessageId = normalizeQueryValue(messageId)

  if (normalizedTopicId) {
    query.topic_id = normalizedTopicId
  } else {
    delete query.topic_id
  }

  if (normalizedMessageId) {
    query.message_id = normalizedMessageId
  } else {
    delete query.message_id
  }

  const navigation = router.replace({
    path: route.path || '/intelligent-query',
    query,
  })
  if (navigation?.catch) {
    navigation.catch(() => {})
  }
}

function messageRootElement() {
  const scrollbar = messagesScrollbarRef.value
  return scrollbar?.$el || scrollbar?.wrapRef || null
}

function findMessageElement(messageId) {
  const normalizedMessageId = normalizeQueryValue(messageId)
  const root = messageRootElement()
  if (!root || !normalizedMessageId) return null
  return Array.from(root.querySelectorAll('[data-message-id]'))
    .find((el) => el.getAttribute('data-message-id') === normalizedMessageId) || null
}

function focusMessage(messageId) {
  const normalizedMessageId = normalizeQueryValue(messageId)
  if (!normalizedMessageId) {
    targetMessageId.value = ''
    scrollToBottom(true)
    return
  }

  targetMessageId.value = normalizedMessageId
  nextTick(() => {
    const el = findMessageElement(normalizedMessageId)
    if (el?.scrollIntoView) {
      el.scrollIntoView({ block: 'center', behavior: 'smooth' })
      return
    }
    targetMessageId.value = ''
    scrollToBottom(true)
  })
}

// ── Data loading ───────────────────────────────────────────────────────────
async function loadSettings() {
  try {
    // Use the runtime-config path: it returns only enabled providers and a
    // default already repaired to an enabled provider/model. Deriving the
    // default from admin settings instead would surface disabled providers
    // and a stale default pointing at a provider the user has not enabled.
    const config = await loadConfig()
    const enabledProviders = Array.isArray(config?.providers)
      ? config.providers.filter((p) => p?.enabled !== false && Array.isArray(p?.models) && p.models.length)
      : []
    settings.providers = enabledProviders
    settings.default_provider_id = defaultProviderId.value
    settings.default_model = defaultModel.value
  } catch {
    // non-fatal
  }
}

async function loadAgents() {
  try {
    const list = await agentApi.listAgents()
    const normalized = (Array.isArray(list) ? list : []).map((a) => ({
      agent_id: String(a?.agent_id || ''),
      name: String(a?.name || '默认助手'),
      is_default: Boolean(a?.is_default),
      preset_questions: Array.isArray(a?.preset_questions) ? a.preset_questions.filter(Boolean) : [],
    })).filter((a) => a.agent_id)
    agents.value = normalized.length
      ? normalized
      : [{ agent_id: 'agent_default', name: '默认助手', is_default: true }]
    const routeAgentId = String(route.query.agent_id || '').trim()
    if (routeAgentId && agents.value.some((a) => a.agent_id === routeAgentId)) {
      agentSelectValue.value = routeAgentId
    } else if (!agentSelectValue.value) {
      const def = agents.value.find((a) => a.is_default) || agents.value[0]
      agentSelectValue.value = def?.agent_id || ''
    }
  } catch {
    agents.value = [{ agent_id: 'agent_default', name: '默认助手', is_default: true }]
    agentSelectValue.value = 'agent_default'
  }
}

async function loadTopics() {
  if (sourceMode.value === 'widget') {
    await loadWidgetTopics()
    return
  }
  try {
    const params = { page: 1, page_size: 50 }
    if (route.query.agent_id) params.agent_id = route.query.agent_id
    const data = await topicApi.listTopics(params)
    topics.value = Array.isArray(data?.list) ? data.list : (Array.isArray(data) ? data : [])
    const requestedTopicId = routeTopicId()
    if (requestedTopicId) {
      await selectTopic(requestedTopicId, { messageId: routeMessageId() })
    } else if (topics.value.length && !activeTopicId.value) {
      await selectTopic(topics.value[0].topic_id)
    }
  } catch {
    // non-fatal
  }
}

// Read-only widget session list (admin endpoint). Unlike portal topics we never
// mirror the selection into the route, so a reload defaults back to portal.
async function loadWidgetTopics() {
  try {
    const params = { page: 1, page_size: 50 }
    // Assistant filter mirrors portal mode so the same agent selector narrows
    // both sources; the admin widget endpoint accepts agent_id server-side.
    if (route.query.agent_id) params.agent_id = route.query.agent_id
    // User filter is applied server-side so it stays accurate across pages.
    if (filterUser.value.startsWith('ext:')) params.external_user_id = filterUser.value.slice(4)
    else if (filterUser.value.startsWith('vis:')) params.visitor_id = filterUser.value.slice(4)
    const data = await dataagentApi.listWidgetTopics(params)
    topics.value = Array.isArray(data?.items) ? data.items : (Array.isArray(data) ? data : [])
    const stillListed = topics.value.some((t) => t.topic_id === activeTopicId.value)
    if (topics.value.length && (!activeTopicId.value || !stillListed)) {
      await selectTopic(topics.value[0].topic_id)
    }
  } catch {
    topics.value = []
  }
}

async function ensureTopicListed(topicId) {
  const normalizedTopicId = normalizeQueryValue(topicId)
  if (!normalizedTopicId || topics.value.some((topic) => String(topic?.topic_id || '') === normalizedTopicId)) return
  try {
    const topic = await topicApi.getTopic(normalizedTopicId)
    if (topic?.topic_id) {
      topics.value.unshift(topic)
    }
  } catch {
    // The message list can still load even when the topic summary lookup fails.
  }
}

async function selectTopic(topicId, options = {}) {
  const normalizedTopicId = normalizeQueryValue(topicId)
  if (!normalizedTopicId) return
  activeTopicId.value = normalizedTopicId
  if (!isWidgetMode.value) {
    await ensureTopicListed(normalizedTopicId)
  }
  try {
    const data = isWidgetMode.value
      ? await dataagentApi.getWidgetTopicMessages(normalizedTopicId, { page: 1, page_size: 500, order: 'asc' })
      : await topicApi.getTopicMessages(normalizedTopicId, { page: 1, page_size: 500, order: 'asc' })
    const list = Array.isArray(data?.items) ? data.items : (Array.isArray(data) ? data : [])
    messages.value = list.map(hydrateMessageFromApi)
    const messageId = normalizeQueryValue(options.messageId)
    if (messageId) {
      focusMessage(messageId)
    } else {
      targetMessageId.value = ''
      scrollToBottom(true)
    }
  } catch {
    messages.value = []
    targetMessageId.value = ''
  }
}


// ── Chart spec helpers ────────────────────────────────────────────────────
// Inline chart_spec written into the model's prose is stripped from display;
// charts must come from a real tool call (rendered below that tool block).
function cleanTextForDisplay(content) {
  return stripChartSpecsFromText(String(content || '')).trim()
}

// Split answer prose into ordered text/chart segments so an inline chart_spec
// (fenced, tagged, or raw JSON) renders as a real chart instead of leaking JSON.
function answerSegments(content) {
  return splitChartSpecText(String(content || ''))
}

// ── Suggestions ───────────────────────────────────────────────────────────
const DEFAULT_SUGGESTIONS = [
  '最近 30 天工作流发布次数趋势',
  '各数据层表数量对比',
  '各工作流发布操作类型占比',
  '有哪些失败的任务实例',
]

const suggestions = computed(() => {
  const agent = agents.value.find((a) => a.agent_id === agentSelectValue.value)
  const questions = Array.isArray(agent?.preset_questions) ? agent.preset_questions.filter(Boolean) : []
  return questions.length ? questions : DEFAULT_SUGGESTIONS
})

function handleSuggestion(text) {
  if (isStreaming.value || isWidgetMode.value) return
  inputText.value = text
  nextTick(() => handleSend())
}

// ── Source / filter ──────────────────────────────────────────────────────
async function handleSourceChange(mode) {
  if (mode === sourceMode.value) return
  if (isStreaming.value) detach()
  sourceMode.value = mode
  activeTopicId.value = ''
  messages.value = []
  targetMessageId.value = ''
  searchKeyword.value = ''
  filterStatus.value = ''
  filterUser.value = ''
  widgetUserOptions.value = []
  topics.value = []
  await loadTopics()
  if (isWidgetMode.value) fetchWidgetUsers('')
}

// Re-query the widget list server-side when the user filter changes so results
// reflect all matching sessions, not just those on the currently loaded page.
watch(filterUser, async () => {
  if (!isWidgetMode.value) return
  activeTopicId.value = ''
  messages.value = []
  await loadWidgetTopics()
})

function resetFilters() {
  if (sourceMode.value !== 'portal') {
    handleSourceChange('portal')
  }
  filterStatus.value = ''
  filterUser.value = ''
  sortOrder.value = 'updated_desc'
}

// ── Topic management ───────────────────────────────────────────────────────
async function handleNewTopic() {
  if (isWidgetMode.value) return
  // Leaving a running conversation detaches it (the backend task keeps running,
  // recoverable from history) instead of blocking, matching the widget.
  if (isStreaming.value) detach()
  activeTopicId.value = ''
  messages.value = []
  targetMessageId.value = ''
  searchKeyword.value = ''
  replaceRouteTopic('')
}

async function handleSelectTopic(topicId) {
  if (topicId === activeTopicId.value) {
    if (!isWidgetMode.value) {
      targetMessageId.value = ''
      replaceRouteTopic(topicId)
    }
    return
  }
  if (isStreaming.value) detach()
  await selectTopic(topicId)
  if (!isWidgetMode.value) replaceRouteTopic(topicId)
}

function handleAgentChange(agentId) {
  agentSelectValue.value = agentId
  const value = String(agentId || '').trim()
  if (String(route.query.agent_id || '').trim() === value) return
  const query = { ...route.query }
  if (value) {
    query.agent_id = value
  } else {
    delete query.agent_id
  }
  const navigation = router.replace({ path: route.path, query })
  if (navigation?.catch) {
    navigation.catch(() => {})
  }
}

function handleModelCommand(command) {
  const [providerId, model] = String(command || '').split('::')
  if (providerId && model) {
    selectedProvider.value = providerId
    selectedModel.value = model
  }
}

// ── Send message ───────────────────────────────────────────────────────────
// The send -> deliver -> stream -> reconcile lifecycle lives in the shared
// engine. Routing (onTopicEnsured), scroll (messages watcher), topic-list
// refresh (reloadTopicsAfterRun), and error toasts (notifyError) are wired
// through the engine options at setup.
async function handleSend() {
  if (isWidgetMode.value) return
  if (isStreaming.value || isUploading.value) return
  const ready = pendingAttachments.value.filter((a) => a.rel_path && !a.uploading)
  if (!inputText.value.trim() && !ready.length) return
  const attachments = ready.map((a) => ({ name: a.name, rel_path: a.rel_path }))
  pendingAttachments.value = []
  await engineSend({ attachments })
}

// Enter 发送，Shift + Enter 换行;输入法组合输入期间的回车用于确认候选词,不发送。
function onEnterKey(event) {
  if (!isPlainEnterSubmit(event)) return
  event.preventDefault()
  handleSend()
}

// Explicit stop: cancel the backend task (engine marks the topic suspended).
function handleCancel() {
  void engineCancel()
}

// ── Conversation files: composer upload + right-side artifact panel ──────────
const fileInputRef = ref(null)
const pendingAttachments = ref([])       // [{ name, rel_path, size, uploading, error }]
const isUploading = computed(() => pendingAttachments.value.some((a) => a.uploading))
const canSendV2 = computed(
  () => !isUploading.value && (canSend.value || pendingAttachments.value.some((a) => a.rel_path && !a.uploading)),
)

const ARTIFACTS_PREF_KEY = 'nl2sql.artifactsPanelOpen'
const artifactsPanelOpen = ref(readArtifactsPref())
const artifacts = ref([])
const artifactsLoading = ref(false)
const previewArtifact = ref(null)
const previewText = ref('')
const previewError = ref('')

function readArtifactsPref() {
  try { return localStorage.getItem(ARTIFACTS_PREF_KEY) === '1' } catch (_e) { return false }
}

function triggerFilePicker() {
  if (isStreaming.value) return
  fileInputRef.value?.click()
}

async function handleFilesSelected(event) {
  const files = Array.from(event?.target?.files || [])
  if (event?.target) event.target.value = ''
  if (!files.length) return
  let topicId = activeTopicId.value
  if (!topicId) {
    try {
      topicId = await chat.ensureTopic('新话题')
      if (!isWidgetMode.value) replaceRouteTopic(topicId)
    } catch (error) {
      ElMessage.error('创建话题失败: ' + (error?.message || error))
      return
    }
  }
  for (const file of files) {
    const entry = reactive({ name: file.name, rel_path: '', size: file.size, uploading: true, error: '' })
    pendingAttachments.value.push(entry)
    try {
      const meta = await topicApi.uploadFile(topicId, file)
      entry.name = meta.name
      entry.rel_path = meta.rel_path
      entry.size = meta.size
      entry.uploading = false
    } catch (error) {
      entry.uploading = false
      entry.error = String(error?.message || '上传失败')
      ElMessage.error(`上传 ${file.name} 失败: ${entry.error}`)
    }
  }
}

function removeAttachment(entry) {
  pendingAttachments.value = pendingAttachments.value.filter((a) => a !== entry)
}

function toggleArtifactsPanel() {
  artifactsPanelOpen.value = !artifactsPanelOpen.value
  try { localStorage.setItem(ARTIFACTS_PREF_KEY, artifactsPanelOpen.value ? '1' : '0') } catch (_e) { /* ignore */ }
  if (artifactsPanelOpen.value) refreshArtifacts()
}

async function refreshArtifacts() {
  const topicId = activeTopicId.value
  if (!topicId) { artifacts.value = []; return }
  artifactsLoading.value = true
  try {
    const res = await topicApi.listFiles(topicId)
    artifacts.value = Array.isArray(res?.files) ? res.files : []
  } catch (_error) {
    // keep the previous list; listing is best-effort
  } finally {
    artifactsLoading.value = false
  }
}

function artifactDownloadUrl(file) {
  return topicApi.fileUrl(activeTopicId.value, file.rel_path, { download: true })
}
function artifactInlineUrl(file) {
  return topicApi.fileUrl(activeTopicId.value, file.rel_path)
}
function isHtmlArtifact(file) {
  return /text\/html/.test(file?.content_type || '') || /\.html?$/i.test(file?.name || '')
}
function isImageArtifact(file) {
  return /^image\//.test(file?.content_type || '') || /\.(png|jpe?g|gif|svg|webp)$/i.test(file?.name || '')
}
function isTextArtifact(file) {
  return /^text\/|application\/json|csv/.test(file?.content_type || '') || /\.(txt|csv|json|md|log|yaml|yml)$/i.test(file?.name || '')
}

async function openArtifact(file) {
  previewArtifact.value = file
  previewText.value = ''
  previewError.value = ''
  if (isHtmlArtifact(file) || isTextArtifact(file)) {
    try {
      previewText.value = await topicApi.fetchFileText(activeTopicId.value, file.rel_path)
    } catch (error) {
      previewError.value = String(error?.message || '加载失败')
    }
  }
}
function closeArtifactPreview() {
  previewArtifact.value = null
  previewText.value = ''
  previewError.value = ''
}

function formatBytes(size) {
  const n = Number(size) || 0
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

// Refresh artifacts when a run finishes (new files may have been generated) and
// reset the panel when switching conversations.
watch(isStreaming, (now, prev) => {
  if (prev && !now && artifactsPanelOpen.value) refreshArtifacts()
})
watch(activeTopicId, () => {
  closeArtifactPreview()
  if (artifactsPanelOpen.value) refreshArtifacts()
  else artifacts.value = []
})

// Keep the view pinned to the latest content as the engine streams (the engine
// triggers messages reactively); reloads / focus still force-scroll explicitly.
watch(messages, () => scrollToBottom(), { deep: true, flush: 'post' })

// The engine clears inputText on send; resize the composer back down.
watch(inputText, (value) => {
  if (!value) nextTick(() => autoResize())
})

// ── Lifecycle ─────────────────────────────────────────────────────────────
onMounted(async () => {
  await Promise.all([loadSettings(), loadAgents()])
  await loadTopics()
})

watch(() => route.query.agent_id, async () => {
  // Re-query both sources: widget mode also honors the assistant filter.
  activeTopicId.value = ''
  messages.value = []
  await loadTopics()
})

watch(
  () => [route.query.topic_id, route.query.message_id],
  async ([topicId, messageId]) => {
    // Widget sessions are not mirrored into the route; ignore route-driven
    // topic selection while viewing them to avoid loading a widget id as portal.
    if (isWidgetMode.value) return
    const normalizedTopicId = normalizeQueryValue(topicId)
    const normalizedMessageId = normalizeQueryValue(messageId)
    if (!normalizedTopicId) {
      targetMessageId.value = ''
      return
    }
    if (normalizedTopicId !== activeTopicId.value) {
      if (isStreaming.value) detach()
      await selectTopic(normalizedTopicId, { messageId: normalizedMessageId })
      return
    }
    if (normalizedMessageId) {
      focusMessage(normalizedMessageId)
    } else {
      targetMessageId.value = ''
    }
  }
)

onBeforeUnmount(() => {
  detach()
})
</script>

<style scoped>
/* ── Root layout ─────────────────────────────────────────────────────────── */
.v2-workbench {
  height: 100%;
  min-height: 0;
  display: grid;
  grid-template-columns: 260px 1fr;
  border: 1px solid #E5EAF1;
  border-radius: 18px;
  overflow: hidden;
  background: #F4F5F7;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.035);
  font-family: 'IBM Plex Sans', 'PingFang SC', 'Hiragino Sans GB', sans-serif;
}

@media (min-width: 1280px) {
  .v2-workbench { grid-template-columns: 300px 1fr; }
  .v2-workbench.artifacts-open { grid-template-columns: 300px 1fr 360px; }
}

.v2-workbench.artifacts-open { grid-template-columns: 260px 1fr 340px; }

/* ── Conversation artifacts panel ────────────────────────────────────────── */
.v2-artifacts-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-left: 1px solid #E5EAF1;
  background: #FBFCFD;
}
.v2-artifacts-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid #EDF0F4;
}
.v2-artifacts-title { font-size: 13px; font-weight: 600; color: #1F2937; }
.v2-artifacts-actions { display: flex; gap: 4px; }
.v2-artifacts-actions button {
  display: inline-flex; align-items: center; justify-content: center;
  width: 26px; height: 26px; border: none; border-radius: 7px;
  background: transparent; color: #6B7280; cursor: pointer;
}
.v2-artifacts-actions button:hover { background: #EEF1F5; color: #111827; }
.v2-artifacts-scroll { flex: 1; min-height: 0; }
.v2-artifacts-empty { padding: 24px 14px; color: #9AA4B2; font-size: 13px; text-align: center; }
.v2-artifact-item {
  display: flex; align-items: center; gap: 8px; width: 100%;
  padding: 10px 14px; border: none; border-bottom: 1px solid #F1F3F6;
  background: transparent; cursor: pointer; text-align: left;
}
.v2-artifact-item:hover { background: #F2F5F9; }
.v2-artifact-name {
  flex: 1; min-width: 0; font-size: 13px; color: #1F2937;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.v2-artifact-meta { display: flex; align-items: center; gap: 6px; }
.v2-artifact-tag {
  font-size: 11px; padding: 1px 6px; border-radius: 6px;
  background: #EEF2FF; color: #4F46E5;
}
.v2-artifact-tag.input { background: #ECFDF3; color: #047857; }
.v2-artifact-size { font-size: 11px; color: #9AA4B2; }
.v2-artifact-row-dl {
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 24px; border-radius: 6px; color: #6B7280;
}
.v2-artifact-row-dl:hover { background: #E6EAF0; color: #111827; }
.v2-artifact-preview { display: flex; flex-direction: column; min-height: 0; flex: 1; }
.v2-artifact-preview-head {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 14px; border-bottom: 1px solid #EDF0F4;
}
.v2-artifact-back { border: none; background: transparent; color: #4F46E5; cursor: pointer; font-size: 13px; }
.v2-artifact-preview-name {
  flex: 1; min-width: 0; font-size: 13px; color: #1F2937;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.v2-artifact-dl-link { font-size: 13px; color: #4F46E5; text-decoration: none; }
.v2-artifact-preview-body { flex: 1; min-height: 0; overflow: auto; background: #fff; }
.v2-artifact-frame { width: 100%; height: 100%; border: none; background: #fff; }
.v2-artifact-img { max-width: 100%; display: block; margin: 0 auto; }
.v2-artifact-text {
  margin: 0; padding: 12px 14px; font-size: 12px; line-height: 1.6;
  white-space: pre-wrap; word-break: break-word;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

/* ── Composer attachments ────────────────────────────────────────────────── */
.v2-attach-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.v2-attach-chip {
  display: inline-flex; align-items: center; gap: 5px;
  max-width: 220px; padding: 4px 8px; border-radius: 8px;
  background: #EEF2F7; color: #374151; font-size: 12px;
}
.v2-attach-chip.is-uploading { opacity: 0.75; }
.v2-attach-chip.is-error { background: #FEF2F2; color: #B91C1C; }
.v2-attach-name { max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.v2-attach-state { font-size: 11px; color: #6B7280; }
.v2-attach-remove { border: none; background: transparent; color: #6B7280; cursor: pointer; font-size: 14px; line-height: 1; padding: 0 2px; }
.v2-attach-remove:hover { color: #111827; }
.v2-file-input { display: none; }
.v2-attach-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 30px; height: 30px; flex: none; border: none; border-radius: 8px;
  background: transparent; color: #6B7280; cursor: pointer;
}
.v2-attach-btn:hover:not(:disabled) { background: #EEF1F5; color: #111827; }
.v2-attach-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* ── Artifacts toggle (top bar) ──────────────────────────────────────────── */
.v2-artifacts-toggle {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 5px 10px; border: 1px solid #E5EAF1; border-radius: 8px;
  background: #fff; color: #4B5563; font-size: 12px; cursor: pointer;
}
.v2-artifacts-toggle:hover { background: #F4F6F9; }
.v2-artifacts-toggle.active { border-color: #C7D2FE; background: #EEF2FF; color: #4338CA; }

/* ── Sidebar ─────────────────────────────────────────────────────────────── */
.v2-sidebar {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 16px 12px;
  background: #ffffff;
  border-right: 1px solid #E5EAF1;
}

.v2-sidebar-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 16px;
}

.v2-agent-select {
  flex: 1;
  min-width: 0;
}

.v2-agent-icon {
  width: 16px;
  height: 16px;
}

.v2-btn-new {
  flex-shrink: 0;
  padding: 6px 12px;
  border: none;
  border-radius: 6px;
  background: var(--odw-primary);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  transition: background var(--odw-transition);
}

.v2-btn-new:hover { background: var(--odw-primary-light); }

/* ── Source tabs + filter ────────────────────────────────────────────────── */
.v2-sidebar-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 8px 12px;
}

.v2-sidebar-toolbar .v2-search-input {
  flex: 1;
  min-width: 0;
  box-sizing: border-box;
  padding: 6px 10px;
  border: 1px solid #dbe3ef;
  border-radius: 6px;
  font-size: 13px;
  outline: none;
  background: #f9fafc;
}

.v2-sidebar-toolbar .v2-search-input:focus { border-color: var(--odw-primary); }

.v2-source-tabs {
  display: inline-flex;
  padding: 2px;
  border-radius: 8px;
  background: #f0f3f8;
}

.v2-source-tab {
  padding: 5px 14px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #606878;
  font-size: 13px;
  cursor: pointer;
  transition: background var(--odw-transition), color var(--odw-transition);
}

.v2-source-tab.active {
  background: #ffffff;
  color: var(--odw-primary);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08);
}

.v2-filter-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 1px solid #dbe3ef;
  border-radius: 8px;
  background: #ffffff;
  color: #8a96a6;
  cursor: pointer;
  transition: border-color var(--odw-transition), color var(--odw-transition);
}

.v2-filter-btn:hover { border-color: var(--odw-primary); color: var(--odw-primary); }
.v2-filter-btn.active { border-color: var(--odw-primary); color: var(--odw-primary); }

.v2-filter-panel { display: flex; flex-direction: column; gap: 14px; }
.v2-filter-group { display: flex; flex-direction: column; gap: 8px; }
.v2-filter-label { font-size: 12px; font-weight: 600; color: #8C8C8C; }
.v2-filter-radios { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; }
.v2-filter-radios :deep(.el-radio) { margin-right: 0; height: 26px; }
.v2-filter-select { width: 100%; }
.v2-user-opt-label { margin-right: 8px; }
.v2-user-opt-count { float: right; color: var(--el-text-color-secondary, #909399); font-size: 12px; }

.v2-filter-actions { display: flex; justify-content: flex-end; border-top: 1px solid #eef1f5; padding-top: 10px; }

.v2-filter-reset {
  padding: 4px 12px;
  border: 1px solid #dbe3ef;
  border-radius: 6px;
  background: #ffffff;
  color: #606878;
  font-size: 12px;
  cursor: pointer;
  transition: border-color var(--odw-transition), color var(--odw-transition);
}

.v2-filter-reset:hover { border-color: var(--odw-primary); color: var(--odw-primary); }

.v2-session-scroll { flex: 1; min-height: 0; }

.v2-session-list { display: flex; flex-direction: column; gap: 2px; padding: 0 8px 8px; }

.v2-session-item {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background var(--odw-transition);
}

.v2-session-item:hover { background: #f0f3f8; }
.v2-session-item.active { background: #e8eef8; }

.v2-session-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: #1F1F1F;
}

.v2-session-meta { flex-shrink: 0; font-size: 11px; color: #8C8C8C; }
.v2-session-dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; margin-right: 5px; vertical-align: middle; }
.v2-session-dot.is-error { background: #F56C6C; }
.v2-session-dot.is-suspended { background: #A0AABF; }

.v2-session-loading {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.v2-session-spinner {
  width: 14px;
  height: 14px;
  color: var(--odw-primary);
  animation: v2-spin 1s linear infinite;
}

.v2-session-spinner-track {
  stroke: rgba(0, 0, 0, 0.05);
}

.v2-session-spinner-head {
  stroke: currentColor;
}

@keyframes v2-spin {
  to { transform: rotate(360deg); }
}

.v2-empty-sessions { padding: 16px 10px; font-size: 13px; color: #8C8C8C; text-align: center; }

/* ── Main ────────────────────────────────────────────────────────────────── */
.v2-main {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #ffffff;
  position: relative;
}

.v2-main-top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 14px 24px 12px;
  border-bottom: 1px solid #eef1f5;
  flex-shrink: 0;
}
.v2-main-top-bar .v2-topic-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.v2-topic-title {
  flex: 1;
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #162131;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── Messages ────────────────────────────────────────────────────────────── */
.v2-messages { flex: 1; min-height: 0; }

.v2-messages-inner {
  padding-top: 24px;
  padding-bottom: 180px;
  padding-inline: clamp(40px, 5%, 64px);
  max-width: 1280px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* ── Landing (new chat) ──────────────────────────────────────────────────── */
.v2-landing-greeting {
  font-size: 26px;
  font-weight: 600;
  color: #1e293b;
  text-align: center;
  letter-spacing: -0.2px;
  margin-bottom: 32px;
}

.v2-landing-suggestions-title {
  text-align: center;
  color: #64748b;
  font-size: 13px;
  margin-top: 24px;
  margin-bottom: 8px;
}

.v2-landing-suggestions {
  width: 100%;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.v2-suggestion-card {
  text-align: left;
  padding: 14px 16px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #ffffff;
  color: #334155;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.55;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease, color 0.15s ease;
}

.v2-suggestion-card:hover:not(:disabled) {
  border-color: var(--odw-primary);
  background: color-mix(in srgb, var(--odw-primary) 4%, #fff);
  color: #162131;
}

.v2-suggestion-card:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.v2-config-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 0 0 24px;
  gap: 8px;
}

.v2-config-empty-title { font-size: 15px; font-weight: 600; color: #334155; }
.v2-config-empty-text { font-size: 13px; color: #8C8C8C; }

/* ── Message rows ────────────────────────────────────────────────────────── */
.v2-msg-row { display: flex; }

.v2-msg-row.is-target-message {
  border-radius: 12px;
  background: rgba(32, 80, 166, 0.06);
  box-shadow: 0 0 0 1px rgba(32, 80, 166, 0.16);
  padding: 8px;
  margin: -8px;
}

.v2-msg-user { justify-content: flex-end; }

.v2-user-shell { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; max-width: 72%; }

.v2-user-bubble {
  padding: 10px 16px;
  border-radius: 16px 16px 4px 16px;
  background: linear-gradient(135deg, var(--odw-primary) 0%, var(--odw-primary-dark) 100%);
  color: #fff;
  font-size: 14px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.v2-msg-assistant { justify-content: flex-start; }

.v2-assistant-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 88%;
}

.v2-msg-footer {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 2px 0;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.16s ease;
}

.v2-msg-row:hover .v2-msg-footer,
.v2-msg-footer:focus-within {
  opacity: 1;
  pointer-events: auto;
}
.v2-msg-time { font-size: 11px; color: #A0AABF; }

/* ── Message tools ───────────────────────────────────────────────────────── */
.v2-message-tool {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: #A0AABF;
  cursor: pointer;
  transition: background-color 0.16s ease, border-color 0.16s ease, color 0.16s ease;
}

.v2-message-tool svg {
  width: 13px;
  height: 13px;
  stroke-width: 1.8;
}

.v2-message-tool:hover, .v2-message-tool.active {
  border-color: #D9E2F2;
  background: #ffffff;
  color: var(--odw-primary);
}

.v2-message-tool.v2-message-feedback-dislike.active {
  color: #e63946;
}

/* ── Thinking (深度思考) panel ────────────────────────────────────────────── */
.v2-process-panel {
  border: 1px solid #dbe3ef;
  border-radius: 10px;
  overflow: hidden;
  background: #f9fafc;
}

.v2-process-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px 14px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  color: #334155;
  font-weight: 500;
  text-align: left;
  transition: background var(--odw-transition);
}

.v2-process-summary:hover { background: #eff1f5; }

.v2-process-label {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  font-weight: 600;
}

.v2-badge-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--odw-primary);
  animation: v2-pulse 1.4s ease-in-out infinite;
}

@keyframes v2-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(0.85); }
}

.v2-process-preview {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: #8C8C8C;
  font-weight: 400;
}

.v2-chevron {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  color: #8C8C8C;
  transition: transform var(--odw-transition);
}

.v2-chevron.expanded { transform: rotate(180deg); }

.v2-process-content { max-height: min(360px, 50vh); }

.v2-process-thought {
  padding: 12px 16px;
  font-size: 13px;
  line-height: 1.65;
  color: #595959;
}

.v2-process-thought :deep(p) { margin: 0 0 8px; }
.v2-process-thought :deep(p:last-child) { margin: 0; }

/* ── Tool output row ─────────────────────────────────────────────────────── */
.v2-tool-row { border-radius: 10px; overflow: hidden; }

/* ── Text block ──────────────────────────────────────────────────────────── */
.v2-text-block {
  font-size: 14px;
  line-height: 1.65;
  color: #162131;
}

.v2-text-block :deep(p) { margin: 0 0 10px; }
.v2-text-block :deep(p:last-child) { margin: 0; }
.v2-text-block :deep(pre) {
  background: #f3f7fb;
  border-radius: 8px;
  padding: 12px 16px;
  overflow-x: auto;
  font-size: 13px;
}
.v2-text-block :deep(code) {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  padding: 2px 6px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--odw-primary) 8%, transparent);
  color: var(--odw-primary);
}
.v2-text-block :deep(table) { border-collapse: collapse; width: 100%; margin: 10px 0; }
.v2-text-block :deep(th), .v2-text-block :deep(td) { border: 1px solid #dbe3ef; padding: 6px 12px; font-size: 13px; }
.v2-text-block :deep(th) { background: #f4f7fb; font-weight: 600; }

/* ── Error card ──────────────────────────────────────────────────────────── */
.v2-error-card {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 14px;
  border-radius: 8px;
  background: rgba(254, 242, 242, 0.9);
  border: 1px solid rgba(252, 165, 165, 0.5);
  font-size: 13px;
  color: #7f1d1d;
}

.v2-error-label {
  flex-shrink: 0;
  font-weight: 600;
  color: #b91c1c;
}

/* ── Cursor ──────────────────────────────────────────────────────────────── */
.v2-cursor {
  display: inline-block;
  color: var(--odw-primary);
  font-weight: 700;
  animation: v2-blink 1s step-end infinite;
  margin-left: 2px;
}

@keyframes v2-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

/* ── Read-only banner (widget sessions) ──────────────────────────────────── */
.v2-readonly-bar {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 100%;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 14px 24px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0) 0%, #ffffff 45%);
  color: #8a96a6;
  font-size: 13px;
}

/* ── Composer ────────────────────────────────────────────────────────────── */
.v2-composer-bar {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 100%;
  z-index: 10;
  padding-top: 32px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0) 0%, rgba(255, 255, 255, 0.85) 30%, #ffffff 50%);
  transition: background 0.3s ease;
  border-top: none;
}

.v2-composer-bar.is-landing {
  top: 0;
  padding-top: 0;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
}

.v2-composer-wrap {
  max-width: 1280px;
  margin: 0 auto;
  width: 100%;
  box-sizing: border-box;
  padding-block: 10px 12px;
  padding-inline: clamp(40px, 5%, 64px);
  transition: max-width 0.3s ease;
}

.v2-composer-bar.is-landing .v2-composer-wrap {
  max-width: 860px;
}

.v2-composer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 10px 10px 18px;
  border: 1px solid #dde2ea;
  border-radius: 16px;
  background: #ffffff;
  transition: border-color var(--odw-transition), box-shadow var(--odw-transition);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.04);
}

.v2-composer:focus-within {
  border-color: #b0bbcc;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.08);
}

.v2-textarea {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  resize: none;
  font-size: 14px;
  line-height: 1.55;
  color: #162131;
  font-family: inherit;
  min-height: 22px;
  max-height: 160px;
  overflow-y: auto;
}

.v2-textarea::placeholder { color: #A0AABF; }

.v2-composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  padding-inline: 4px;
}

.v2-composer-toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.v2-composer-hint {
  color: #9aa5b1;
  font-size: 11px;
  line-height: 1.4;
  white-space: nowrap;
}

.v2-composer-toolbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.v2-model-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #8a96a6;
  font-size: 12px;
  cursor: pointer;
  transition: color var(--odw-transition), background var(--odw-transition);
  max-width: 160px;
}

.v2-model-btn:hover { color: #4a5568; background: #eef1f5; }

.v2-model-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.v2-send-btn {
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 8px;
  background: #e8eaed;
  color: #606878;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: background var(--odw-transition), color var(--odw-transition), transform var(--odw-transition);
}

.v2-send-btn:not(:disabled):not(.v2-cancel-btn) {
  background: linear-gradient(135deg, var(--odw-primary) 0%, var(--odw-primary-dark) 100%);
  color: #fff;
}

.v2-send-btn:disabled { opacity: 0.4; cursor: default; }
.v2-send-btn:not(:disabled):hover { transform: scale(1.06); }
.v2-send-btn:active { transform: scale(0.94); }

.v2-cancel-btn {
  background: linear-gradient(135deg, #7f1d1d 0%, #b91c1c 100%) !important;
  color: #fff !important;
}

/* ── Typing indicator ────────────────────────────────────────────────────── */
.v2-typing-indicator {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 8px 2px;
}

.v2-typing-indicator span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--odw-primary);
  opacity: 0.35;
  animation: v2-typing 1.2s ease-in-out infinite;
}

.v2-typing-indicator span:nth-child(2) { animation-delay: 0.2s; }
.v2-typing-indicator span:nth-child(3) { animation-delay: 0.4s; }

@keyframes v2-typing {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.35; }
  30% { transform: translateY(-5px); opacity: 1; }
}

/* ── Responsive ──────────────────────────────────────────────────────────── */
@media (max-width: 960px) {
  .v2-workbench { grid-template-columns: 1fr; }
  .v2-sidebar { display: none; }
}

@media (max-width: 640px) {
  .v2-messages-inner { padding-top: 16px; padding-bottom: 160px; padding-inline: 16px; }
  .v2-composer-wrap { padding-block: 10px 14px; padding-inline: 16px; }
  .v2-user-shell { max-width: 90%; }
  .v2-assistant-body { max-width: 100%; }
  .v2-landing-greeting { font-size: 18px; }
  .v2-landing-suggestions { grid-template-columns: 1fr; }
}
</style>
