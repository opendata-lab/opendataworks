<template>
  <section class="agent-workbench">
    <main class="workspace-main">
      <header class="workspace-head">
        <div class="head-main">
          <button
            class="back icon-button"
            aria-label="返回智能体列表"
            @click="goBack"
          >
            <Icon name="arrow-left" />
          </button>
          <div class="agent-avatar"><Icon name="spark" /></div>
          <div class="title-row">
            <h1>{{ form.name || "智能体" }}</h1>
            <span
              class="badge"
              :class="dirty || draft.has_changes ? 'amber' : 'green'"
              >{{
                !canManage
                  ? "只读"
                  : dirty || draft.has_changes
                    ? "未发布修改"
                    : "已发布"
              }}</span
            >
          </div>
        </div>
        <div class="head-actions">
          <template v-if="canManage">
            <button
              class="button secondary"
              :disabled="busy || loading || !!loadError"
              @click="handleSave()"
            >
              <Icon name="save" />{{ saving ? "保存中…" : "保存草稿" }}
            </button>
            <button
              class="button primary"
              :disabled="!canPublish || busy || running"
              @click="openPublish"
            >
              <Icon name="publish" />发布<span class="version-mini"
                >v{{ nextVersion }}</span
              >
            </button>
          </template>
          <button
            v-else
            class="button primary"
            :disabled="loading || !!loadError"
            @click="openChat"
          >
            <Icon name="chat" />开启对话
          </button>
        </div>
      </header>
      <div v-if="loading" class="load-state" role="status">正在加载智能体…</div>
      <div v-else-if="loadError" class="load-state" role="alert">
        <p>{{ loadError }}</p>
        <button class="button secondary" @click="loadDetail">重新加载</button>
      </div>
      <template v-else>
        <div class="workbench">
          <section class="panel prompt-panel" aria-labelledby="prompt-title">
            <header class="panel-head">
              <div>
                <Icon name="file" />
                <h2 id="prompt-title">提示词</h2>
              </div>
            </header>
            <div class="prompt-body">
              <details class="builtin-section" open>
                <summary>
                  <span><Icon name="lock" />内置提示词</span
                  ><span class="readonly-label"
                    >只读<Icon name="chevron" class="small"
                  /></span>
                </summary>
                <pre
                  class="builtin-content"
                  tabindex="0"
                  aria-label="内置提示词，只读"
                  >{{ builtinPrompt }}</pre>
              </details>
              <div class="custom-section">
                <div class="section-title">
                  <h3>自定义提示词</h3>
                  <span class="badge" :class="canManage ? 'blue' : 'neutral'">{{
                    canManage ? "可编辑" : "只读"
                  }}</span>
                </div>
                <div class="prompt-editor">
                  <label for="custom-prompt" class="sr-only">自定义提示词</label
                  ><textarea
                    id="custom-prompt"
                    v-model="form.system_prompt"
                    :readonly="!canManage || publishing"
                    spellcheck="false"
                    placeholder="设置智能体角色、工作方式和表达偏好…"
                  ></textarea>
                  <div class="editor-foot">
                    {{ form.system_prompt.length }} 字
                  </div>
                </div>
              </div>
            </div>
          </section>
          <section class="panel config-panel" aria-labelledby="config-title">
            <header class="panel-head">
              <div>
                <Icon name="settings" />
                <h2 id="config-title">智能体配置</h2>
              </div>
            </header>
            <fieldset class="config-scroll" :disabled="!canManage">
              <section class="config-section">
                <h3>基础信息</h3>
                <label class="field-label" for="agent-name">智能体名称</label
                ><input
                  id="agent-name"
                  v-model="form.name"
                  maxlength="128"
                /><label class="field-label" for="description">描述</label
                ><textarea
                  id="description"
                  v-model="form.description"
                  rows="2"
                ></textarea>
              </section>
              <section
                v-for="kind in ['skills', 'mcp']"
                :key="kind"
                class="config-section"
              >
                <div class="section-title">
                  <h3>
                    <Icon :name="kind === 'skills' ? 'layers' : 'plug'" />{{
                      kind === "skills" ? "Skills" : "MCP 服务"
                    }}<span class="count">{{
                      selectedResources(kind).length
                    }}</span>
                  </h3>
                  <button
                    v-if="canManage"
                    type="button"
                    class="text-button"
                    :aria-label="
                      kind === 'skills' ? '配置 Skills' : '配置 MCP 服务'
                    "
                    :disabled="publishing"
                    :aria-expanded="activeResourcePicker === kind"
                    @click="
                      activeResourcePicker =
                        activeResourcePicker === kind ? '' : kind
                    "
                  >
                    <Icon name="plus" class="small" />配置
                  </button>
                </div>
                <div class="resource-list">
                  <div
                    v-for="item in selectedResources(kind)"
                    :key="item.id"
                    class="resource-row"
                  >
                    <div
                      class="resource-icon"
                      :class="kind === 'skills' ? 'purple' : 'blue'"
                    >
                      <Icon :name="kind === 'skills' ? 'layers' : 'plug'" />
                    </div>
                    <div class="resource-copy">
                      <strong>{{ item.name }}</strong>
                      <p v-if="item.description">{{ item.description }}</p>
                    </div>
                    <button
                      v-if="canManage"
                      class="icon-button"
                      :aria-label="`移除 ${item.name}`"
                      @click="removeResource(kind, item.id)"
                    >
                      <Icon name="x" class="small" />
                    </button>
                  </div>
                  <p
                    v-if="!selectedResources(kind).length"
                    class="resource-empty"
                  >
                    尚未配置
                  </p>
                </div>
                <div
                  v-if="canManage && activeResourcePicker === kind"
                  class="resource-add-row"
                >
                  <el-select
                    class="resource-add"
                    :model-value="null"
                    filterable
                    :aria-label="
                      kind === 'skills' ? '添加 Skill' : '添加 MCP 服务'
                    "
                    :placeholder="
                      availableResources(kind).length
                        ? kind === 'skills'
                          ? '选择 Skill'
                          : '选择 MCP 服务'
                        : '暂无可添加项'
                    "
                    :disabled="publishing || !availableResources(kind).length"
                    @change="addResource(kind, $event)"
                  >
                    <template #prefix
                      ><Icon name="plus" class="small"
                    /></template>
                    <el-option
                      v-for="item in availableResources(kind)"
                      :key="item.id"
                      :value="item.id"
                      :label="item.name"
                    />
                  </el-select>
                  <button
                    type="button"
                    class="icon-button"
                    :aria-label="
                      kind === 'skills' ? '关闭 Skills 选择' : '关闭 MCP 选择'
                    "
                    @click="activeResourcePicker = ''"
                  >
                    <Icon name="x" class="small" />
                  </button>
                </div>
              </section>
              <section class="config-section">
                <div class="section-title">
                  <h3>
                    <Icon name="code" />工具<span class="count">{{
                      form.allowed_tools.length
                    }}</span>
                  </h3>
                </div>
                <div class="tools">
                  <button
                    v-for="tool in visibleTools"
                    :key="tool"
                    class="tool"
                    :class="{ off: !form.allowed_tools.includes(tool) }"
                    :aria-pressed="form.allowed_tools.includes(tool)"
                    @click="toggleTool(tool)"
                  >
                    <Icon name="check" />{{ tool }}
                  </button>
                </div>
              </section>
              <details class="config-section advanced-details">
                <summary>
                  <h3><Icon name="database" />数据与权限</h3>
                  <span class="detail-value"
                    >{{
                      scopeSelection.length
                        ? `${scopeSelection.length} 个 Schema`
                        : "默认范围"
                    }}<Icon name="chevron" class="small"
                  /></span>
                </summary>
                <label class="field-label" for="data-scope">数据范围</label
                ><el-select
                  id="data-scope"
                  v-model="scopeSelection"
                  multiple
                  filterable
                  collapse-tags
                  collapse-tags-tooltip
                  :disabled="!canManage || publishing"
                  placeholder="默认数据范围"
                  style="width: 100%"
                  ><el-option
                    v-for="(scope, key) in scopeOptionByKey"
                    :key="key"
                    :value="key"
                    :label="scopeLabel(scope)"
                /></el-select>
                <template v-if="canManage"
                  ><label class="field-label" for="visibility">可见范围</label
                  ><select id="visibility" v-model="form.visibility.mode">
                    <option value="all">全部用户</option>
                    <option value="authenticated">仅登录用户</option>
                    <option value="selected">指定用户</option>
                  </select>
                  <template v-if="form.visibility.mode === 'selected'"
                    ><label class="field-label" for="visible-users"
                      >允许访问的用户</label
                    ><el-select
                      id="visible-users"
                      v-model="form.visibility.allowed_users"
                      multiple
                      filterable
                      remote
                      allow-create
                      default-first-option
                      :remote-method="searchAuthUsers"
                      :loading="authUserLoading"
                      placeholder="搜索或输入用户 ID"
                      style="width: 100%"
                      ><el-option
                        v-for="user in authUserOptions"
                        :key="user.user_id"
                        :value="user.user_id"
                        :label="authUserLabel(user)" /></el-select
                  ></template>
                </template>
              </details>
              <details class="config-section advanced-details">
                <summary>
                  <h3><Icon name="chat" />预设问题</h3>
                  <Icon name="chevron" class="small" />
                </summary>
                <template
                  v-for="(_, index) in form.preset_questions"
                  :key="index"
                  ><label class="field-label" :for="`preset-${index}`"
                    >问题 {{ index + 1 }}</label
                  ><input
                    :id="`preset-${index}`"
                    v-model="form.preset_questions[index]"
                    maxlength="200"
                    placeholder="添加预设问题（可选）"
                /></template>
              </details>
              <details class="config-section advanced-details last-section">
                <summary>
                  <h3><Icon name="settings" />高级设置</h3>
                  <Icon name="chevron" class="small" />
                </summary>
                <label class="field-label" for="max-turns"
                  >会话轮次上限（0 为跟随默认）</label
                ><input
                  id="max-turns"
                  v-model.number="form.max_turns"
                  type="number"
                  min="0"
                  max="200"
                /><template v-if="canManage"
                  ><label class="field-label" for="env-vars"
                    >环境变量 JSON</label
                  ><textarea
                    id="env-vars"
                    v-model="envVarsText"
                    class="mono"
                    rows="3"
                    spellcheck="false"
                  ></textarea>
                </template>
              </details>
            </fieldset>
          </section>
          <section class="panel preview-panel" aria-labelledby="preview-title" :style="{ '--preview-composer-height': `${previewComposerHeight}px` }">
            <header class="panel-head">
              <div>
                <Icon name="chat" />
                <h2 id="preview-title">预览与调试</h2>
              </div>
              <button
                v-if="canManage"
                class="icon-button"
                :disabled="running || sending || clearing || !previewTopicId"
                aria-label="清空调试对话"
                @click="resetPreview"
              >
                <Icon name="refresh" />
              </button>
            </header>
            <div class="preview-tabs" role="tablist">
              <button
                v-for="tab in [
                  { key: 'preview', label: '对话预览' },
                  { key: 'debug', label: '运行调试' },
                ]"
                :key="tab.key"
                class="tab"
                :class="{ active: previewTab === tab.key }"
                role="tab"
                :aria-selected="previewTab === tab.key"
                @click="previewTab = tab.key"
              >
                {{ tab.label }}</button
              ><span class="run-badge" :class="runBadgeClass">{{
                runLabel
              }}</span>
            </div>
            <div class="preview-viewport">
              <dataagent-conversation
                v-if="canManage"
                :key="conversationKey"
                ref="conversationRef"
                class="preview-conversation conversation-surface"
                :class="{ 'debug-hidden': previewTab !== 'preview' }"
                :endpoint="previewTopicId"
                :transportFactory.prop="previewTransportFactory"
                :endpointResolver.prop="resolvePreviewEndpoint"
                :composerConfig.prop="previewComposerConfig"
                :beforeSend.prop="beforePreviewSend"
                :disabled="busy || sending"
                placeholder="输入调试问题…（输入 / 调用技能）"
                @dataagent-run-change="onRunChange"
                @dataagent-composer-resize="event => { if (event.detail?.height) previewComposerHeight = event.detail.height }"
                @dataagent-complete="onComplete"
                @dataagent-error="onConversationError"
              >
                <div slot="empty" class="welcome">
                  <div class="welcome-avatar"><Icon name="spark" /></div>
                  <h3>{{ form.name }}</h3>
                  <p v-if="form.description">{{ form.description }}</p>
                  <div class="quick-questions">
                    <button
                      v-for="question in form.preset_questions.filter(Boolean)"
                      :key="question"
                      class="quick-question"
                      :disabled="sending || running || !hasModels"
                      @click="sendPreview(question)"
                    >
                      {{ question }}<Icon name="chevron" />
                    </button>
                  </div>
                </div>
              </dataagent-conversation>
              <div v-else class="chat-content">
                <div class="welcome">
                  <div class="welcome-avatar"><Icon name="spark" /></div>
                  <h3>{{ form.name }}</h3>
                  <p>{{ form.description }}</p>
                  <button class="button primary" @click="openChat">
                    开启对话
                  </button>
                </div>
              </div>
              <div
                v-show="canManage && previewTab === 'debug'"
                class="debug-content"
                role="tabpanel"
              >
                <div class="debug-head">
                  <span class="field-label">本次草稿运行记录</span
                  ><span v-if="activeTaskId" class="badge neutral">{{
                    engineKind || (running ? "运行中" : "已结束")
                  }}</span>
                </div>
                <div v-if="!records.length" class="debug-empty">
                  <Icon name="code" />
                  <h3>暂无运行记录</h3>
                </div>
                <ol v-else class="trace-list">
                  <li
                    v-for="record in debugRecords"
                    :key="record.seq_id"
                    class="trace-item"
                    :class="{ error: record.event_type?.includes('error') }"
                  >
                    <span class="trace-icon"
                      ><Icon
                        :name="
                          record.event_type?.includes('error')
                            ? 'error'
                            : 'check'
                        "
                    /></span>
                    <div class="trace-title">
                      {{ recordLabel(record) }}<span>#{{ record.seq_id }}</span>
                    </div>
                    <details class="trace-detail">
                      <summary>查看详情</summary>
                      <pre>{{
                        JSON.stringify(
                          record.data || record.payload || record,
                          null,
                          2,
                        )
                      }}</pre>
                    </details>
                  </li>
                </ol>
              </div>
            </div>
            <div
              v-if="verdict"
              class="debug-verdict"
              :class="verdictClass"
              role="status"
            >
              <Icon name="info" class="small" />{{ verdict }}
            </div>
          </section>
        </div>
      </template>
    </main>
    <dialog
      ref="publishDialog"
      class="publish-dialog"
      aria-labelledby="publish-title"
      @cancel="publishAck = false"
    >
      <button
        class="icon-button dialog-close"
        aria-label="关闭发布确认"
        :disabled="publishing"
        @click="publishDialog.close()"
      >
        <Icon name="x" />
      </button>
      <div class="dialog-icon"><Icon name="publish" /></div>
      <h2 id="publish-title">确认发布智能体</h2>
      <p class="dialog-description">发布后，新对话使用当前草稿。</p>
      <dl class="publish-summary">
        <div>
          <dt>智能体</dt>
          <dd>{{ form.name }}</dd>
        </div>
        <div>
          <dt>发布版本</dt>
          <dd>v{{ nextVersion }}</dd>
        </div>
        <div>
          <dt>可见范围</dt>
          <dd>{{ visibilityLabel }}</dd>
        </div>
        <div>
          <dt>配置内容</dt>
          <dd>
            {{ form.skill_folders.length }} Skills ·
            {{ form.mcp_server_ids.length }} MCP ·
            {{ form.allowed_tools.length }} 工具
          </dd>
        </div>
        <div>
          <dt>调试状态</dt>
          <dd class="success-text">
            <Icon name="check" class="small" />调试完成
          </dd>
        </div>
      </dl>
      <label class="confirm-checkbox"
        ><input v-model="publishAck" type="checkbox" /><span
          >已检查配置与调试结果</span
        ></label
      >
      <p class="publish-warning">
        <Icon name="info" class="small" />已有对话继续使用原版本。
      </p>
      <div class="dialog-actions">
        <button
          class="button secondary"
          :disabled="publishing"
          @click="publishDialog.close()"
        >
          继续编辑</button
        ><button
          class="button primary"
          :disabled="!publishAck || !canPublish || publishing"
          @click="handlePublish"
        >
          {{ publishing ? "发布中…" : `确认发布 v${nextVersion}` }}
        </button>
      </div>
    </dialog>
  </section>
