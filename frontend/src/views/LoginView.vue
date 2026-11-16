<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="login-header">
        <picture class="login-logo">
          <source srcset="/opendataworks-icon-dark.svg" media="(prefers-color-scheme: dark)">
          <img src="/opendataworks-icon-light.svg" alt="OpenDataWorks 图标">
        </picture>
        <h2>数据门户</h2>
        <p class="login-subtitle">OpenDataWorks 统一数据中台</p>
      </div>

      <el-alert
        v-if="oauthErrorMessage"
        class="login-alert"
        type="error"
        :title="oauthErrorMessage"
        :closable="false"
        show-icon
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @keyup.enter="handleLogin"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            :prefix-icon="User"
            autocomplete="username"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            show-password
            autocomplete="current-password"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            class="login-button"
            :loading="loading"
            @click="handleLogin"
          >
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <div v-if="oauthProviders.length" class="oauth-section">
        <el-divider class="oauth-divider">或</el-divider>
        <div class="oauth-buttons">
          <el-button
            v-for="provider in oauthProviders"
            :key="provider.id"
            size="large"
            class="oauth-button"
            @click="handleOAuthLogin(provider)"
          >
            通过 {{ provider.name || provider.id }} 登录
          </el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'
import { isDemoMode } from '@/demo/runtime'
import { useAuthStore } from '@/stores/auth'
import { sanitizeRedirect } from '@/utils/authRedirect'

const OAUTH_REDIRECT_KEY = 'odw_login_redirect'
const OAUTH_ERROR_MESSAGES = {
  oauth_denied: '已取消授权',
  oauth_account_disabled: '账号已停用，请联系管理员'
}
const DEFAULT_OAUTH_ERROR = '第三方登录失败，请重试或联系管理员'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const formRef = ref(null)
const loading = ref(false)
const oauthProviders = ref([])
const callbackFailed = ref(false)
const form = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const oauthErrorMessage = computed(() => {
  if (callbackFailed.value) {
    return DEFAULT_OAUTH_ERROR
  }
  const rawError = Array.isArray(route.query.error) ? route.query.error[0] : route.query.error
  if (typeof rawError !== 'string' || !rawError.trim()) {
    return ''
  }
  return OAUTH_ERROR_MESSAGES[rawError.trim()] || DEFAULT_OAUTH_ERROR
})

const readQueryRedirect = () => {
  const raw = Array.isArray(route.query.redirect) ? route.query.redirect[0] : route.query.redirect
  return sanitizeRedirect(raw)
}

const readAndClearOAuthRedirect = () => {
  try {
    const saved = window.sessionStorage.getItem(OAUTH_REDIRECT_KEY)
    window.sessionStorage.removeItem(OAUTH_REDIRECT_KEY)
    return sanitizeRedirect(saved)
  } catch {
    return '/'
  }
}

const loadLoginOptions = async () => {
  try {
    const options = await authApi.options()
    oauthProviders.value = Array.isArray(options?.oauth) ? options.oauth : []
  } catch {
    oauthProviders.value = []
  }
}

onMounted(async () => {
  if (!isDemoMode && route.query.oauth === 'success') {
    await authStore.fetchMe()
    if (authStore.isLoggedIn) {
      await router.replace(readAndClearOAuthRedirect())
      return
    }
    callbackFailed.value = true
  }
  await loadLoginOptions()
})

const handleLogin = async () => {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await authStore.login(form.username, form.password)
    router.replace(readQueryRedirect())
  } catch (error) {
    // 失败提示由 request.js 响应拦截器统一弹出
  } finally {
    loading.value = false
  }
}

const handleOAuthLogin = (provider) => {
  try {
    window.sessionStorage.setItem(OAUTH_REDIRECT_KEY, readQueryRedirect())
  } catch {
    // 忽略存储异常，仍允许跳转授权
  }
  window.location.assign(`/api/oauth2/authorization/${encodeURIComponent(provider.id)}`)
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--odw-bg-page);
}

.login-card {
  width: 400px;
  padding: 8px 8px 0;
}

.login-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 24px;
}

.login-logo {
  width: 56px;
  height: 56px;
  margin-bottom: 12px;
}

.login-logo img {
  width: 100%;
  height: 100%;
}

.login-header h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
}

.login-subtitle {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.login-button {
  width: 100%;
}

.login-alert {
  margin-bottom: 18px;
}

.oauth-section {
  padding-bottom: 18px;
}

.oauth-divider {
  margin: 8px 0 18px;
}

.oauth-buttons {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.oauth-button {
  width: 100%;
  margin-left: 0;
}
</style>
