import axios from 'axios'
const client = axios.create({ baseURL: import.meta.env.VITE_ADMIN_API_ORIGIN || 'http://127.0.0.1:8080', timeout: 15000 })
client.interceptors.request.use(config => { const token = localStorage.getItem('lianyu-admin-token'); if (token) config.headers['lianyu-token'] = token; return config })
export async function adminLogin(payload) { const { data } = await client.post('/api/admin/v1/auth/login', payload); return data }
export async function adminLogout() { await client.post('/api/admin/v1/auth/logout') }
export async function getOverview() { const { data } = await client.get('/api/admin/v1/overview'); return data }
export async function fetchAdminList(endpoint) { const { data } = await client.get(endpoint); return data?.data || [] }
export async function createRelease(payload) { return client.post('/api/admin/v1/releases', payload) }