</template>

<script setup>
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from "vue";
import {
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  useRoute,
  useRouter,
} from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { dataagentApi } from "@/api/dataagent";
import { createNl2SqlApiClient, DATAAGENT_CLIENT_HEADERS } from "@/api/nl2sql";
import { useAuthStore } from "@/stores/auth";
import { withAgentContext } from "@/router/agentContext";
import { createNl2SqlTransport } from "./nl2sqlTransport";
import Icon from "./AgentWorkbenchIcon.vue";
import { buildCommands } from "@opendataworks/agent-conversation";

const route = useRoute(),
  router = useRouter(),
  authStore = useAuthStore();
const canManage = computed(() => authStore.isAdmin);
const api = createNl2SqlApiClient({
  defaultHeaders: DATAAGENT_CLIENT_HEADERS,
  onUnauthorized: () =>
    router.push({ path: "/login", query: { redirect: route.fullPath } }),
});
const agentId = computed(() => String(route.params.agentId || ""));
const loading = ref(true),
  loadError = ref(""),
  saving = ref(false),
  publishing = ref(false),
  sending = ref(false),
  clearing = ref(false);
const busy = computed(() => saving.value || publishing.value || clearing.value);
const form = reactive({
  agent_id: "",
  name: "",
  description: "",
  system_prompt: "",
  allowed_tools: [],
  mcp_server_ids: [],
  skill_folders: [],
  max_turns: 0,
  env_vars: {},
  data_scope: { allowed_scopes: [] },
  visibility: { mode: "all", allowed_users: [], allowed_groups: [] },
  preset_questions: ["", "", ""],
  is_default: false,
});
const draft = reactive({
  revision: 1,
  published_version: 0,
  has_changes: false,
  can_publish: false,
  preview_status: null,
});
const builtinPrompt = ref(""),
  envVarsText = ref("{}"),
  dataScopeOptions = ref([]),
  scopeSelection = ref([]),
  baseline = ref("");
