<template>
  <div class="auth-page">
    <div class="auth-card">
      <h1 class="auth-title">登录 toys-video</h1>
      <p class="auth-subtitle">欢迎回来,精彩继续。</p>
      <el-form class="auth-form" :model="form" @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" show-password />
        </el-form-item>
        <el-button class="auth-submit" type="primary" size="large" :loading="loading" @click="submit">登录</el-button>
        <div class="auth-switch">
          没有账号?
          <router-link :to="{ path: '/register', query: $route.query }">去注册</router-link>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api.js'
import { auth } from '../auth.js'

const route = useRoute()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const data = await http.post('/auth/login', form)
    auth.set({ ...data.user, token: data.token })
    ElMessage.success('登录成功')
    router.push(route.query.redirect || '/')
  } finally {
    loading.value = false
  }
}
</script>
