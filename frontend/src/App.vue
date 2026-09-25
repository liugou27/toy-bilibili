<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <div class="header-left" @click="$router.push('/')">
        <span class="logo">▶ toys-video</span>
      </div>
      <div class="header-search">
        <el-input
          v-model="keyword"
          placeholder="搜索视频"
          clearable
          @keyup.enter="doSearch"
        >
          <template #append>
            <el-button @click="doSearch">搜索</el-button>
          </template>
        </el-input>
      </div>
      <div class="header-right">
        <template v-if="auth.user">
          <el-button text type="primary" @click="$router.push('/upload')">投稿</el-button>
          <el-button text @click="$router.push('/my')">我的投稿</el-button>
          <el-button v-if="auth.isAdmin()" text type="warning" @click="$router.push('/admin/review')">
            审核后台
          </el-button>
          <el-dropdown @command="onUserCommand">
            <span class="username">{{ auth.user.username }}</span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <template v-else>
          <el-button text @click="$router.push('/login')">登录</el-button>
          <el-button type="primary" plain @click="$router.push('/register')">注册</el-button>
        </template>
      </div>
    </el-header>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { auth } from './auth.js'

const router = useRouter()
const keyword = ref('')

function doSearch() {
  router.push({ path: '/', query: { q: keyword.value || undefined } })
}

function onUserCommand(cmd) {
  if (cmd === 'logout') {
    auth.logout()
    router.push('/')
  }
}
</script>

<style>
* { box-sizing: border-box; }
body { margin: 0; background: #f5f6f8; font-family: 'PingFang SC', 'Helvetica Neue', Arial, sans-serif; }
.app-shell { min-height: 100vh; }
.app-header {
  display: flex; align-items: center; gap: 24px;
  background: #fff; border-bottom: 1px solid #e8e8ea;
  position: sticky; top: 0; z-index: 10;
}
.logo { font-weight: 700; font-size: 20px; color: #fb7299; cursor: pointer; white-space: nowrap; }
.header-search { flex: 1; max-width: 480px; }
.header-right { margin-left: auto; display: flex; align-items: center; gap: 4px; }
.username { cursor: pointer; color: #333; padding: 0 8px; }
.app-main { max-width: 1200px; margin: 0 auto; width: 100%; }
</style>
