import http from './index'

export function fetchModels(provider) {
  return http.get('/ai/models', { params: { provider }, skipGlobalError: true })
}

export function previewModels(data) {
  return http.post('/ai/models/preview', data, { skipGlobalError: true })
}

export function chat(data) {
  return http.post('/ai/chat', data)
}

export function listVaults() {
  return http.get('/ai/vault')
}

export function getVault(provider) {
  return http.get(`/ai/vault/${provider}`)
}

export function createVault(data) {
  return http.post('/ai/vault', data)
}

export function updateVault(id, data) {
  return http.put(`/ai/vault/${id}`, data)
}

export function deleteVault(id) {
  return http.delete(`/ai/vault/${id}`)
}

/**
 * 当前用户的模型额度熔断状态。
 *
 * 后端在额度类错误（402/401/key 失效/model_not_found）时立刻熔断主动关怀，
 * 任意一次成功即自动恢复；前端只负责把「为什么停」说清楚，不提供开关。
 */
export function getModelQuota() {
  return http.get('/model/quota')
}
