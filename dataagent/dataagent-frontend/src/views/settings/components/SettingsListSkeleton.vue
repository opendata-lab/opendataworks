<template>
  <!-- 首屏骨架：沿用列表本身的行结构，让加载态和到达后的布局对齐，
       避免 v-loading 遮罩在只有标题的空容器里被压扁。 -->
  <div class="settings-skeleton" role="status" aria-busy="true" aria-live="polite">
    <span class="settings-skeleton__sr">加载中</span>
    <div v-for="section in sectionCount" :key="section" class="settings-skeleton__section">
      <div class="settings-skeleton__title" />
      <div class="settings-skeleton__list">
        <div v-for="row in rows" :key="row" class="settings-skeleton__row">
          <div v-if="icon" class="settings-skeleton__icon" />
          <div class="settings-skeleton__main">
            <div class="settings-skeleton__line settings-skeleton__line--name" />
            <div class="settings-skeleton__line settings-skeleton__line--desc" />
          </div>
          <div class="settings-skeleton__action" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  rows: { type: Number, default: 4 },
  sections: { type: Number, default: 1 },
  icon: { type: Boolean, default: false }
})

const sectionCount = computed(() => Math.max(1, props.sections))
</script>

<style scoped>
.settings-skeleton {
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.settings-skeleton__sr {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.settings-skeleton__section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.settings-skeleton__list {
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #ffffff;
  overflow: hidden;
}

.settings-skeleton__row {
  display: flex;
  align-items: center;
  gap: 16px;
  min-height: 72px;
  padding: 14px 16px;
  border-bottom: 1px solid #f1f5f9;
}

.settings-skeleton__row:last-child {
  border-bottom: none;
}

.settings-skeleton__icon {
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  border-radius: 8px;
}

.settings-skeleton__main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.settings-skeleton__action {
  flex: 0 0 auto;
  width: 64px;
  height: 20px;
  border-radius: 10px;
}

.settings-skeleton__title {
  width: 96px;
  height: 14px;
  border-radius: 4px;
}

.settings-skeleton__line--name {
  width: 38%;
  height: 14px;
  border-radius: 4px;
}

.settings-skeleton__line--desc {
  width: 72%;
  height: 12px;
  border-radius: 4px;
}

/* 一条统一的扫光，比多个独立转圈更安静，也不会因容器高度塌缩而变形。 */
.settings-skeleton__title,
.settings-skeleton__icon,
.settings-skeleton__action,
.settings-skeleton__line {
  background: linear-gradient(90deg, #eef2f7 25%, #f7fafc 37%, #eef2f7 63%);
  background-size: 400% 100%;
  animation: settings-skeleton-sheen 1.4s ease-in-out infinite;
}

@keyframes settings-skeleton-sheen {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .settings-skeleton__title,
  .settings-skeleton__icon,
  .settings-skeleton__action,
  .settings-skeleton__line {
    animation: none;
  }
}
</style>