const capabilities = reactive({ tools: [], skills: [], mcp_servers: [] });
const activeResourcePicker = ref("");
const authUserOptions = ref([]),
  authUserLoading = ref(false);
const previewComposerHeight = ref(130);
const previewTopicId = ref(""),
  conversationKey = ref(0),
  conversationRef = ref(null),
  previewTab = ref("preview"),
  records = ref([]),
  activeTaskId = ref(""),
  runStatus = ref(""),
  previewRevision = ref(0),
  previewError = ref("");
const publishDialog = ref(null),
  publishAck = ref(false);
const runtimeConfig = ref({});
const previewProviders = computed(() => (runtimeConfig.value.providers || []).map(provider => ({
  ...provider,
  provider_id: provider.id || provider.provider_id,
  models: (provider.models || []).map(model => typeof model === 'string' ? model : model.name),
})));
const hasModels = computed(() => previewProviders.value.some(provider => provider.enabled !== false && provider.models.length));
const previewComposerConfig = computed(() => ({
  providers: previewProviders.value,
  default_provider_id: runtimeConfig.value.default_provider_id,
  default_model: runtimeConfig.value.default_model,
  slashCommands: buildCommands(form.skill_folders),
  uploadBeforeConversation: true,
}));
const visibleTools = computed(() =>
  canManage.value ? capabilities.tools : form.allowed_tools,
);
const nextVersion = computed(() => Number(draft.published_version || 0) + 1);
const visibilityLabel = computed(
  () =>
    ({ all: "全部用户", authenticated: "仅登录用户", selected: "指定用户" })[
      form.visibility.mode
    ],
);
const scopeKey = (scope) =>
  `${scope?.cluster_id ?? "platform"}::${scope?.database || ""}`;
