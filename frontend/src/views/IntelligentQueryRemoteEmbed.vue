<template>
  <div class="dataagent-embed-page">
    <div v-if="isDemoMode" class="dataagent-embed-demo" role="status">
      <div class="dataagent-embed-demo-title">演示环境暂未接入智能问数助手</div>
      <div class="dataagent-embed-demo-desc">
        DataAgent 已拆分为独立服务；当前为静态演示环境，未挂载远程助手脚本。如需体验完整智能问数能力，请在完整部署环境中配置 DataAgent 服务与智能助手 ID。
      </div>
    </div>
    <template v-else>
      <div id="odw-dataagent-inline-widget" class="dataagent-inline-widget"></div>
      <div v-if="errorText" class="dataagent-embed-error">{{ errorText }}</div>
    </template>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { isDemoMode } from '@/demo/runtime'
import { QUERY_AGENT_MISSING_MESSAGE, loadDataAgentWidgetScript, loadQueryAgentId, portalWidgetIdentity } from '@/utils/dataagentWidgetLoader'

const INLINE_CONTAINER_ID = 'odw-dataagent-inline-widget'

const errorText = ref('')
const route = useRoute()
let widgetController = null
let unmounted = false

const installInlineWidget = async () => {
  if (isDemoMode) return
  try {
    const [widget, agentId] = await Promise.all([loadDataAgentWidgetScript(), loadQueryAgentId()])
    if (unmounted) return
    widgetController = widget.installWidget({
      displayMode: 'inline',
      containerId: INLINE_CONTAINER_ID,
      projectName: '智能问数',
      projectColor: '#3B82F6',
      agentId,
      ...portalWidgetIdentity(),
      apiBaseUrl: '',
    })
    if (typeof route.query.topicId === 'string' && route.query.topicId) {
      widgetController.selectConversation(route.query.topicId)
    }
  } catch (error) {
    console.warn('[OpenDataWorks] failed to install inline DataAgent widget:', error)
    errorText.value = error?.message === QUERY_AGENT_MISSING_MESSAGE
      ? QUERY_AGENT_MISSING_MESSAGE
      : '智能问数加载失败，请检查 DataAgent 转发配置。'
  }
}

watch(() => route.query.topicId, (topicId) => {
  if (!widgetController) return
  if (typeof topicId === 'string' && topicId) widgetController.selectConversation(topicId)
  else widgetController.newConversation()
})

onMounted(() => {
  if (!isDemoMode) {
    void installInlineWidget()
  }
})

onBeforeUnmount(() => {
  unmounted = true
  widgetController?.destroy()
})
</script>

<style scoped>
.dataagent-embed-page {
  position: relative;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.dataagent-embed-demo {
  margin: 16px;
  max-width: 560px;
  padding: 16px 20px;
  border: 1px solid var(--odw-border, #e2e8f0);
  border-radius: var(--odw-radius-md, 8px);
  background: var(--odw-bg-card, #ffffff);
  color: var(--odw-text-primary, #1a202c);
}

.dataagent-embed-demo-title {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.5;
}

.dataagent-embed-demo-desc {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--odw-text-secondary, #718096);
}

.dataagent-inline-widget {
  width: 100%;
  height: 100%;
  min-height: 360px;
}

.dataagent-embed-error {
  position: absolute;
  inset: 16px auto auto 16px;
  max-width: min(520px, calc(100% - 32px));
  padding: 12px 14px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  background: #fef2f2;
  color: #991b1b;
  font-size: 14px;
}
</style>
