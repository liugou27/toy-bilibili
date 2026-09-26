<template>
  <el-container class="app-shell">
    <header class="nav">
      <div class="nav-inner">
        <a class="nav-logo" href="/" @click.prevent="$router.push('/')">▶ toys-video</a>
        <nav class="nav-links">
          <a href="/" @click.prevent="$router.push('/')">发现</a>
          <el-dropdown v-if="channels.length" trigger="click" @command="onChannelCommand">
            <span class="nav-channel" :class="{ 'is-active': isChannelRoute }">
              分区
              <svg class="nav-caret" viewBox="0 0 10 6" width="10" height="6" aria-hidden="true">
                <path d="M1 1l4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="c in channels"
                  :key="c.key"
                  :command="c.key"
                  :class="{ 'is-current': c.key === route.params.key }"
                >
                  <span class="channel-item-name">{{ c.name }}</span>
                  <span class="channel-item-count">{{ fmtCount(c.count) }}</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <a v-if="auth.user" href="/upload" @click.prevent="$router.push('/upload')">投稿</a>
          <a v-if="auth.user" href="/my" @click.prevent="$router.push('/my')">我的空间</a>
          <a v-if="auth.isAdmin()" href="/admin/review" @click.prevent="$router.push('/admin/review')">审核后台</a>
        </nav>
        <div class="nav-actions">
          <template v-if="auth.user">
            <el-dropdown @command="onUserCommand" trigger="click">
              <span class="nav-user">
                <span class="avatar">{{ displayName.slice(0, 1).toUpperCase() }}</span>
                {{ displayName }}
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="logout">退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <template v-else>
            <a class="nav-auth" href="/login" @click.prevent="$router.push('/login')">登录</a>
            <a class="nav-cta" href="/register" @click.prevent="$router.push('/register')">注册</a>
          </template>
        </div>
      </div>
    </header>
    <main class="app-main">
      <router-view v-slot="{ Component }">
        <transition name="route-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
    <footer class="app-footer">toys-video · 微服务视频平台学习项目</footer>
  </el-container>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { auth } from './auth.js'
import { useRoute, useRouter } from 'vue-router'
import http from './api.js'

const route = useRoute()
const router = useRouter()

// 分区入口:接口失败时整个「分区」下拉隐藏,不影响其余导航
const channels = ref([])
const isChannelRoute = computed(() => route.path.startsWith('/channel'))

onMounted(async () => {
  try {
    const list = await http.get('/videos/categories')
    channels.value = [...(list || [])].sort((a, b) => (b.count || 0) - (a.count || 0))
  } catch {
    channels.value = []
  }
})

function onChannelCommand(key) {
  router.push(`/channel/${key}`)
}

function onUserCommand(cmd) {
  if (cmd === 'logout') {
    auth.logout()
    router.push('/')
  }
}

function fmtCount(n) {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万`
  return String(n || 0)
}

// 展示名:nickname 优先,未设置时回退用户名(本地缓存的旧登录态可能无 nickname)
const displayName = computed(() => auth.user?.nickname || auth.user?.username || '')
</script>

<style>
.app-shell { min-height: 100vh; display: flex; flex-direction: column; }

/* 毛玻璃导航 */
.nav {
  position: sticky; top: 0; z-index: 100;
  background: rgba(251, 251, 253, 0.72);
  backdrop-filter: saturate(180%) blur(20px);
  -webkit-backdrop-filter: saturate(180%) blur(20px);
  border-bottom: 1px solid var(--hairline);
}
.nav-inner {
  max-width: 1120px; margin: 0 auto; padding: 0 24px;
  height: 52px; display: flex; align-items: center; gap: 32px;
}
.nav-logo { font-weight: 700; font-size: 18px; color: var(--text); cursor: pointer; letter-spacing: -0.02em; }
.nav-links { display: flex; gap: 28px; align-items: center; }
.nav-links a { color: var(--text-secondary); font-size: 14px; transition: color 0.3s var(--ease); }
.nav-links a:hover { color: var(--text); text-decoration: none; }
.nav-channel {
  display: inline-flex; align-items: center; gap: 5px;
  color: var(--text-secondary); font-size: 14px; outline: none;
  transition: color 0.3s var(--ease);
}
.nav-channel:hover, .nav-channel.is-active { color: var(--text); }
.nav-caret { margin-top: 1px; opacity: 0.6; }
.channel-item-name { margin-right: 12px; }
.channel-item-count { font-size: 12px; color: var(--text-tertiary); }
.el-dropdown-menu__item.is-current .channel-item-name { color: var(--accent); font-weight: 600; }
.nav-actions { margin-left: auto; display: flex; align-items: center; gap: 20px; }
.nav-auth { color: var(--text-secondary); font-size: 14px; }
.nav-cta {
  background: var(--accent); color: #fff; font-size: 14px;
  padding: 6px 16px; border-radius: var(--radius-button); transition: background 0.3s var(--ease);
}
.nav-cta:hover { background: var(--accent-hover); text-decoration: none; }
.nav-user { display: flex; align-items: center; gap: 8px; cursor: pointer; font-size: 14px; color: var(--text); }
.avatar {
  width: 28px; height: 28px; border-radius: 50%;
  background: var(--accent); color: #fff; font-size: 13px; font-weight: 600;
  display: inline-flex; align-items: center; justify-content: center;
}

.app-main {
  flex: 1; width: 100%; max-width: 1120px;
  margin: 0 auto; padding: 32px 24px 64px;
}

.app-footer {
  text-align: center; padding: 32px 0; color: var(--text-tertiary); font-size: 12px;
  border-top: 1px solid var(--hairline);
}
</style>
