<template>
  <el-container class="layout-container">
    <el-header height="60px">
      <div class="header-wrapper">
        <div class="logo">
          <picture class="logo-icon">
            <source srcset="/opendataworks-icon-dark.svg" media="(prefers-color-scheme: dark)">
            <img src="/opendataworks-icon-light.svg" alt="OpenDataWorks 图标">
          </picture>
          <h2>数据门户</h2>
          <el-tag v-if="isDemoMode" size="small" effect="dark" class="demo-tag">演示环境</el-tag>
        </div>
        <el-menu
          :default-active="activeMenu"
          router
          mode="horizontal"
          class="menu"
        >
          <el-menu-item
            v-for="item in menuItems"
            :key="item.index"
            :index="item.index"
            @mouseenter="preloadMenuRoute(item.index)"
            @focus="preloadMenuRoute(item.index)"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </el-menu-item>
        </el-menu>
      </div>
    </el-header>

    <el-main>
      <router-view />
    </el-main>

  </el-container>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataBoard, DataLine, Connection, Collection, Warning, Setting, Share, Link, ChatDotRound } from '@element-plus/icons-vue'
import { isDemoMode } from '@/demo/runtime'
import { preloadRouteComponents, scheduleRouteWarmup } from '@/router/routeWarmup'

const DATAAGENT_WIDGET_SCRIPT_URL = import.meta.env.VITE_DATAAGENT_WIDGET_JS_URL || '/dataagent/widget/opendataworks-widget.bundle.js'
const DATAAGENT_WIDGET_SCRIPT_ATTR = 'data-odw-dataagent-widget-script'

const route = useRoute()
const router = useRouter()
const menuItems = computed(() => {
  const items = [
    { index: '/dashboard', label: '控制台', icon: DataBoard },
    { index: '/datastudio', label: 'Data Studio', icon: DataLine },
    { index: '/workflows', label: '任务调度', icon: Share },
    { index: '/domains', label: '数据建模', icon: Collection },
    { index: '/lineage', label: '数据血缘', icon: Connection },
    { index: '/inspection', label: '数据质量', icon: Warning },
    { index: '/integration', label: '数据集成', icon: Link },
    { index: '/intelligent-query', label: 'Agent问答', icon: ChatDotRound },
    { index: '/settings', label: '设置', icon: Setting }
  ]
  if (!isDemoMode) {
    return items
  }
  return items.filter((item) => ['/dashboard', '/domains', '/datastudio', '/workflows', '/lineage', '/intelligent-query'].includes(item.index))
})
const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/dashboard')) {
    return '/dashboard'
  }
  if (path.startsWith('/datastudio')) {
    return '/datastudio'
  }
  if (path.startsWith('/workflows') || path.startsWith('/tasks')) {
    return '/workflows'
  }
  if (path.startsWith('/domains')) {
    return '/domains'
  }
  if (path.startsWith('/lineage')) {
    return '/lineage'
  }
  if (path.startsWith('/inspection')) {
    return '/inspection'
  }
  if (path.startsWith('/integration')) {
    return '/integration'
  }
  if (path.startsWith('/intelligent-query') || path.startsWith('/nl2sql')) {
    return '/intelligent-query'
  }
  if (path.startsWith('/settings')) {
    return '/settings'
  }
  return path
})

const preloadMenuRoute = (path) => {
  if (!path || path === activeMenu.value) {
    return
  }
  void preloadRouteComponents(router, path)
}

let _widgetCtrl = null
let _layoutUnmounted = false