const scopeLabel = (scope) =>
  `${scope.cluster_name || (scope.cluster_id == null ? "platform-mysql" : `cluster_id=${scope.cluster_id}`)} / ${scope.source_type || "-"} / ${scope.database}`;
const scopeOptionByKey = computed(() => {
  const options = Object.fromEntries(
    dataScopeOptions.value.map((scope) => [scopeKey(scope), scope]),
  );
  for (const scope of form.data_scope?.allowed_scopes || []) {
    options[scopeKey(scope)] ||= scope;
  }
  return options;
});
const editorState = () =>
  JSON.stringify({
    form,
    env: envVarsText.value,
    scopes: scopeSelection.value,
  });
const dirty = computed(
  () => !!baseline.value && editorState() !== baseline.value,
);
const running = computed(() =>
  ["queued", "running", "waiting_input", "waiting_permission"].includes(
    runStatus.value,
  ),
);
const canPublish = computed(
  () =>
    canManage.value &&
    !dirty.value &&
    draft.can_publish &&
    !previewError.value &&
    !loading.value,
);
const runLabel = computed(() =>
  dirty.value ||
  (previewRevision.value && previewRevision.value !== draft.revision)
    ? "需重新调试"
    : running.value
      ? "运行中"
      : sending.value
        ? "提交中"
        : previewError.value ||
            ["failed", "cancelled"].includes(runStatus.value)
          ? "调试未通过"
          : draft.preview_status === "finished"
            ? "调试完成"
            : "待调试",
);
const runBadgeClass = computed(() =>
  runLabel.value === "调试完成"
    ? "success"
    : runLabel.value === "运行中"
      ? "running"
      : runLabel.value === "调试未通过"
        ? "error"
        : "",
);
const verdictClass = computed(() =>
  dirty.value ||
  (previewRevision.value && previewRevision.value !== draft.revision)
    ? "stale"
    : previewError.value || ["failed", "cancelled"].includes(runStatus.value)
      ? "error"
      : draft.preview_status === "finished"
        ? "success"
        : "",
);
const verdict = computed(() =>
  !canManage.value
    ? ""
    : (dirty.value && (activeTaskId.value || draft.preview_status)) ||
        (previewRevision.value && previewRevision.value !== draft.revision)
      ? "草稿已变更，请重新调试。"
      : previewError.value ||
        (runStatus.value === "cancelled"
          ? "调试已停止。"
          : runStatus.value === "failed"
            ? "调试失败，请查看运行记录。"
            : draft.preview_status === "finished" && draft.has_changes
              ? "调试完成，可以确认发布。"
              : ""),
);
const debugRecords = computed(() =>
  records.value.filter(
    (r) =>
      !["content.delta", "text.delta", "thinking.delta"].includes(r.event_type),
  ),
);
const engineKind = computed(
  () => records.value.find((r) => r.engine_kind)?.engine_kind || "",
);
const recordLabel = (record) =>
  record.data?.tool_name ||
  record.data?.name ||
  record.event_type ||
  record.record_type ||
  "运行事件";

