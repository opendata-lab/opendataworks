<template>
  <el-dialog
    v-model="inspectDialogVisible"
    :title="`${inspectTarget ? getMemberDisplayName(inspectTarget) : ''} · 账号实际权限`"
    width="680px"
    append-to-body
  >
    <div v-loading="inspectState.loading">
      <div class="dialog-source-strip">
        <span
          >数据源：<strong>{{ activeClusterName }}</strong></span
        >
        <span
          >绑定账号：<code>{{
            inspectState.data?.username || inspectTarget?.account?.username || '未绑定'
          }}</code></span
        >
        <span v-if="inspectState.data?.nativeIdentity">
          原生身份：<code>{{ inspectState.data.nativeIdentity }}</code>
        </span>
      </div>

      <el-alert
        v-if="inspectState.error"
        :title="inspectState.error"
        type="error"
        :closable="false"
        show-icon
        class="top-alert"
      />

      <p
        v-if="inspectState.data?.notice"
        class="state-note warning-note"
        data-testid="inspect-notice"
      >
        {{ inspectState.data.notice }}
      </p>

      <el-table
        v-if="!inspectState.error"
        :data="normalizedInspectGrants"
        border
        empty-text="未回读到原生权限或尚未绑定账号"
        style="width: 100%"
      >
        <el-table-column prop="scope" label="资源范围 / 对象" min-width="220" />
        <el-table-column prop="actionsText" label="原生权限" min-width="200" />
        <el-table-column prop="sourceText" label="来源" min-width="140" />
      </el-table>
    </div>
    <template #footer>
      <el-button @click="inspectDialogVisible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { toRefs } from 'vue'

const props = defineProps({ model: { type: Object, required: true } })
// The feature composable owns these drafts and operations; this view only renders its contract.
const {
  activeClusterName,
  getMemberDisplayName,
  inspectDialogVisible,
  inspectTarget,
  inspectState,
  normalizedInspectGrants,
} = toRefs(props.model)
</script>

<style scoped lang="scss">
@use './permission-shared';
</style>
