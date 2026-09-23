import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'
import { setDataagentUnauthorizedHandler } from './api/dataagent'
import { defineAgentConversation } from '@opendataworks/agent-conversation'
import './styles/variables.css'
import 'font-awesome/css/font-awesome.min.css'

const app = createApp(App)
const pinia = createPinia()

defineAgentConversation()

app.use(pinia)
app.use(router)

// 认证 401 → 清会话态并跳登录页（仅独立 SPA；widget bundle 不经过本入口）。
export function handleUnauthorized() {
  import('./stores/auth').then(({ useAuthStore }) => {
    const authStore = useAuthStore()
    if (!authStore.enabled) return
    authStore.currentUser = null
    const current = router.currentRoute.value
    if (current.name !== 'Login') {
      router.push({ path: '/login', query: { redirect: current.fullPath } })
    }
  })
}
setDataagentUnauthorizedHandler(handleUnauthorized)

app.mount('#app')
