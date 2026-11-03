<template>
  <section class="settings-content">
    <DataSourceManagement v-if="activeTab === 'datasource'" />
    <UserManagement v-else-if="activeTab === 'users'" />
    <DolphinConfig v-else-if="activeTab === 'dolphin'" />
    <MinioConfigManagement v-else-if="activeTab === 'minio'" />
    <AgentSettings v-else-if="activeTab === 'agent'" />
  </section>
</template>
<script setup>
import { computed, watch } from 'vue'
import { getActivePinia } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import DataSourceManagement from './DataSourceManagement.vue'
import UserManagement from './UserManagement.vue'
import DolphinConfig from './DolphinConfig.vue'
import MinioConfigManagement from './MinioConfigManagement.vue'
import AgentSettings from './AgentSettings.vue'
const route = useRoute()
const router = useRouter()
const authStore = getActivePinia() ? useAuthStore() : null
const canManageUsers = computed(() => !authStore || !authStore.initialized || authStore.isAdmin)
const activeTab = computed(() => {
  const tab = route?.query?.tab
  if (tab === 'users') return canManageUsers.value ? 'users' : 'datasource'
  return ['datasource', 'dolphin', 'minio', 'agent'].includes(tab) ? tab : 'datasource'
})
watch(() => route?.query?.tab, tab => {
  if (['dataagent', 'skills'].includes(tab)) router.replace('/intelligent-query')
}, { immediate: true })
</script>
<style scoped>
.settings-content { padding: 16px 20px; min-width: 0; height: 100%; overflow: auto; box-sizing: border-box; }
.settings-content :deep(.dolphin-config), .settings-content :deep(.minio-config) { max-width: none; margin: 0; }
</style>
