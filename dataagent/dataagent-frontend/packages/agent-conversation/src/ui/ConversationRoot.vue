<template>
  <div class="dac-root">
    <div class="dac-messages">
      <slot name="empty">暂无消息</slot>
    </div>
    <div class="dac-composer">
      <div class="dac-composer-actions">
        <slot name="composer-actions" />
      </div>
    </div>
  </div>
</template>

<script setup>
// T1 scaffold: layout and slot anchors only. The conversation kernel, composer
// and message list arrive in T2/T3. Keeping this component inert on purpose —
// T1 exists to prove the custom element plumbing, not to render conversations.
defineProps({
  placeholder: { type: String, default: '' }
})
</script>

<style>
/* Element Plus popovers must never teleport out of the shadow root: the
   package's styles live here, so a teleported layer would render unstyled.
   The stacking context below is what keeps in-place layers on top. */
.dac-root {
  position: relative;
  z-index: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  font-family: Inter, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
  font-size: 14px;
  color: #0f172a;
}

.dac-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
}

.dac-composer {
  border-top: 1px solid #e2e8f0;
  padding: 12px 16px;
}

.dac-composer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
