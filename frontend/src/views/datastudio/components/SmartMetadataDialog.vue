<template>
  <el-dialog v-model="visible" title="智能元数据" width="960px" append-to-body :close-on-click-modal="false">
    <p v-if="targetMissing" class="smd-blocked" data-test="smd-target-missing">
      对应的表已关闭，这些建议无法写回。请重新打开该表后再生成。
    </p>
    <p v-if="result && commentBlockedReason" class="smd-blocked" data-test="smd-comment-blocked">
      {{ commentBlockedReason }}。字段描述和表描述建议只供参考，不会写回；平台属性和新鲜度仍可采纳。
    </p>
    <el-tabs v-if="result" v-model="tab">
      <el-tab-pane label="字段描述" name="fields">
        <div class="smd-toolbar">
          <span class="smd-hint">
            默认情况，展示表内所有字段生成的描述，您也可以
            <el-checkbox v-model="onlyWeak">仅看描述为空/描述与名称相同的字段</el-checkbox>
          </span>
          <el-input v-model="keyword" size="small" clearable placeholder="请输入字段关键字" class="smd-search" />
        </div>

        <el-table :data="visibleFields" border size="small" max-height="420">
          <el-table-column width="110" align="center">
            <template #header>
              <el-checkbox
                :model-value="allSelected"
                :indeterminate="indeterminate"
                :disabled="!selectableVisible.length"
                @change="toggleAll"
              >
                是否采纳
              </el-checkbox>
            </template>
            <template #default="{ row, $index }">
              <span class="smd-select-cell">
                <el-checkbox v-model="selected[row.fieldName]" :disabled="!row.hasRecommendation || !!commentBlockedReason" />
                <span class="smd-idx">{{ $index + 1 }}</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="字段名" width="190">
            <template #default="{ row }">
              <el-input :model-value="row.fieldName" size="small" disabled />
            </template>
          </el-table-column>
          <el-table-column label="字段描述" width="230">
            <template #default="{ row }">
              <el-input :model-value="row.currentComment" type="textarea" autosize disabled placeholder="—" />
            </template>
          </el-table-column>
          <el-table-column label="生成推荐描述">
            <template #default="{ row }">
              <el-input
                v-if="row.hasRecommendation"
                v-model="row.editedComment"
                type="textarea"
                autosize
                class="smd-suggest-input"
              />
              <el-input v-else model-value="" type="textarea" :rows="2" disabled placeholder="已被采纳或暂无推荐" />
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="表信息" name="table">
        <div class="smd-tabledesc">
          <div class="smd-line">
            <span class="smd-label">表名</span>
            <el-input :model-value="result.tableName" size="small" disabled />
          </div>
          <div class="smd-line">
            <span class="smd-label">当前表描述</span>
            <el-input :model-value="result.table.currentComment" type="textarea" autosize disabled placeholder="—" />
          </div>
          <div class="smd-line">
            <span class="smd-label">
              <el-checkbox v-model="adoptTable" :disabled="!result.table.hasRecommendation || !!commentBlockedReason" />
              生成推荐描述
            </span>
            <el-input
              v-model="tableText"
              type="textarea"
              autosize
              class="smd-suggest-input"
              :disabled="!result.table.hasRecommendation"
              :placeholder="result.table.hasRecommendation ? '' : '已被采纳或暂无推荐'"
            />
          </div>
        </div>

        <el-table :data="result.attributes" border size="small" class="smd-attr-table">
          <el-table-column width="96" align="center">
            <template #header>是否采纳</template>
            <template #default="{ row }">
              <el-checkbox v-model="selectedAttrs[row.key]" :disabled="!row.hasRecommendation" />
            </template>
          </el-table-column>
          <el-table-column prop="label" label="属性" width="90" />
          <el-table-column label="当前值" width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.currentValue || '-' }}</template>
          </el-table-column>
          <el-table-column label="生成推荐值" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.hasRecommendation">{{ row.suggestedValue }}</span>
              <span v-else class="smd-muted">已被采纳或暂无推荐</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="smd-attr-hint">推荐值只会取自平台已有的分层与业务域/数据域编码，清单外的取值会被丢弃。</div>

        <div v-if="result.freshness" class="smd-fresh">
          <div class="smd-fresh-title">数据新鲜度</div>
          <div class="smd-line">
            <span class="smd-label">当前契约</span>
            <el-input :model-value="result.freshness.currentText" size="small" disabled />
          </div>
          <div class="smd-line">
            <span class="smd-label">
              <el-checkbox v-model="adoptFreshness" :disabled="!result.freshness.hasRecommendation" />
              生成推荐契约
            </span>
            <el-input
              :model-value="result.freshness.hasRecommendation ? result.freshness.suggestedText : ''"
              size="small"
              disabled
              :placeholder="result.freshness.hasRecommendation ? '' : '暂无推荐或与当前一致'"
            />
          </div>
          <div class="smd-attr-hint">推荐的时间列只会取自该表真实字段，采纳后按 T-1 语义（预警/过期各 1 天）写入新鲜度契约。</div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <div class="smd-footer">
        <span class="smd-count">已选 {{ totalSelected }} 项</span>
        <span>
          <el-button :loading="metadataGenerating" @click="regenerate">重新生成</el-button>
          <el-button @click="visible = false">取消</el-button>
          <el-button type="primary" :loading="metadataAdopting" :disabled="totalSelected === 0 || targetMissing" @click="onAdopt">
            采纳
          </el-button>
        </span>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, inject, reactive, ref, watch } from 'vue'
