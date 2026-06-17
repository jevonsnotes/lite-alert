<script setup lang="ts">
/**
 * Recursive JSON Schema field row.
 *
 * Each row ends with [+] [×]:
 * - [+] only for object/array — adds a nested child
 * - [×] removes the current field
 *
 * For scalar types (string/number/boolean/integer) there is no [+] because
 * scalars cannot have children. The "子"/"元素" label cell shows a tooltip
 * hinting the user to switch the type to "object" first.
 */
import { computed, watch } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'

export type SchemaField = {
  name: string
  type: 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array'
  required: boolean
  description?: string
  enums?: string
  children?: SchemaField[]
  items?: SchemaField
}

const props = defineProps<{
  modelValue: SchemaField
  level: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: SchemaField): void
  (e: 'remove'): void
}>()

const local = computed({
  get: () => props.modelValue,
  set: (v: SchemaField) => emit('update:modelValue', v)
})

const SCALAR_TYPES = ['string', 'number', 'integer', 'boolean']

function setField<K extends keyof SchemaField>(key: K, value: SchemaField[K]) {
  emit('update:modelValue', { ...local.value, [key]: value })
}

watch(() => local.value.type, (next, prev) => {
  if (next === prev) return
  if (next === 'object' && !local.value.children) {
    setField('children', [])
  }
  if (next === 'array' && !local.value.items) {
    setField('items', { name: '(item)', type: 'string', required: false })
  }
})

function addChild() {
  const current = local.value.children ?? []
  setField('children', [...current, { name: '', type: 'string', required: false }])
}
function updateChild(i: number, v: SchemaField) {
  const next = [...(local.value.children ?? [])]
  next[i] = v
  setField('children', next)
}
function removeChild(i: number) {
  const next = [...(local.value.children ?? [])]
  next.splice(i, 1)
  setField('children', next)
}
function updateItems(v: SchemaField) {
  setField('items', v)
}

const indentStyle = computed(() => ({
  borderLeft: props.level === 0 ? 'none' : '2px solid var(--la-border)',
  paddingLeft: props.level === 0 ? '0' : '12px',
  marginLeft: props.level === 0 ? '0' : '4px'
}))

const isContainer = computed(() =>
  local.value.type === 'object' || local.value.type === 'array')

/** [+] adds a child to object/array; disabled for scalar types */
function onAddNested() {
  if (local.value.type === 'object') {
    addChild()
  } else if (local.value.type === 'array') {
    if (!local.value.items) updateItems({ name: '(item)', type: 'string', required: false })
  }
}
</script>

<template>
  <div class="schema-field" :style="indentStyle">
    <div class="row">
      <el-input
        :model-value="local.name"
        :disabled="disabled"
        size="small"
        placeholder="字段名"
        class="col-name"
        @update:model-value="(v: string) => setField('name', v)"
      />
      <el-select
        :model-value="local.type"
        :disabled="disabled"
        size="small"
        class="col-type"
        @update:model-value="(v: any) => setField('type', v)"
      >
        <el-option label="string" value="string" />
        <el-option label="number" value="number" />
        <el-option label="integer" value="integer" />
        <el-option label="boolean" value="boolean" />
        <el-option label="object" value="object" />
        <el-option label="array" value="array" />
      </el-select>
      <el-checkbox
        :model-value="local.required"
        :disabled="disabled"
        class="col-req"
        @update:model-value="(v: any) => setField('required', !!v)"
      >必填</el-checkbox>
      <el-input
        :model-value="local.description"
        :disabled="disabled"
        size="small"
        placeholder="描述"
        class="col-desc"
        @update:model-value="(v: string) => setField('description', v)"
      />
      <el-input
        v-if="SCALAR_TYPES.includes(local.type)"
        :model-value="local.enums"
        :disabled="disabled"
        size="small"
        placeholder="枚举,逗号"
        class="col-enum"
        @update:model-value="(v: string) => setField('enums', v)"
      />
      <span v-else class="col-label">{{ local.type === 'object' ? '子' : '元素' }}</span>
      <el-button
        :disabled="disabled || !isContainer"
        link type="primary" size="small" :icon="Plus"
        class="btn-add"
        :class="{ invisible: !isContainer }"
        :title="local.type === 'object' ? '添加子字段' : '编辑数组元素结构'"
        @click="onAddNested"
      />
      <el-button
        :disabled="disabled"
        link type="danger" size="small" :icon="Delete"
        class="btn-del"
        @click="emit('remove')"
      />
    </div>

    <!-- Object children -->
    <div v-if="local.type === 'object'" class="children-block">
      <SchemaFieldRow
        v-for="(child, i) in local.children ?? []"
        :key="i"
        :model-value="child"
        :level="level + 1"
        :disabled="disabled"
        @update:model-value="(v: SchemaField) => updateChild(i, v)"
        @remove="removeChild(i)"
      />
    </div>

    <!-- Array item schema -->
    <div v-else-if="local.type === 'array' && local.items" class="children-block">
      <SchemaFieldRow
        :model-value="local.items"
        :level="level + 1"
        :disabled="disabled"
        @update:model-value="updateItems"
        @remove="() => setField('items', { name: '(item)', type: 'string', required: false })"
      />
      <div class="muted-inline" style="margin-left: 16px">
        ↑ 数组元素的结构。元素也可以是 object 以构成更复杂的嵌套。
      </div>
    </div>
  </div>
</template>

<script lang="ts">
export default { name: 'SchemaFieldRow' }
</script>

<style scoped>
.schema-field { margin-bottom: 4px; }
.row {
  display: grid;
  grid-template-columns: 120px 85px 60px 1fr 130px 26px 26px;
  gap: 6px;
  align-items: center;
}
.col-name, .col-type, .col-desc, .col-enum { width: 100%; }
.col-req { white-space: nowrap; }
.col-label {
  text-align: center;
  font-size: 11px;
  color: var(--la-fg-muted);
  cursor: default;
}
.col-label.clickable { color: var(--la-fg); }
.col-label.scalar { text-decoration: underline dotted var(--la-fg-muted); cursor: help; }
.btn-add, .btn-del {
  padding: 2px;
  min-width: auto;
}
.btn-add.invisible { visibility: hidden; pointer-events: none; }
.children-block {
  margin: 6px 0 8px 0;
}
.muted-inline { color: var(--la-fg-muted); font-size: 12px; }
</style>
