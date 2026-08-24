<script setup>
import { onMounted, ref, watch } from 'vue'
import DataTable from '../components/DataTable.vue'
import { fetchAdminList, createRelease } from '../api'
const props = defineProps({ kind: { type: String, required: true } })
const rows=ref([]),loading=ref(false),error=ref(''),showCreate=ref(false),draft=ref({version:'',channel:'stable',notes:''})
const configs={users:{endpoint:'/api/admin/v1/users',columns:[['id','ID'],['username','账号'],['nickname','昵称'],['created_at','创建时间']]},releases:{endpoint:'/api/admin/v1/releases',columns:[['version','版本'],['channel','渠道'],['state','状态'],['published_at','发布时间']]},admins:{endpoint:'/api/admin/v1/admins',columns:[['id','ID'],['username','账号'],['display_name','名称'],['status','状态'],['totp_enabled','2FA']]},audit:{endpoint:'/api/admin/v1/audit',columns:[['action_key','动作'],['target_id','目标'],['result','结果'],['trace_id','Trace ID'],['created_at','时间']]}}
const configs2={announcements:{endpoint:'/api/admin/v1/announcements',columns:[['id','ID'],['title','标题'],['state','状态'],['published_at','发布时间']]}}; const meta=()=>configs2[props.kind]||configs[props.kind]||configs.users; const columns=()=>meta().columns.map(([key,label])=>({key,label}))
async function load(){loading.value=true;error.value='';try{rows.value=await fetchAdminList(meta().endpoint)}catch(e){error.value=e?.response?.data?.message||'读取失败'}finally{loading.value=false}}
async function save(){await createRelease(draft.value);showCreate.value=false;await load()}
watch(()=>props.kind,load);onMounted(load)
</script>
<template><section class="management-page"><div class="section-title"><div><h3>数据列表</h3><span>{{loading?'正在读取…':`${rows.length} 条记录`}}</span></div><button v-if="kind==='releases'" class="primary-button small" @click="showCreate=true">新建版本</button></div><div v-if="error" class="form-error">{{error}}</div><DataTable :columns="columns()" :rows="rows"/><div v-if="showCreate" class="dialog-backdrop" @click.self="showCreate=false"><form class="dialog-panel" @submit.prevent="save"><h3>新建版本草稿</h3><label>版本号<input v-model="draft.version" placeholder="v0.2.364" required></label><label>渠道<select v-model="draft.channel"><option>stable</option><option>beta</option></select></label><label>发布说明<textarea v-model="draft.notes" rows="4"></textarea></label><div class="dialog-actions"><button type="button" class="logout" @click="showCreate=false">取消</button><button class="primary-button small">保存草稿</button></div></form></div></section></template>
