import { settingsApi } from '@/api/settings'
import { useAuthStore } from '@/stores/auth'

const SCRIPT_URL = import.meta.env.VITE_DATAAGENT_WIDGET_JS_URL || '/dataagent/widget/dataagent-widget.bundle.js'
const SCRIPT_ATTR = 'data-odw-dataagent-widget-script'

export function loadDataAgentWidgetScript() {
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return Promise.reject(new Error('DataAgent widget script can only be loaded in a browser'))
  }
  if (window.DataAgentWidget?.installWidget) {
    return Promise.resolve(window.DataAgentWidget)
  }
  if (window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__) {
    return window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__
  }

  const existingScript = document.querySelector(`script[${SCRIPT_ATTR}]`)
  window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__ = new Promise((resolve, reject) => {
    const script = existingScript || document.createElement('script')
    const handleLoad = () => {
      if (window.DataAgentWidget?.installWidget) {
        resolve(window.DataAgentWidget)
      } else {
        reject(new Error('DataAgent widget global API is not available after script load'))
      }
    }
    const handleError = () => reject(new Error(`Failed to load DataAgent widget script: ${SCRIPT_URL}`))

    script.addEventListener('load', handleLoad, { once: true })
    script.addEventListener('error', handleError, { once: true })

    if (!existingScript) {
      script.setAttribute(SCRIPT_ATTR, '')
      script.src = SCRIPT_URL
      script.async = true
      document.head.appendChild(script)
    }
  }).catch((error) => {
    document.querySelector(`script[${SCRIPT_ATTR}]`)?.remove()
    delete window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__
    throw error
  })

  return window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__
}

export const QUERY_AGENT_MISSING_MESSAGE = '尚未配置智能问数助手，请在「配置管理 → 智能助手」中选择'

// 门户问数使用的助手由管理员在「智能助手」设置中选择；DataAgent 没有固定的平台助手 ID。
export async function loadQueryAgentId() {
  const settings = await settingsApi.getAgentSettings()
  const agentId = String(settings?.queryAgentId || '').trim()
  if (!agentId) throw new Error(QUERY_AGENT_MISSING_MESSAGE)
  return agentId
}

// 门户在 DataAgent「Widget 接入」中登记的站点标识，管理员需把门户来源加入该站点的允许来源。
export const PORTAL_WIDGET_SITE_ID = 'opendataworks-portal'

// 门户内嵌时由门户上报当前用户（local:<用户名> 或 <来源>:<external_id>），DataAgent 据此选择数据库账号。
export function portalWidgetIdentity() {
  const userId = String(useAuthStore().currentUser?.agentUserId || '').trim()
  return userId ? { websiteId: PORTAL_WIDGET_SITE_ID, userId } : { websiteId: PORTAL_WIDGET_SITE_ID }
}
