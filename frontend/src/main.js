import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'
import './styles/variables.css'

import { installWidget } from '@/widget/entry'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.mount('#app')

// 右下角悬浮 OpenDataWorks 助手
installWidget({
  displayMode: 'floating',
  position: 'bottom-right',
  projectName: 'AI 智能问数',
  apiBaseUrl: '',
  agentId: 'agent_opendataworks',
})
