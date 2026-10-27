<template>
  <el-container class="layout-container" :style="widgetLayoutStyle">
    <el-header height="var(--odw-nav-height)">
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
        <div v-if="!isDemoMode && authStore.currentUser" class="user-area">
          <el-dropdown trigger="click" @command="handleUserCommand">
            <span class="user-trigger">
              <el-icon><User /></el-icon>
              <span class="user-name">{{ authStore.currentUser.nickname || authStore.currentUser.username }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="changePassword">修改密码</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </el-header>

    <el-main>
      <header class="portal-page-header">
        <h1>{{ pageTitle }}</h1>
        <AiAssistantTrigger :prompts="assistantPrompts" />
      </header>
      <div class="portal-page-content"><router-view /></div>
    </el-main>

    <el-dialog v-model="passwordDialogVisible" title="修改密码" width="420px">
      <el-form
        ref="passwordFormRef"
        :model="passwordForm"
        :rules="passwordRules"
        label-width="80px"
      >
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordSubmitting" @click="submitChangePassword">确定</el-button>
      </template>
    </el-dialog>

  </el-container>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, provide, readonly, reactive, ref } from 'vue'
import { isNavigationFailure, NavigationFailureType, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { DataBoard, DataLine, Connection, Collection, Warning, Setting, Share, Link, ChatDotRound, User, ArrowDown } from '@element-plus/icons-vue'
import { isDemoMode } from '@/demo/runtime'
import { preloadRouteComponents, scheduleRouteWarmup } from '@/router/routeWarmup'
import { loadDataAgentWidgetScript, loadQueryAgentId } from '@/utils/dataagentWidgetLoader'
import AiAssistantTrigger from '@/components/AiAssistantTrigger.vue'
import { FLOATING_WIDGET_KEY } from '@/utils/floatingWidget'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/api/auth'

const workflowPrompts = {
  workflows: ['帮我查看工作流发布趋势', '帮我分析工作流调度依赖', '帮我解释工作流发布流程'],
  tasks: ['帮我查询任务执行情况', '帮我分析任务之间的依赖', '帮我检查任务 SQL'],
  monitor: ['帮我分析工作流执行失败原因', '帮我查看工作流运行趋势', '帮我分析运行耗时较长的工作流']
}
const dataStudioPrompts = ['帮我编写数据查询 SQL', '帮我分析数据表结构', '帮我设计数仓分层模型']

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const passwordDialogVisible = ref(false)
const passwordSubmitting = ref(false)
const passwordFormRef = ref(null)
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})
const passwordRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, message: '新密码长度不能少于8位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

const handleUserCommand = async (command) => {
  if (command === 'logout') {
    await authStore.logout()
    router.replace('/login')
    return
  }
  if (command === 'changePassword') {
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    passwordDialogVisible.value = true
  }
}