import { isWeakDescription } from '../metadataGeneration'

// 「智能元数据」复核弹窗：字段描述 / 表名与表描述两个 tab，勾选后批量采纳直接写回。
const ctx = inject('dataStudioCtx', null)
if (!ctx) {
  throw new Error('SmartMetadataDialog 需要 dataStudioCtx')
}

const {
  metadataResult,
  metadataDialogVisible,
  metadataAdopting,
  metadataGenerating,
  adoptMetadata,
  generateMetadata,
  activeTab,
  tabStates,
  structureEditBlockedReason
} = ctx

const result = computed(() => metadataResult.value)
const currentTabId = () => String(result.value?.tabId || activeTab.value || '')
// 生成期间关闭了原 tab 时，建议已无处写回
const targetMissing = computed(() => !!result.value && !tabStates[currentTabId()]?.table)
// 表描述和字段描述会改物理注释；不支持时只采纳平台属性和新鲜度
const commentBlockedReason = computed(() => structureEditBlockedReason(tabStates[currentTabId()]?.table))
const visible = computed({
  get: () => metadataDialogVisible.value,
  set: (value) => {
    metadataDialogVisible.value = value
  }
})

const tab = ref('fields')
const onlyWeak = ref(false)
const keyword = ref('')
const selected = reactive({})
const selectedAttrs = reactive({})
const adoptTable = ref(false)
const adoptFreshness = ref(false)
const tableText = ref('')
const localFields = ref([])

watch(
  () => metadataResult.value,
  (value) => {
    Object.keys(selected).forEach((key) => delete selected[key])
    Object.keys(selectedAttrs).forEach((key) => delete selectedAttrs[key])
    ;(value?.attributes || []).forEach((item) => {
      selectedAttrs[item.key] = item.hasRecommendation
    })
    localFields.value = (value?.fields || []).map((field) => ({ ...field, editedComment: field.suggestedComment }))
    const commentsAdoptable = !commentBlockedReason.value
    localFields.value.forEach((field) => {
      selected[field.fieldName] = field.hasRecommendation && commentsAdoptable
    })
    adoptTable.value = !!value?.table?.hasRecommendation && commentsAdoptable
    tableText.value = value?.table?.suggestedComment || ''
    adoptFreshness.value = !!value?.freshness?.hasRecommendation
    tab.value = 'fields'
  },
  { immediate: true }
)

const visibleFields = computed(() =>
  localFields.value.filter((field) => {
    if (onlyWeak.value && !isWeakDescription(field.fieldName, field.currentComment)) return false
    if (keyword.value && !field.fieldName.toLowerCase().includes(keyword.value.trim().toLowerCase())) return false
    return true
  })
)

const selectableVisible = computed(() =>
  commentBlockedReason.value ? [] : visibleFields.value.filter((field) => field.hasRecommendation)
)

const allSelected = computed(
  () => selectableVisible.value.length > 0 && selectableVisible.value.every((field) => selected[field.fieldName])
)

const indeterminate = computed(() => {
  const count = selectableVisible.value.filter((field) => selected[field.fieldName]).length
  return count > 0 && count < selectableVisible.value.length
})

const toggleAll = (value) => {
  selectableVisible.value.forEach((field) => {
    selected[field.fieldName] = !!value
  })
}

const selectedFields = computed(() =>
  commentBlockedReason.value
    ? []
    : localFields.value.filter((field) => field.hasRecommendation && selected[field.fieldName])
)

const adoptTableActive = computed(
  () => adoptTable.value && !!result.value?.table?.hasRecommendation && !commentBlockedReason.value
)

const selectedAttributes = computed(() =>
  (result.value?.attributes || []).filter((item) => item.hasRecommendation && selectedAttrs[item.key])
)

const adoptFreshnessActive = computed(
  () => adoptFreshness.value && !!result.value?.freshness?.hasRecommendation
)

const totalSelected = computed(
  () =>
    selectedFields.value.length +
    selectedAttributes.value.length +
    (adoptTableActive.value ? 1 : 0) +
    (adoptFreshnessActive.value ? 1 : 0)
)


const onAdopt = () => {
  adoptMetadata(currentTabId(), {
    table: adoptTableActive.value ? { text: tableText.value } : null,
    attributes: selectedAttributes.value.map((item) => ({ key: item.key, value: item.suggestedValue })),
    freshness: adoptFreshnessActive.value ? result.value.freshness.suggested : null,
    fields: selectedFields.value.map((field) => ({ fieldName: field.fieldName, text: field.editedComment }))
  })
}

const regenerate = () => generateMetadata(currentTabId(), { force: true })
</script>

<style scoped>
.smd-blocked {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.smd-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.smd-hint {
  color: #5d7491;
  font-size: 13px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.smd-search {
  width: 220px;
  flex: none;
}

.smd-select-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.smd-idx {
  color: #8298b2;
}

.smd-suggest-input :deep(.el-textarea__inner) {
  border-color: #c4b5fd;
}

.smd-tabledesc {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.smd-line {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.smd-label {
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.smd-fresh {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.smd-fresh-title {
  font-weight: 600;
}

.smd-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.smd-count {
  color: #5d7491;
  font-size: 13px;
}
</style>
