<template>
  <div class="dataagent-embed-page">
    <div id="odw-dataagent-inline-widget" class="dataagent-inline-widget"></div>
    <div v-if="errorText" class="dataagent-embed-error">{{ errorText }}</div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { QUERY_AGENT_MISSING_MESSAGE, loadDataAgentWidgetScript, loadQueryAgentId } from '@/utils/dataagentWidgetLoader'

const INLINE_CONTAINER_ID = 'odw-dataagent-inline-widget'

const errorText = ref('')
const route = useRoute()
let widgetController = null
let unmounted = false

const installInlineWidget = async () => {
  try {
    const [widget, agentId] = await Promise.all([loadDataAgentWidgetScript(), loadQueryAgentId()])
    if (unmounted) return
    widgetController = widget.installWidget({
      displayMode: 'inline',
      containerId: INLINE_CONTAINER_ID,
      projectName: '智能问数',
      projectColor: '#3B82F6',
      agentId,
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
  void installInlineWidget()
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
