<template>
  <div v-loading="loading" class="widget-conversations">
    <header class="page-header">
      <div class="page-header-main">
        <div class="page-kicker">智能问数 · Widget 会话</div>
        <h2 class="page-title">Widget 会话审计</h2>
        <p class="page-desc">
          查看通过嵌入式 Widget 产生的会话（只读）。门户与 Widget、以及各站点/访客之间的会话隔离不受影响，此页面仅供后台审计使用。
        </p>
      </div>
      <div class="page-header-actions">
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </div>
    </header>

    <section class="filter-bar">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="站点">
          <el-select
            v-model="filters.website_id"
            clearable
            filterable
            allow-create
            default-first-option
            placeholder="全部站点"
            class="filter-site"
          >
            <el-option
              v-for="site in siteOptions"
              :key="site.website_id"
              :label="site.label"
              :value="site.website_id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="外部用户">
          <el-input v-model="filters.external_user_id" clearable placeholder="external_user_id" />
        </el-form-item>
        <el-form-item label="访客">
          <el-input v-model="filters.visitor_id" clearable placeholder="visitor_id" />
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" clearable placeholder="按标题搜索" @keyup.enter="applyFilters" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="applyFilters">查询</el-button>
          <el-button :icon="RefreshLeft" @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <el-table :data="topics" border stripe class="topic-table" empty-text="暂无 Widget 会话">
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
      <el-table-column label="来源" width="90">
        <template #default="{ row }">
          <el-tag size="small" type="warning">{{ row.source || 'widget' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="website_id" label="站点" min-width="120" show-overflow-tooltip />
      <el-table-column label="用户 / 访客" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.external_user_id">用户：{{ row.external_user_id }}</span>
          <span v-else-if="row.visitor_id">访客：{{ row.visitor_id }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="message_count" label="消息数" width="90" align="center" />
      <el-table-column prop="last_message_preview" label="最近消息" min-width="200" show-overflow-tooltip />
      <el-table-column prop="updated_at" label="更新时间" width="180" />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link :icon="ChatLineSquare" @click="openMessages(row)">查看消息</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="loadTopics"
        @size-change="onPageSizeChange"
      />
    </div>

    <el-drawer
      v-model="drawerVisible"
      :title="drawerTitle"
      size="46%"
      direction="rtl"
    >
      <div v-loading="messagesLoading" class="message-pane">
        <div v-if="!messages.length && !messagesLoading" class="empty-block">
          <div class="empty-title">暂无消息</div>
        </div>
        <div v-for="msg in messages" :key="msg.message_id" class="message-item" :class="msg.sender_type">
          <div class="message-meta">
            <el-tag size="small" :type="msg.sender_type === 'user' ? 'info' : 'success'">
              {{ msg.sender_type === 'user' ? '用户' : '助手' }}
            </el-tag>
            <span class="message-time">{{ msg.created_at }}</span>
          </div>
          <div class="message-content">{{ msg.content || '（无文本内容）' }}</div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatLineSquare, Refresh, RefreshLeft, Search } from '@element-plus/icons-vue'
import { dataagentApi } from '@/api/dataagent'

const loading = ref(false)
const topics = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const siteOptions = ref([])

const filters = reactive({
  website_id: '',
  external_user_id: '',
  visitor_id: '',
  keyword: ''
})

const drawerVisible = ref(false)
const drawerTitle = ref('')
const messages = ref([])
const messagesLoading = ref(false)

const buildParams = () => {
  const params = { page: page.value, page_size: pageSize.value }
  for (const key of ['website_id', 'external_user_id', 'visitor_id', 'keyword']) {
    const value = String(filters[key] || '').trim()
    if (value) params[key] = value
  }
  return params
}

const loadTopics = async () => {
  loading.value = true
  try {
    const payload = await dataagentApi.listWidgetTopics(buildParams())
    topics.value = Array.isArray(payload?.items) ? payload.items : []
    total.value = Number(payload?.total || 0)
  } catch (error) {
    if (!error?.__odwNotified) {
      ElMessage.error(error?.message || '加载 Widget 会话失败')
    }
  } finally {
    loading.value = false
  }
}

const loadSiteOptions = async () => {
  try {
    const payload = await dataagentApi.getSettings()
    const list = Array.isArray(payload?.widget_allowed_sites) ? payload.widget_allowed_sites : []
    siteOptions.value = list
      .map((site) => ({
        website_id: String(site?.website_id || '').trim(),
        label: String(site?.project_name || '').trim()
          ? `${String(site.project_name).trim()}（${String(site.website_id).trim()}）`
          : String(site?.website_id || '').trim()
      }))
      .filter((item) => item.website_id)
  } catch {
    siteOptions.value = []
  }
}

const applyFilters = () => {
  page.value = 1
  loadTopics()
}

const resetFilters = () => {
  filters.website_id = ''
  filters.external_user_id = ''
  filters.visitor_id = ''
  filters.keyword = ''
  applyFilters()
}

const onPageSizeChange = () => {
  page.value = 1
  loadTopics()
}

const reload = () => {
  loadSiteOptions()
  loadTopics()
}

const openMessages = async (row) => {
  drawerVisible.value = true
  drawerTitle.value = row.title || 'Widget 会话'
  messages.value = []
  messagesLoading.value = true
  try {
    const payload = await dataagentApi.getWidgetTopicMessages(row.topic_id, { page: 1, page_size: 200, order: 'asc' })
    messages.value = Array.isArray(payload?.items) ? payload.items : []
  } catch (error) {
    if (!error?.__odwNotified) {
      ElMessage.error(error?.message || '加载会话消息失败')
    }
  } finally {
    messagesLoading.value = false
  }
}

onMounted(() => {
  loadSiteOptions()
  loadTopics()
})
</script>

<style scoped>
.widget-conversations {
  color: #1f2937;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 18px 20px;
  border: 1px solid #cddceb;
  border-left: 4px solid #1f5f99;
  border-radius: 8px;
  background: linear-gradient(90deg, #f4f8fc 0%, #ffffff 72%);
}

.page-header-main {
  min-width: 0;
}

.page-kicker {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: #2c659b;
}

.page-title {
  margin: 7px 0 6px;
  font-size: 22px;
  font-weight: 700;
  color: #16324f;
}

.page-desc {
  margin: 0;
  max-width: 720px;
  color: #5b6b7c;
  font-size: 13px;
  line-height: 1.6;
}

.filter-bar {
  padding: 14px 16px 2px;
  border: 1px solid #e5eaf0;
  border-radius: 8px;
  background: #fff;
}

.filter-site {
  width: 220px;
}

.topic-table {
  width: 100%;
}

.muted {
  color: #9aa7b4;
}

.pager {
  display: flex;
  justify-content: flex-end;
}

.message-pane {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 120px;
}

.message-item {
  border: 1px solid #e5eaf0;
  border-radius: 8px;
  padding: 10px 12px;
  background: #fafcff;
}

.message-item.user {
  background: #f5f7fa;
}

.message-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.message-time {
  font-size: 12px;
  color: #9aa7b4;
}

.message-content {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
  color: #1f2937;
}

.empty-block {
  padding: 24px;
  text-align: center;
  color: #9aa7b4;
}

.empty-title {
  font-size: 14px;
}
</style>
