<template>
  <div class="auth-page">
    <div class="auth-card">
      <h1 class="auth-title">注册账号</h1>
      <p class="auth-subtitle">加入社区,分享你的每一个瞬间。</p>
      <el-form class="auth-form" :model="form" @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名(3-32 位字母数字下划线)" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码(至少 6 位)" show-password />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password2" type="password" placeholder="确认密码" show-password />
        </el-form-item>
        <el-button class="auth-submit" type="primary" size="large" :loading="loading" @click="submit">注册并登录</el-button>
        <div class="auth-switch">
          已有账号?
          <router-link :to="{ path: '/login', query: $route.query }">去登录</router-link>
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
const form = reactive({ username: '', password: '', password2: '' })
const loading = ref(false)

async function submit() {
  if (!/^[a-zA-Z0-9_]{3,32}$/.test(form.username)) {
    ElMessage.warning('用户名须为 3-32 位字母、数字或下划线')
    return
  }
  if (form.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  if (form.password !== form.password2) {
    ElMessage.warning('两次密码不一致')
    return
  }
  loading.value = true
  try {
    const data = await http.post('/auth/register', {
      username: form.username,
      password: form.password
    })
    auth.set({ ...data.user, token: data.token })
    ElMessage.success('注册成功')
    router.push(route.query.redirect || '/')
  } finally {
    loading.value = false
  }
}
</script>
