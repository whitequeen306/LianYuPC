import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listVaults, createVault, updateVault, deleteVault, fetchModels, previewModels } from '@/api/ai'

export const useProvidersStore = defineStore('providers', () => {
  const vaults = ref([])
  const models = ref({})  // { provider: [{ id, name }] }
  const loading = ref(false)
  const modelsLoading = ref(false)

  async function fetchVaults() {
    loading.value = true
    try {
      vaults.value = await listVaults() || []
    } finally {
      loading.value = false
    }
  }

  async function addVault(data) {
    const entry = await createVault(data)
    vaults.value.push(entry)
    return entry
  }

  async function editVault(id, data) {
    const entry = await updateVault(id, data)
    const idx = vaults.value.findIndex(v => v.id === id)
    if (idx >= 0) vaults.value[idx] = entry
    return entry
  }

  async function removeVault(id) {
    await deleteVault(id)
    vaults.value = vaults.value.filter(v => v.id !== id)
  }

  async function fetchModelsFor(provider) {
    modelsLoading.value = true
    try {
      const list = await fetchModels(provider)
      models.value[provider] = list || []
      return list
    } finally {
      modelsLoading.value = false
    }
  }

  /** 未保存配置时预览模型列表（添加对话框 / 编辑时改了密钥） */
  async function previewModelsFor(baseUrl, apiKey) {
    return await previewModels({ baseUrl, apiKey })
  }

  return {
    vaults, models, loading, modelsLoading,
    fetchVaults, addVault, editVault, removeVault,
    fetchModelsFor, previewModelsFor
  }
})
