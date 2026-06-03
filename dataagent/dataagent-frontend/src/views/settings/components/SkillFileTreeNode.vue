<template>
  <div class="tree-node">
    <button
      type="button"
      class="tree-node__button"
      :class="{ 'is-selected': node.type === 'file' && node.documentId === selectedDocumentId }"
      :style="{ paddingLeft: `${12 + level * 18}px` }"
      @click="handleClick"
    >
      <el-icon class="tree-node__toggle" :class="{ 'is-expanded': expanded }">
        <ArrowRight v-if="node.type === 'folder'" />
      </el-icon>
      <el-icon class="tree-node__icon">
        <FolderOpened v-if="node.type === 'folder'" />
        <Document v-else />
      </el-icon>
      <span class="tree-node__label">{{ node.label }}</span>
    </button>

    <div v-if="node.type === 'folder' && expanded" class="tree-node__children">
      <SkillFileTreeNode
        v-for="child in node.children || []"
        :key="child.key"
        :node="child"
        :level="level + 1"
        :selected-document-id="selectedDocumentId"
        @select="$emit('select', $event)"
      />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ArrowRight, Document, FolderOpened } from '@element-plus/icons-vue'

defineOptions({
  name: 'SkillFileTreeNode'
})

const props = defineProps({
  node: {
    type: Object,
    required: true
  },
  level: {
    type: Number,
    default: 0
  },
  selectedDocumentId: {
    type: Number,
    default: null
  }
})

const emit = defineEmits(['select'])

const expanded = ref(true)

const handleClick = () => {
  if (props.node.type === 'folder') {
    expanded.value = !expanded.value
    return
  }
  if (props.node.documentId) {
    emit('select', props.node.documentId)
  }
}
</script>

<style scoped>
.tree-node {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tree-node__button {
  width: 100%;
  border: 0;
  background: transparent;
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  padding: 7px 10px;
  border-radius: 8px;
  color: #475569;
  cursor: pointer;
  text-align: left;
  transition: background-color 0.2s ease, color 0.2s ease;
}

.tree-node__button:hover {
  background: #eff6ff;
  color: #1d4ed8;
}

.tree-node__button.is-selected {
  background: #dbeafe;
  color: #1d4ed8;
  font-weight: 600;
}

.tree-node__toggle,
.tree-node__icon {
  color: inherit;
  flex-shrink: 0;
}

.tree-node__toggle {
  transition: transform 0.2s ease;
}

.tree-node__toggle.is-expanded {
  transform: rotate(90deg);
}

.tree-node__label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-node__children {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
