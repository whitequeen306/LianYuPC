<script setup>
defineProps({ columns: { type: Array, required: true }, rows: { type: Array, default: () => [] }, actions: { type: Array, default: () => [] } })
const emit = defineEmits(['action'])
</script>
<template><div class="data-table"><div class="data-head"><span v-for="column in columns" :key="column.key">{{ column.label }}</span><span v-if="actions.length">操作</span></div><div v-if="!rows.length" class="data-empty">暂无记录</div><div v-for="(row,index) in rows" :key="row.id ?? index" class="data-row"><span v-for="column in columns" :key="column.key">{{ row[column.key] ?? '—' }}</span><span v-if="actions.length" class="row-actions"><button v-for="action in actions" :key="action.key" class="table-action" :disabled="action.disabled?.(row)" @click="emit('action', { action: action.key, row })">{{ action.label }}</button></span></div></div></template>