const submitChangePassword = async () => {
  if (!passwordFormRef.value) return
  const valid = await passwordFormRef.value.validate().catch(() => false)
  if (!valid) return

  passwordSubmitting.value = true
  try {
    await authApi.changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })
    passwordDialogVisible.value = false
    ElMessage.success('密码修改成功')
  } catch (error) {
    // 失败提示由 request.js 响应拦截器统一弹出
  } finally {
    passwordSubmitting.value = false
  }
}
const menuItems = computed(() => {
  const items = [
    { index: '/dashboard', label: '控制台', icon: DataBoard },
    { index: '/datastudio', label: '数据开发', icon: DataLine },
    { index: '/workflows', label: '工作流', icon: Share },
    { index: '/domains', label: '数据建模', icon: Collection },
    { index: '/lineage', label: '数据血缘', icon: Connection },
    { index: '/inspection', label: '数据质量', icon: Warning },
    { index: '/integration', label: '数据集成', icon: Link },
    { index: '/intelligent-query', label: '智能问数', icon: ChatDotRound },
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

const pageTitle = computed(() => route.meta?.title || menuItems.value.find(item => item.index === activeMenu.value)?.label || '数据门户')
const pagePrompts = {
  '/dashboard': ['帮我汇总平台数据资产概况', '帮我查看工作流运行趋势', '帮我梳理数据质量问题'],
  '/domains': ['帮我设计业务域和数据域划分', '帮我解释数仓分层的设计方法', '帮我梳理数据建模流程'],
  '/lineage': ['帮我分析表的上下游依赖', '帮我评估表结构变更的影响范围', '帮我解释数据血缘关系'],
  '/inspection': ['帮我分析数据质量问题', '帮我设计数据巡检规则', '帮我排查数据异常的原因'],
  '/integration': ['帮我梳理数据同步任务配置', '帮我排查数据同步失败原因', '帮我检查数据源连接配置'],
  '/settings': ['帮我了解平台配置项', '帮我检查调度环境配置', '帮我配置智能助手'],
  '/intelligent-query': ['帮我推荐数据分析方法', '帮我整理一个数据分析问题', '帮我解释查询结果']
}
const assistantPrompts = computed(() => {
  if (route.path.startsWith('/datastudio')) return dataStudioPrompts
  if (route.path === '/workflows') return workflowPrompts[route.query?.tab] || workflowPrompts.workflows
  if (route.path.startsWith('/workflows/')) {
    const name = route.params?.id ? `工作流 #${route.params.id}` : '当前工作流'
    return [`帮我分析${name}的调度依赖`, `帮我排查${name}的执行失败原因`, `帮我梳理${name}的发布流程`]
  }
  return pagePrompts[activeMenu.value] || [`帮我了解${pageTitle.value}的使用方法`, '帮我分析当前的数据问题']
})

const preloadMenuRoute = (path) => {
  if (!path || path === activeMenu.value) {
    return
  }
  void preloadRouteComponents(router, path)
}

let _widgetCtrl = null
let _layoutUnmounted = false
let _widgetInstallPromise = null
const floatingWidgetOpen = ref(false)
const widgetPanelLayout = ref({ side: null, reservedWidth: 0 })
const widgetLayoutStyle = computed(() => {
  const { side, reservedWidth: width } = widgetPanelLayout.value
  return width ? { width: `calc(100% - ${width}px)`, marginLeft: side === 'left' ? `${width}px` : '0', marginRight: side === 'right' ? `${width}px` : '0' } : {}
})

const installFloatingWidget = () => {
  if (_widgetInstallPromise) return _widgetInstallPromise
  _widgetInstallPromise = (async () => {
    const [widget, agentId] = await Promise.all([loadDataAgentWidgetScript(), loadQueryAgentId()])
    if (_layoutUnmounted) throw new Error('页面已关闭')
    _widgetCtrl = widget.installWidget({
      displayMode: 'floating',
      position: 'bottom-right',
      projectName: '智能助手',
      projectColor: '#3B82F6',
      agentId,
      apiBaseUrl: '',
      onFullscreen: async ({ topicId }) => {
        const failure = await router.push({
          path: '/intelligent-query',
          query: topicId ? { topicId } : {}
        })
        if (!failure || isNavigationFailure(failure, NavigationFailureType.duplicated)) _widgetCtrl?.close()
      },
    })
    _widgetCtrl.on('open', () => { floatingWidgetOpen.value = true })
    _widgetCtrl.on('close', () => { floatingWidgetOpen.value = false })
    _widgetCtrl.on('panel-layout', (layout) => { widgetPanelLayout.value = layout })
    widgetPanelLayout.value = _widgetCtrl.getPanelLayout()
    return _widgetCtrl
  })().catch((error) => {
    _widgetInstallPromise = null
    throw error
  })
  return _widgetInstallPromise
}

provide(FLOATING_WIDGET_KEY, {
  isOpen: readonly(floatingWidgetOpen),
  open: async (draft) => {
    const controller = await installFloatingWidget()
    if (_layoutUnmounted) throw new Error('页面已关闭')
    controller.open({ draft })
    return controller
  }
})

onMounted(() => {
  scheduleRouteWarmup(
    router,
    menuItems.value
      .map((item) => item.index)
      .filter((path) => path !== activeMenu.value)
  )
  void installFloatingWidget().catch((error) => {
    console.warn('[OpenDataWorks] failed to install DataAgent widget:', error)
  })
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
  width: 32px;
  height: 32px;
  margin-right: 8px;
  display: inline-flex;
}

.logo-icon img {
  width: 100%;
  height: 100%;
}

.logo h2 {
  white-space: nowrap;
  color: var(--odw-text-on-dark);
  font-size: 18px;
  font-weight: 600;
  margin: 0;
  letter-spacing: 0;
}

.demo-tag {
  margin-left: 12px;
  border: none;
  background: rgba(255, 255, 255, 0.18);
  color: var(--odw-text-on-dark);
}

.menu {
  --el-menu-horizontal-height: var(--odw-nav-height);
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

.user-area {
  flex: 0 0 auto;
  padding: 0 16px;
}

.user-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--odw-text-on-dark-secondary);
  cursor: pointer;
  outline: none;
  white-space: nowrap;
}

.user-trigger:hover {
  color: var(--odw-text-on-dark);
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
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
  padding: 0;
  display: flex;
  flex-direction: column;
  flex: 1;
  overflow: hidden;
  overflow-x: hidden;
  min-height: 0;
  min-width: 0;
}

.portal-page-header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  min-height: 44px;
  padding: 6px 16px;
  box-sizing: border-box;
  flex: none;
  background: var(--odw-bg-card);
  border-bottom: 1px solid var(--odw-border);
}
.portal-page-header h1 { margin: 0; font: inherit; font-size: 16px; line-height: 24px; font-weight: 600; color: var(--odw-text-primary); }
.portal-page-content { flex: 1; min-height: 0; min-width: 0; overflow-y: auto; overflow-x: hidden; padding: 4px; }

@media (max-width: 520px) {
  .portal-page-header :deep(.ai-assistant-prompt-window) { display: none; }
}

@media (max-width: 768px) {
  .logo {
    min-width: 148px;
    flex-basis: 148px;
    justify-content: flex-start;
    padding-left: 8px;
  }

  .logo-icon {
    width: 24px;
    height: 24px;
    margin-right: 4px;
  }

  .logo h2 {
    white-space: nowrap;
    font-size: 14px;
    letter-spacing: 0;
  }
  .demo-tag { margin-left: 6px; padding: 0 4px; height: 18px; font-size: 10px; }
}
</style>
