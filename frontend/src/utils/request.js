import axios from 'axios'
import { ElMessage } from 'element-plus'
import { demoAdapter } from '@/demo/mockServer'
import { isDemoMode } from '@/demo/runtime'

const request = axios.create({
  baseURL: isDemoMode ? '' : '/api',
  timeout: 60000,  // 增加到60秒，支持较长时间的操作（如工作流执行）
  withCredentials: true  // 携带 odw_session 会话 Cookie
})

if (isDemoMode) {
  request.defaults.adapter = demoAdapter
}

const shouldSkipErrorMessage = (config) => {
  return !!(config && (config.skipErrorMessage || config.silent))
}

// 请求拦截器
request.interceptors.request.use(
  config => {
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器
request.interceptors.response.use(
  response => {
    const res = response.data
    // 后端统一返回格式 {code, data, message}
    if (res.code !== 200) {
      if (!shouldSkipErrorMessage(response.config)) {
        ElMessage.error(res.message || '请求失败')
      }
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res.data
  },
  error => {
    const config = error?.config
    const responseMessage = error?.response?.data?.message

    // 未登录或会话过期：统一跳转登录页，不再弹错误提示
    if (error?.response?.status === 401 && !config?.skipAuthRedirect && !isDemoMode) {
      if (window.location.pathname !== '/login') {
        const redirect = encodeURIComponent(window.location.pathname + window.location.search)
        window.location.href = `/login?redirect=${redirect}`
      }
      return Promise.reject(error)
    }

    const message = responseMessage || error.message || '网络错误'
    if (!shouldSkipErrorMessage(config)) {
      ElMessage.error(message)
    }
    if (responseMessage) {
      error.message = responseMessage
    }
    return Promise.reject(error)
  }
)

export default request
