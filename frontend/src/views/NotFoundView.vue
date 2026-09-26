<template>
  <div class="notfound">
    <p class="code" aria-hidden="true">404</p>
    <h1 class="title">页面走丢了</h1>
    <p class="sub">你要找的页面不存在,或者已被移动。</p>
    <el-button type="primary" size="large" class="home-btn" @click="$router.push('/')">回到首页</el-button>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted } from 'vue'

onMounted(() => {
  document.title = '页面走丢了 · toys-video'
})

onBeforeUnmount(() => {
  document.title = 'toys-video'
})
</script>

<style scoped>
.notfound {
  min-height: 52vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 48px 24px;
}
.code {
  margin: 0;
  font-size: 88px;
  font-weight: 700;
  letter-spacing: -0.04em;
  line-height: 1;
  background: linear-gradient(180deg, var(--text), var(--text-tertiary));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.title {
  margin: 18px 0 0;
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.02em;
}
.sub {
  margin: 12px 0 32px;
  font-size: 15px;
  color: var(--text-secondary);
}
.home-btn {
  border-radius: var(--radius-button);
  padding: 12px 28px;
}
</style>
