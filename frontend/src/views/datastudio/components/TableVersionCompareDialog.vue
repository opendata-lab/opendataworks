<template>
  <el-dialog
    :model-value="modelValue"
    title="版本对比"
    width="760px"
    append-to-body
    destroy-on-close
    @update:model-value="(val) => emit('update:modelValue', val)"
  >
    <div v-loading="loading" class="compare-body">
      <template v-if="result">
        <div class="compare-summary">
          <span class="compare-versions">v{{ result.leftVersionNo }} → v{{ result.rightVersionNo }}</span>
          <el-tag v-if="result.changed" type="warning" size="small">有变化</el-tag>
          <el-tag v-else type="info" size="small">无变化</el-tag>
          <el-tag v-if="result.summary?.attributeChangedCount" size="small">表属性 {{ result.summary.attributeChangedCount }}</el-tag>
          <el-tag v-if="result.summary?.columnsAddedCount" type="success" size="small">新增字段 {{ result.summary.columnsAddedCount }}</el-tag>
          <el-tag v-if="result.summary?.columnsRemovedCount" type="danger" size="small">删除字段 {{ result.summary.columnsRemovedCount }}</el-tag>
          <el-tag v-if="result.summary?.columnsModifiedCount" type="warning" size="small">修改字段 {{ result.summary.columnsModifiedCount }}</el-tag>
        </div>

        <el-tabs v-model="activeTab" class="compare-tabs">
          <el-tab-pane name="structured" label="结构化对比">
            <el-scrollbar max-height="420px">
              <template v-if="result.changed">
                <template v-if="result.tableAttributeChanges?.length">
                  <div class="compare-section-title">表属性变更</div>
                  <el-table :data="result.tableAttributeChanges" border size="small">
                    <el-table-column prop="name" label="属性" min-width="130" />
                    <el-table-column label="旧值" min-width="180">
                      <template #default="{ row }">
                        <span class="value-old">{{ displayValue(row.oldValue) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="新值" min-width="180">
                      <template #default="{ row }">
                        <span class="value-new">{{ displayValue(row.newValue) }}</span>
                      </template>
                    </el-table-column>
                  </el-table>
                </template>

                <template v-if="result.columnsAdded?.length">
                  <div class="compare-section-title">新增字段</div>
                  <div class="tag-group">
                    <el-tag v-for="name in result.columnsAdded" :key="`add-${name}`" type="success" size="small">
                      {{ name }}
                    </el-tag>
                  </div>
                </template>

                <template v-if="result.columnsRemoved?.length">
                  <div class="compare-section-title">删除字段</div>
                  <div class="tag-group">
                    <el-tag v-for="name in result.columnsRemoved" :key="`del-${name}`" type="danger" size="small">
                      {{ name }}
                    </el-tag>
                  </div>
                </template>

                <template v-if="result.columnsModified?.length">
                  <div class="compare-section-title">修改字段</div>
                  <div v-for="column in result.columnsModified" :key="`mod-${column.fieldName}`" class="modified-column">
                    <div class="modified-column-name">{{ column.fieldName }}</div>
                    <el-table :data="column.changes" border size="small">
                      <el-table-column prop="name" label="属性" min-width="120" />
                      <el-table-column label="旧值" min-width="170">
                        <template #default="{ row }">
                          <span class="value-old">{{ displayValue(row.oldValue) }}</span>
                        </template>
                      </el-table-column>
                      <el-table-column label="新值" min-width="170">
                        <template #default="{ row }">
                          <span class="value-new">{{ displayValue(row.newValue) }}</span>
                        </template>
                      </el-table-column>
                    </el-table>
                  </div>
                </template>
              </template>
              <el-empty v-else description="两个版本的元数据完全一致" :image-size="60" />
            </el-scrollbar>
          </el-tab-pane>

          <el-tab-pane name="raw" label="原始 Diff">
            <el-scrollbar max-height="420px">
              <pre class="raw-diff"><span
                v-for="(line, index) in rawDiffLines"
                :key="index"
                :class="line.cls"
              >{{ line.text }}</span></pre>
            </el-scrollbar>
          </el-tab-pane>
        </el-tabs>
      </template>
      <el-empty v-else-if="!loading" :description="error || '暂无对比结果'" :image-size="60" />
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { tableApi } from '@/api/table'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  tableId: {
    type: [Number, String],
    default: null
  },
  leftVersionId: {
    type: [Number, String],
    default: null
  },
  rightVersionId: {
    type: [Number, String],
    default: null
  }
})

const emit = defineEmits(['update:modelValue'])

const loading = ref(false)
const result = ref(null)
const error = ref('')
const activeTab = ref('structured')

const rawDiffLines = computed(() => {
  const raw = result.value?.rawDiff || ''
  return raw.split('\n').map((text) => {
    let cls = 'line-context'
    if (text.startsWith('+')) cls = 'line-added'
    else if (text.startsWith('-')) cls = 'line-removed'
    else if (text.startsWith('@@')) cls = 'line-hunk'
    return { text, cls }
  })
})

const displayValue = (value) => (value === null || value === undefined || value === '' ? '（空）' : value)

const loadCompare = async () => {
  if (!props.tableId || !props.leftVersionId || !props.rightVersionId) return
  loading.value = true
  result.value = null
  error.value = ''
  activeTab.value = 'structured'
  try {
    result.value = await tableApi.compareTableVersions(props.tableId, {
      leftVersionId: props.leftVersionId,
      rightVersionId: props.rightVersionId
    })
  } catch (e) {
    error.value = e?.message || '对比失败'
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) loadCompare()
  }
)
</script>

<style scoped>
.compare-body {
  min-height: 200px;
}

.compare-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.compare-versions {
  font-weight: 600;
}

.compare-section-title {
  font-weight: 600;
  font-size: 13px;
  margin: 14px 0 8px;
}

.compare-section-title:first-child {
  margin-top: 0;
}

.tag-group {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.modified-column {
  margin-bottom: 12px;
}

.modified-column-name {
  font-family: monospace;
  font-size: 13px;
  margin-bottom: 6px;
}

.value-old {
  color: #c45656;
  text-decoration: line-through;
}

.value-new {
  color: #529b2e;
}

.raw-diff {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.raw-diff span {
  display: block;
  white-space: pre-wrap;
  word-break: break-all;
}

.line-added {
  background: #f0f9eb;
  color: #529b2e;
}

.line-removed {
  background: #fef0f0;
  color: #c45656;
}

.line-hunk {
  color: #909399;
}

.line-context {
  color: #606266;
}
</style>
