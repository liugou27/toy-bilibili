import axios from 'axios'
import { ElMessage } from 'element-plus'
import { auth } from './auth.js'
import router from './router.js'

const http = axios.create({ baseURL: '/api', timeout: 600000 })

http.interceptors.request.use((config) => {
  const token = auth.token()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body.code === 'number') {
      if (body.code === 0) return body.data
      if (body.code === 1001 && auth.user) {
        auth.logout()
        ElMessage.error(body.message || '登录已过期')
        router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
        return Promise.reject(new Error(body.message))
      }
      ElMessage.error(body.message || `请求失败(${body.code})`)
      return Promise.reject(new Error(body.message))
    }
    return body
  },
  (err) => {
    const status = err.response?.status
    const body = err.response?.data
    if (status === 401) {
      auth.logout()
      ElMessage.error('登录已过期,请重新登录')
      router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    } else if (status === 403) {
      ElMessage.error('无权访问')
    } else if (status === 429) {
      ElMessage.error('请求太频繁,请稍后再试')
    } else if (status >= 500) {
      ElMessage.error(body?.message || '服务暂时不可用,请稍后重试')
    } else {
      ElMessage.error(body?.message || err.message)
    }
    return Promise.reject(err)
  }
)

export default http