const loadDataAgentWidgetScript = () => {
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return Promise.reject(new Error('DataAgent widget script can only be loaded in a browser'))
  }
  if (window.OpenDataWorksWidget?.installWidget) {
    return Promise.resolve(window.OpenDataWorksWidget)
  }
  if (window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__) {
    return window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__
  }

  const existingScript = document.querySelector(`script[${DATAAGENT_WIDGET_SCRIPT_ATTR}]`)
  window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__ = new Promise((resolve, reject) => {
    const script = existingScript || document.createElement('script')
    const handleLoad = () => {
      if (window.OpenDataWorksWidget?.installWidget) {
        resolve(window.OpenDataWorksWidget)
      } else {
        reject(new Error('DataAgent widget global API is not available after script load'))
      }
    }
    const handleError = () => reject(new Error(`Failed to load DataAgent widget script: ${DATAAGENT_WIDGET_SCRIPT_URL}`))

    script.addEventListener('load', handleLoad, { once: true })
    script.addEventListener('error', handleError, { once: true })

    if (!existingScript) {
      script.setAttribute(DATAAGENT_WIDGET_SCRIPT_ATTR, '')
      script.src = DATAAGENT_WIDGET_SCRIPT_URL
      script.async = true
      document.head.appendChild(script)
    }
  })

  return window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__
}

const installFloatingWidget = async () => {
  try {
    const widget = await loadDataAgentWidgetScript()
    if (_layoutUnmounted) return
    _widgetCtrl = widget.installWidget({
      displayMode: 'floating',
      position: 'bottom-right',
      projectName: '智能助手',
      projectColor: '#2c5282',
      agentId: 'agent_opendataworks',
      apiBaseUrl: '',
    })
  } catch (error) {
    console.warn('[OpenDataWorks] failed to install DataAgent widget:', error)
  }
}

onMounted(() => {
  scheduleRouteWarmup(
    router,
    menuItems.value
      .map((item) => item.index)
      .filter((path) => path !== activeMenu.value)
  )
  void installFloatingWidget()
})
onBeforeUnmount(() => {
  _layoutUnmounted = true
  _widgetCtrl?.destroy()
})
</script>

<style scoped>
.layout-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  width: 100%;
  min-width: 0;
  overflow: hidden;
}

.el-header {
  background: var(--odw-nav-bg);
  padding: 0;
  box-shadow: var(--odw-shadow-nav);
  position: relative;
  z-index: 100;
  overflow: hidden;
}

.header-wrapper {
  display: flex;
  align-items: center;
  height: 100%;
  width: 100%;
  min-width: 0;
  overflow: hidden;
}

.logo {
  height: var(--odw-nav-height);
  min-width: 200px;
  flex: 0 0 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--odw-nav-logo-bg);
  backdrop-filter: blur(10px);
}

.logo-icon {
  width: 44px;
  height: 44px;
  margin-right: 12px;
  display: inline-flex;
}

.logo-icon img {
  width: 100%;
  height: 100%;
}

.logo h2 {
  color: var(--odw-text-on-dark);
  font-size: 20px;
  font-weight: 600;
  margin: 0;
  letter-spacing: 1px;
}

.demo-tag {
  margin-left: 12px;
  border: none;
  background: rgba(255, 255, 255, 0.18);
  color: var(--odw-text-on-dark);
}

.menu {
  flex: 1 1 auto;
  min-width: 0;
  border: none;
  background: transparent;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: none;
  white-space: nowrap;
}

.menu::-webkit-scrollbar {
  display: none;
}

.menu :deep(.el-menu-item) {
  flex: 0 0 auto;
}

.el-menu--horizontal {
  border-bottom: none;
}

.el-menu-item {
  color: var(--odw-text-on-dark-secondary);
  border-bottom: none;
  transition: all var(--odw-transition);
  font-weight: 500;
}

.el-menu-item:hover {
  background-color: var(--odw-nav-item-hover) !important;
  color: var(--odw-text-on-dark) !important;
  border-bottom: none;
}

.el-menu-item.is-active {
  background-color: var(--odw-nav-item-active) !important;
  color: var(--odw-text-on-dark) !important;
  border-bottom: 2px solid var(--odw-nav-active-border);
  font-weight: 600;
}

.el-main {
  background-color: var(--odw-bg-page);
  padding: 4px;
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  min-height: 0;
  min-width: 0;
}

@media (max-width: 768px) {
  .logo {
    min-width: 164px;
    flex-basis: 164px;
    justify-content: flex-start;
    padding-left: 12px;
  }

  .logo-icon {
    width: 38px;
    height: 38px;
    margin-right: 8px;
  }

  .logo h2 {
    font-size: 18px;
    letter-spacing: 0;
  }
}
</style>
