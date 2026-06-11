<template>
  <div class="dataagent-embed-page">
    <div id="odw-dataagent-inline-widget" class="dataagent-inline-widget"></div>
    <div v-if="errorText" class="dataagent-embed-error">{{ errorText }}</div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { loadDataAgentWidgetScript } from '@/utils/dataagentWidgetLoader'

const INLINE_CONTAINER_ID = 'odw-dataagent-inline-widget'

const errorText = ref('')
let widgetController = null
let unmounted = false

const installInlineWidget = async () => {
  try {
    const widget = await loadDataAgentWidgetScript()
    if (unmounted) return
    widgetController = widget.installWidget({
      displayMode: 'inline',
      containerId: INLINE_CONTAINER_ID,
      projectName: '智能问数',
      projectColor: '#2c5282',
      agentId: 'agent_opendataworks',
      apiBaseUrl: '',
    })
  } catch (error) {
    console.warn('[OpenDataWorks] failed to install inline DataAgent widget:', error)
    errorText.value = '智能问数加载失败，请检查 DataAgent 前端地址配置。'
  }
}

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