const notifyError = (error, fallback) => {
  if (!error?.__odwNotified)
    ElMessage.error(
      error?.response?.data?.detail || error?.message || fallback,
    );
};
function applyAgent(agent) {
  Object.assign(form, {
    ...agent,
    visibility: {
      mode: "all",
      allowed_users: [],
      allowed_groups: [],
      ...(agent.visibility || {}),
    },
    env_vars: agent.env_vars || {},
    data_scope: agent.data_scope || { allowed_scopes: [] },
    preset_questions: [
      agent.preset_questions?.[0] || "",
      agent.preset_questions?.[1] || "",
      agent.preset_questions?.[2] || "",
    ],
  });
  // Metadata is separate from the editable configuration.
  for (const key of [
    "revision",
    "published_version",
    "has_changes",
    "can_publish",
    "preview_task_id",
    "preview_topic_id",
    "preview_status",
  ])
    delete form[key];
  envVarsText.value = JSON.stringify(form.env_vars, null, 2);
  scopeSelection.value = (form.data_scope.allowed_scopes || []).map(scopeKey);
  syncDraft(agent);
  baseline.value = editorState();
}
function syncDraft(agent) {
  for (const key of [
    "revision",
    "published_version",
    "has_changes",
    "can_publish",
    "preview_status",
  ])
    if (key in agent) draft[key] = agent[key];
}
function buildPayload() {
  if (!form.name.trim()) throw new Error("请输入智能体名称");
  let env;
  try {
    env = JSON.parse(envVarsText.value || "{}");
  } catch {
    throw new Error("环境变量必须是合法 JSON");
  }
  if (!env || Array.isArray(env) || typeof env !== "object")
    throw new Error("环境变量必须是 JSON 对象");
  return {
    name: form.name,
    description: form.description,
    system_prompt: form.system_prompt,
    allowed_tools: [...form.allowed_tools],
    mcp_server_ids: [...form.mcp_server_ids],
    skill_folders: [...form.skill_folders],
    max_turns: Number(form.max_turns || 0),
    env_vars: env,
    data_scope: {
      allowed_scopes: scopeSelection.value
        .map((k) => scopeOptionByKey.value[k])
        .filter(Boolean)
        .map((s) => ({
          cluster_id: s.cluster_id ?? null,
          source_type: s.source_type || "",
          database: s.database || "",
        })),
    },
    visibility: {
      ...form.visibility,
      allowed_users: form.visibility.allowed_users
        .map((id) => id.trim())
        .filter(Boolean),
    },
    preset_questions: form.preset_questions
      .map((q) => q.trim())
      .filter(Boolean),
    expected_revision: draft.revision,
  };
}
async function handleSave(silent = false) {
  if (!canManage.value || busy.value) return false;
  saving.value = true;
  try {
    const stateAtSave = editorState(),
      payload = buildPayload(),
      oldRevision = draft.revision,
      generation = loadGeneration;
    const saved = await dataagentApi.updateAgent(form.agent_id, payload);
    if (generation !== loadGeneration) return false;
    if (editorState() === stateAtSave) applyAgent(saved);
    else {
      syncDraft(saved);
      baseline.value = stateAtSave;
    }
    if (saved.revision !== oldRevision && !running.value) detachPreview(false);
    if (!silent) ElMessage.success("草稿已保存");
    return true;
  } catch (error) {
    notifyError(error, "保存失败");
    return false;
  } finally {
    saving.value = false;
  }
}
let loadGeneration = 0;
async function loadDetail() {
  const generation = ++loadGeneration;
  loading.value = true;
  loadError.value = "";
  try {
    const results = await Promise.all([
      canManage.value
        ? dataagentApi.getAgentDraft(agentId.value)
        : dataagentApi.getAgentProfile(agentId.value),
      dataagentApi.getAgentBuiltinPrompt(),
      ...(canManage.value
        ? [
            dataagentApi.getAgentCapabilities(),
            dataagentApi.listDataScopeOptions(),
            api.runtimeApi.getConfig(),
          ]
        : []),
    ]);
    if (generation !== loadGeneration) return;
    const [agent, prompt, caps, scopes, runtime] = results;
    builtinPrompt.value = prompt.content;
    if (canManage.value) {
      Object.assign(capabilities, caps);
      dataScopeOptions.value = scopes;
      runtimeConfig.value = runtime;
    } else dataScopeOptions.value = agent.data_scope?.allowed_scopes || [];
    applyAgent(agent);
    if (canManage.value && agent.preview_topic_id) {
      previewRevision.value = agent.revision;
      previewTopicId.value = agent.preview_topic_id;
      activeTaskId.value = agent.preview_task_id || "";
    }
    if (canManage.value) searchAuthUsers();
  } catch (error) {
    if (generation !== loadGeneration) return;
    loadError.value =
      error?.response?.data?.detail || error?.message || "加载失败";
  } finally {
    if (generation === loadGeneration) loading.value = false;
  }
}
async function searchAuthUsers(keyword = "") {
  if (!canManage.value) return;
  authUserLoading.value = true;
  try {
    const result = await dataagentApi.listAuthUsers({ keyword, limit: 50 });
    const fetched = result.items || [];
    const known = new Set(fetched.map((u) => u.user_id));
    authUserOptions.value = [
      ...fetched,
      ...form.visibility.allowed_users
        .filter((id) => !known.has(id))
        .map((user_id) => ({ user_id })),
    ];
  } catch {
    authUserOptions.value = form.visibility.allowed_users.map((user_id) => ({
      user_id,
    }));
  } finally {
    authUserLoading.value = false;
  }
}
const authUserLabel = (user) =>
  user.display_name ? `${user.display_name}（${user.user_id}）` : user.user_id;
