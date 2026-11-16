// 会话过期跳登录页的接线点。
//
// 这里刻意不 import router / store：request.js 需要触发跳转，而
// request.js ← api/auth.js ← stores/auth.js ← router/index.js 已经是一条依赖链，
// 反向 import 会成环。改成组合根（main.js）注册回调，本模块保持零依赖。
let unauthorizedHandler = null

export const setUnauthorizedHandler = (handler) => {
  unauthorizedHandler = handler
}

export const handleUnauthorized = () => {
  unauthorizedHandler?.()
}

/**
 * 401 走 SPA 内跳转而不是整页刷新。
 *
 * 整页刷新会把已加载已求值的模块图全部丢掉，登录成功后要重新拉 Layout、
 * 目标页和 vendor chunk，表现就是"登录成功后卡一会儿才进页面"；留在 SPA 内则
 * 是零网络的重新渲染。顺带让组件正常走 onBeforeUnmount，Data Studio 的标签页
 * 持久化（去抖写入）不会再被整页刷新截断。
 */
export const createUnauthorizedRedirect = ({ router, authStore }) => {
  let redirecting = false

  return () => {
    if (redirecting) return
    const current = router.currentRoute.value
    if (current.path === '/login') return

    // 会话过期时并发请求会一起 401，而 router.replace 是异步的，
    // currentRoute 在导航完成前不会更新，只靠路径判断挡不住重复跳转。
    redirecting = true
    authStore.markSessionExpired()
    router
      .replace({ path: '/login', query: { redirect: current.fullPath } })
      .finally(() => {
        redirecting = false
      })
  }
}

/**
 * 净化登录后回跳的站内路径：仅接受单个 '/' 开头的站内路径，
 * 拒绝协议相对路径（'//'）、反斜杠路径（'/\'）、控制字符以及 '/login' 自身。
 */
export const sanitizeRedirect = (target) => {
  if (typeof target !== 'string') return '/'
  const value = target.trim()
  if (!value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return '/'
  }
  if (/[\r\n\t]/.test(value)) {
    return '/'
  }
  const pathOnly = value.split(/[?#]/)[0]
  if (pathOnly === '/login' || pathOnly.startsWith('/login/')) {
    return '/'
  }
  return value
}
