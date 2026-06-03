import { ElMessage } from 'element-plus'

export const isDemoMode = import.meta.env.VITE_DEMO_MODE === 'true'

export const demoReadonlyMessage = '当前为演示环境，仅支持浏览与查询。'

export const showDemoReadonlyMessage = (action = '该操作') => {
  ElMessage.info(`${action}在演示环境中不可用`)
}
