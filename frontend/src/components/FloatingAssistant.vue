<template>
  <Teleport to="body">
    <button
      v-if="!isOpen"
      class="fa-fab"
      type="button"
      aria-label="打开 AI 助手"
      @click="openPanel"
    >
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        <circle cx="9" cy="10" r="0.5" fill="currentColor" stroke="none" />
        <circle cx="12" cy="10" r="0.5" fill="currentColor" stroke="none" />
        <circle cx="15" cy="10" r="0.5" fill="currentColor" stroke="none" />
      </svg>
    </button>

    <Transition name="fa-slide">
      <div v-if="isOpen" class="fa-panel" role="dialog" aria-label="AI 助手">
        <header class="fa-panel-header">
          <svg class="fa-panel-logo" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M12 2V4" />
            <rect x="4" y="6" width="16" height="12" rx="2" />
            <circle cx="9" cy="12" r="1.5" fill="currentColor" stroke="none" />
            <circle cx="15" cy="12" r="1.5" fill="currentColor" stroke="none" />
            <path d="M9 16c1.5 1 4.5 1 6 0" />
          </svg>
          <span class="fa-panel-title">AI 助手</span>
          <div class="fa-panel-actions">
            <button class="fa-icon-btn" type="button" title="新建对话" aria-label="新建对话" @click="handleNewTopic">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <line x1="12" y1="5" x2="12" y2="19" />
                <line x1="5" y1="12" x2="19" y2="12" />
              </svg>
            </button>
            <a class="fa-icon-btn" href="/intelligent-query" title="在完整界面中打开" aria-label="在完整界面中打开">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M15 3h6v6" />
                <path d="M10 14L21 3" />
                <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
              </svg>
            </a>
            <button class="fa-icon-btn" type="button" title="关闭" aria-label="关闭面板" @click="isOpen = false">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>
        </header>
        <div class="fa-panel-body">
          <NL2SqlChat v-if="chatMounted" ref="chatRef" mode="panel" />
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, onBeforeUnmount } from 'vue'
import NL2SqlChat from '@/views/intelligence/NL2SqlChat.vue'

const isOpen = ref(false)
const chatMounted = ref(false)
const chatRef = ref(null)

const openPanel = () => {
  isOpen.value = true
  chatMounted.value = true
}

const handleNewTopic = () => {
  chatRef.value?.handleNewTopic()
}

const onKeydown = (e) => {
  if (e.key === 'Escape' && isOpen.value) {
    isOpen.value = false
  }
}

document.addEventListener('keydown', onKeydown)
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.fa-fab {
  position: fixed;
  bottom: 28px;
  right: 28px;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  background: linear-gradient(135deg, var(--odw-primary) 0%, var(--odw-primary-dark) 100%);
  color: #fff;
  box-shadow: 0 4px 20px rgba(44, 82, 130, 0.35);
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform var(--odw-transition), box-shadow var(--odw-transition);
}

.fa-fab:hover {
  transform: scale(1.08);
  box-shadow: 0 6px 28px rgba(44, 82, 130, 0.45);
}

.fa-fab:active {
  transform: scale(0.96);
}

.fa-panel {
  position: fixed;
  bottom: 28px;
  right: 28px;
  width: 480px;
  height: min(680px, calc(100vh - 100px));
  border-radius: var(--odw-radius-lg);
  background: #ffffff;
  box-shadow: 0 12px 48px rgba(15, 23, 42, 0.18), 0 2px 8px rgba(15, 23, 42, 0.08);
  z-index: 200;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.fa-panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  height: 52px;
  flex-shrink: 0;
  background: linear-gradient(135deg, var(--odw-primary) 0%, var(--odw-primary-dark) 100%);
  color: var(--odw-text-on-dark);
  border-radius: var(--odw-radius-lg) var(--odw-radius-lg) 0 0;
}

.fa-panel-logo {
  opacity: 0.85;
  flex-shrink: 0;
}

.fa-panel-title {
  flex: 1;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.3px;
  color: var(--odw-text-on-dark);
}

.fa-panel-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.fa-icon-btn {
  width: 32px;
  height: 32px;
  border-radius: var(--odw-radius-sm);
  border: none;
  background: transparent;
  color: var(--odw-text-on-dark-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background var(--odw-transition), color var(--odw-transition);
  text-decoration: none;
}

.fa-icon-btn:hover {
  background: rgba(255, 255, 255, 0.15);
  color: var(--odw-text-on-dark);
}

.fa-icon-btn:active {
  background: rgba(255, 255, 255, 0.22);
}

.fa-panel-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.fa-slide-enter-active {
  transition: transform 0.28s cubic-bezier(0.32, 0.72, 0, 1), opacity 0.22s ease;
}

.fa-slide-leave-active {
  transition: transform 0.22s ease, opacity 0.18s ease;
}

.fa-slide-enter-from,
.fa-slide-leave-to {
  transform: translateY(24px);
  opacity: 0;
}

@media (max-width: 768px) {
  .fa-fab {
    bottom: 16px;
    right: 16px;
  }

  .fa-panel {
    bottom: 16px;
    right: 16px;
    width: calc(100vw - 32px);
  }
}
</style>