const toggleTool = (tool) => {
  if (canManage.value)
    form.allowed_tools = form.allowed_tools.includes(tool)
      ? form.allowed_tools.filter((t) => t !== tool)
      : [...form.allowed_tools, tool];
};
const optionsFor = (kind) =>
  kind === "skills"
    ? capabilities.skills.map((s) => ({
        id: s.folder,
        name: s.folder,
        description: s.enabled ? "已启用" : "未启用",
      }))
    : capabilities.mcp_servers.map((s) => ({
        id: s.id,
        name: s.name,
        description: `${s.enabled ? "已启用" : "未启用"} · ${s.tool_names?.length || 0} 个工具`,
      }));
const selectedResources = (kind) =>
  (kind === "skills" ? form.skill_folders : form.mcp_server_ids).map(
    (id) => optionsFor(kind).find((s) => s.id === id) || { id, name: id },
  );
const availableResources = (kind) => {
  const selected = kind === "skills" ? form.skill_folders : form.mcp_server_ids;
  return optionsFor(kind).filter((item) => !selected.includes(item.id));
};
function addResource(kind, id) {
  if (
    !canManage.value ||
    publishing.value ||
    !availableResources(kind).some((item) => item.id === id)
  )
    return;
  const key = kind === "skills" ? "skill_folders" : "mcp_server_ids";
  form[key] = [...form[key], id];
  activeResourcePicker.value = "";
}
function removeResource(kind, id) {
  if (canManage.value && !publishing.value) {
    const key = kind === "skills" ? "skill_folders" : "mcp_server_ids";
    form[key] = form[key].filter((v) => v !== id);
  }
}
function captureRecord(record) {
  if (
    ["content.delta", "text.delta", "thinking.delta"].includes(
      record.event_type,
    )
  )
    return;
  if (!records.value.some((r) => r.seq_id === record.seq_id))
    records.value.push(record);
}
function detachPreview(remount = true) {
  previewTopicId.value = "";
  previewRevision.value = 0;
  if (remount) conversationKey.value++;
  activeTaskId.value = "";
  runStatus.value = "";
  records.value = [];
  previewError.value = "";
}
async function resolvePreviewEndpoint() {
  const generation = loadGeneration;
  if (dirty.value && !(await handleSave(true))) throw new Error('请先保存有效草稿');
  if (dirty.value || generation !== loadGeneration) throw new Error('配置已变化，请重新调试');
  const topic = await dataagentApi.createAgentPreviewTopic(agentId.value, draft.revision);
  if (generation !== loadGeneration) throw new Error('智能体已切换');
  previewTopicId.value = topic.topic_id;
  previewRevision.value = draft.revision;
  return topic.topic_id;
}
function previewTransportFactory(topicId) {
  const revision = previewRevision.value || draft.revision;
  const previewApi = {
    ...api,
    taskApi: {
      ...api.taskApi,
      deliverMessage: (data) =>
        dataagentApi.submitAgentPreview(agentId.value, {
          expected_revision: revision,
          topic_id: topicId,
          message_content: data.content,
          provider_id: data.provider_id,
          model: data.model,
        }),
      streamSdkEvents: (taskId, options) =>
        api.taskApi.streamSdkEvents(taskId, {
          ...options,
          onRecord: (record) => {
            captureRecord(record);
            options.onRecord(record);
          },
        }),
    },
  };
  const transport = createNl2SqlTransport(previewApi, topicId, {
    getAgentId: () => agentId.value,
  });
  const load = transport.loadConversation;
  transport.loadConversation = async () => {
    const result = await load();
    const latest = [...result.messages]
      .reverse()
      .find((m) => m.role === "assistant");
    (latest?.records || []).forEach(captureRecord);
    return result;
  };
  // Ratings for test conversations have no product meaning.
  delete transport.submitFeedback;
  return transport;
}
async function beforePreviewSend({ attachments = [] } = {}) {
  if (!canManage.value || sending.value || running.value || busy.value) return false;
  const generation = loadGeneration;
  const attachmentRevision = previewTopicId.value ? previewRevision.value : null;
  sending.value = true;
  previewError.value = '';
  try {
    if (dirty.value && !(await handleSave(true))) return false;
    if (dirty.value) throw new Error('保存期间配置发生变化，请重新发送');
    if (generation !== loadGeneration) return false;
    if (attachments.length && attachmentRevision !== draft.revision) {
      detachPreview(false);
      await nextTick();
      throw new Error('草稿已更新，请重新添加附件后发送');
    }
    if (previewTopicId.value && previewRevision.value !== draft.revision) detachPreview(false);
    records.value = [];
    draft.can_publish = false;
    return true;
  } finally {
    sending.value = false;
  }
}
async function sendPreview(text) {
  if (!canManage.value || busy.value || running.value || sending.value || !hasModels.value || !String(text || '').trim()) return;
  await conversationRef.value?.sendMessage(String(text).trim());
}
function onRunChange(event) {
  runStatus.value = event.detail?.status || "";
  activeTaskId.value = event.detail?.taskId || activeTaskId.value;
  if (running.value) {
    draft.can_publish = false;
    draft.preview_status = "running";
  }
}
async function onComplete() {
  if (!canManage.value) return;
  try {
    syncDraft(await dataagentApi.getAgentDraft(agentId.value));
  } catch (error) {
    notifyError(error, "读取调试状态失败");
  }
}
function onConversationError(event) {
  previewError.value = event.detail?.message || "调试失败";
  draft.can_publish = false;
}
async function resetPreview() {
  if (running.value || !previewTopicId.value) return;
  clearing.value = true;
  try {
    await api.topicApi.deleteTopic(previewTopicId.value);
    detachPreview();
    syncDraft(await dataagentApi.getAgentDraft(agentId.value));
  } catch (error) {
    notifyError(error, "清空失败");
  } finally {
    clearing.value = false;
  }
}
function openPublish() {
  if (!canPublish.value) return;
  publishAck.value = false;
  publishDialog.value.showModal();
}
async function handlePublish() {
  if (!canPublish.value || !publishAck.value || publishing.value) return;
  publishing.value = true;
  try {
    const result = await dataagentApi.publishAgent(
      agentId.value,
      draft.revision,
    );
    syncDraft(result);
    publishDialog.value.close();
    ElMessage.success(`已发布 v${result.published_version}`);
  } catch (error) {
    notifyError(error, "发布失败");
  } finally {
    publishing.value = false;
  }
}
const goBack = () =>
  router.push(
    withAgentContext({ name: "IntelligentQueryAgents" }, route.query),
  );
