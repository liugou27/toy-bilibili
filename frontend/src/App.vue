<template>
  <el-container class="app-shell">
    <header class="nav">
      <div class="nav-inner">
        <a class="nav-logo" href="/" @click.prevent="$router.push('/')">▶ toys-video</a>
        <nav class="nav-links">
          <a href="/" @click.prevent="$router.push('/')">发现</a>
          <a v-if="auth.user" href="/upload" @click.prevent="$router.push('/upload')">投稿</a>
          <a v-if="auth.user" href="/my" @click.prevent="$router.push('/my')">我的投稿</a>
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
import { computed } from 'vue'
import { auth } from './auth.js'
import { useRouter } from 'vue-router'

const router = useRouter()

// 展示名:nickname 优先,未设置时回退用户名(本地缓存的旧登录态可能无 nickname)
const displayName = computed(() => auth.user?.nickname || auth.user?.username || '')

function onUserCommand(cmd) {
  if (cmd === 'logout') {
    auth.logout()
    router.push('/')
  }
}
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
.nav-links { display: flex; gap: 28px; }
.nav-links a { color: var(--text-secondary); font-size: 14px; transition: color 0.3s var(--ease); }
.nav-links a:hover { color: var(--text); text-decoration: none; }
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
