<script setup>
defineProps({ columns: { type: Array, required: true }, rows: { type: Array, default: () => [] } })
</script>
<template><div class="data-table"><div class="data-head"><span v-for="column in columns" :key="column.key">{{ column.label }}</span></div><div v-if="!rows.length" class="data-empty">暂无记录</div><div v-for="(row,index) in rows" :key="row.id ?? index" class="data-row"><span v-for="column in columns" :key="column.key">{{ row[column.key] ?? '—' }}</span></div></div></template>