const openChat = () =>
  router.push(
    withAgentContext(
      { path: "/chat", query: { agent_id: form.agent_id } },
      route.query,
    ),
  );
async function confirmLeave() {
  if (!dirty.value && !running.value && !sending.value) return true;
  try {
    await ElMessageBox.confirm(
      running.value || sending.value
        ? "调试仍在运行，离开后可返回查看结果。"
        : "尚有未保存的修改，是否离开？",
      "离开工作台",
      {
        confirmButtonText: "离开",
        cancelButtonText: "继续编辑",
        type: "warning",
      },
    );
    return true;
  } catch {
    return false;
  }
}
onBeforeRouteLeave(confirmLeave);
onBeforeRouteUpdate(async (to) => {
  if (to.params.agentId === route.params.agentId) return true;
  if (!(await confirmLeave())) return false;
  return true;
});
watch(agentId, () => {
  detachPreview();
  baseline.value = "";
  loadDetail();
});
watch(dirty, (value) => {
  if (value) {
    publishAck.value = false;
    publishDialog.value?.close();
  }
});
const beforeUnload = (event) => {
  if (dirty.value || running.value || sending.value) {
    event.preventDefault();
    event.returnValue = "";
  }
};
onMounted(() => {
  window.addEventListener("beforeunload", beforeUnload);
  loadDetail();
});
onBeforeUnmount(() => { loadGeneration++; window.removeEventListener("beforeunload", beforeUnload); });
</script>

