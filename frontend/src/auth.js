import { reactive } from 'vue'

const KEY = 'toys-video-auth'

function load() {
  try {
    return JSON.parse(localStorage.getItem(KEY)) || null
  } catch {
    return null
  }
}

export const auth = reactive({
  user: load(),
  token() {
    return this.user?.token || ''
  },
  set(u) {
    this.user = u
    localStorage.setItem(KEY, JSON.stringify(u))
  },
  logout() {
    this.user = null
    localStorage.removeItem(KEY)
  },
  isAdmin() {
    return this.user?.role === 'ADMIN'
  }
})