<style scoped src="./agentWorkbench.css"></style>
<style scoped src="./conversationPresentation.css"></style>
<style scoped>
.agent-workbench {
  min-height: 100%;
}
.config-scroll {
  border: 0;
  margin: 0;
  min-width: 0;
}
.config-scroll:disabled {
  opacity: 1;
}
.builtin-content {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: 11px/1.85 var(--font);
}
.title-row h1 {
  overflow-wrap: anywhere;
}
.resource-copy {
  min-width: 0;
}
.resource-copy strong {
  overflow-wrap: anywhere;
}
.resource-add-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.resource-add {
  flex: 1;
  min-width: 0;
}
.resource-add-row > .icon-button {
  flex-shrink: 0;
  color: var(--muted);
}
.load-state {
  min-height: 360px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  align-items: center;
  justify-content: center;
  color: var(--secondary);
}
.preview-conversation {
  display: block;
  min-height: 0;
  height: 100%;
  width: 100%;
  --dac-content-max-width: 100%;
  --dac-content-padding-inline: 20px;
  --dac-message-gap: 22px;
  --dac-bg: #fff;
}
.preview-conversation.debug-hidden::part(messages),
.preview-conversation.debug-hidden::part(empty),
.preview-conversation.debug-hidden::part(suggestions) {
  visibility: hidden;
  pointer-events: none;
}
.preview-viewport {
  flex: 1;
  min-height: 0;
  position: relative;
  overflow: hidden;
}
.preview-viewport .debug-content {
  position: absolute;
  inset: 0 0 var(--preview-composer-height, 130px);
}
.preview-viewport .chat-content {
  height: 100%;
}
.preview-conversation::part(root) {
  justify-content: flex-start;
}
.preview-conversation::part(messages) {
  flex: 1;
  overflow: auto;
}
.preview-conversation::part(empty) {
  flex: 1;
  display: block;
}
.preview-conversation::part(messages) {
  padding-top: 19px;
}
.trace-detail pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 220px;
  overflow: auto;
}
.config-scroll :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px var(--border) inset;
  min-height: 36px;
}
</style>
