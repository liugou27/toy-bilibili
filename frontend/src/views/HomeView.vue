<template>
  <div class="home">
    <section class="hero reveal" v-reveal>
      <p v-if="query.keyword" class="hero-eyebrow">“{{ query.keyword }}” 的搜索结果</p>
      <h1 class="hero-title">每一个瞬间,都值得被看见</h1>
      <p class="hero-subtitle">随手记录,随时观看。来自社区创作者的每个精彩视频,都在这里。</p>
    </section>

    <div v-if="loading" class="grid" aria-hidden="true">
      <div v-for="i in 8" :key="i" class="card-skeleton">
        <div class="sk-thumb"></div>
        <div class="sk-line"></div>
        <div class="sk-line sk-line-short"></div>
      </div>
    </div>

    <div v-else-if="!videos.length" class="empty">
      <p class="empty-title">{{ query.keyword ? '没有找到相关视频' : '这里还很安静' }}</p>
      <p class="empty-sub">{{ query.keyword ? '换个关键词试试,或成为第一个分享它的人。' : '第一个视频,就从你的投稿开始。' }}</p>
      <el-button type="primary" size="large" @click="$router.push('/upload')">立即投稿</el-button>
    </div>

    <div v-else class="grid">
      <article
        v-for="v in videos"
        :key="v.id"
        v-reveal
        class="card reveal"
        @click="$router.push(`/watch/${v.id}`)"
      >
        <div class="thumb">
          <img v-if="v.poster" :src="v.poster" loading="lazy" />
          <div v-else class="thumb-placeholder">暂无封面</div>
          <span v-if="v.durationSec" class="duration">{{ fmtDuration(v.durationSec) }}</span>
        </div>
        <div class="card-body">
          <div class="title">{{ v.title }}</div>
          <div class="meta">
            <span>{{ v.ownerName || 'UP主' }}</span>
            <span>{{ fmtCount(v.playCount) }} 播放</span>
          </div>
        </div>
      </article>
    </div>

    <div v-if="total > query.size" class="pager">
      <el-pagination
        layout="prev, pager, next"
        :total="total"
        :page-size="query.size"
        :current-page="query.page"
        @current-change="onPage"
      />
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api.js'

const route = useRoute()
const videos = ref([])
const total = ref(0)
const loading = ref(true)
const query = reactive({ page: 1, size: 12, keyword: route.query.q || '' })

async function load() {
  loading.value = true
  try {
    const data = await http.get('/videos', { params: query })
    videos.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function onPage(p) {
  query.page = p
  load()
}

watch(() => route.query.q, (q) => {
  query.keyword = q || ''
  query.page = 1
  load()
})

onMounted(load)

function fmtDuration(sec) {
  const s = Math.round(sec)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

function fmtCount(n) {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万`
  return String(n || 0)
}

// v-reveal:进入视口后加 .is-visible 触发渐入(样式见 style.css 的 .reveal)
let revealObserver = null

function ensureRevealObserver() {
  if (revealObserver) return revealObserver
  revealObserver = new IntersectionObserver((entries) => {
    let batch = 0
    for (const entry of entries) {
      if (!entry.isIntersecting) continue
      revealObserver.unobserve(entry.target)
      const delay = Math.min(batch * 70, 280)
      batch += 1
      if (delay) {
        setTimeout(() => entry.target.classList.add('is-visible'), delay)
      } else {
        entry.target.classList.add('is-visible')
      }
    }
  }, { threshold: 0.1 })
  return revealObserver
}

const vReveal = {
  mounted(el) {
    ensureRevealObserver().observe(el)
  },
  unmounted(el) {
    revealObserver?.unobserve(el)
  }
}

onBeforeUnmount(() => {
  revealObserver?.disconnect()
  revealObserver = null
})
</script>

<style scoped>
.hero {
  text-align: center;
  padding: 32px 0 12px;
}
.hero-eyebrow {
  margin: 0 0 14px;
  font-size: 17px;
  font-weight: 600;
  color: var(--accent);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 24px;
  margin-top: 40px;
}

/* transition 同时承担 reveal 渐入(opacity)与 hover 反馈(transform/box-shadow) */
.card {
  cursor: pointer;
  background: var(--surface);
  border-radius: var(--radius-card);
  overflow: hidden;
  box-shadow: var(--shadow-card);
  transition: opacity 0.7s var(--ease), transform 0.4s var(--ease), box-shadow 0.4s var(--ease);
}
.card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-card-hover);
}
.thumb {
  position: relative;
  aspect-ratio: 16/9;
  overflow: hidden;
  background: #e8e8ed;
}
.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transition: transform 0.4s var(--ease);
}
.card:hover .thumb img { transform: scale(1.05); }
.thumb-placeholder {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-tertiary);
  font-size: 13px;
  background: linear-gradient(150deg, #ececf1, #e3e3e8);
}
.duration {
  position: absolute;
  right: 10px;
  bottom: 10px;
  padding: 3px 9px;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  color: #fff;
  font-size: 12px;
  font-weight: 500;
  border-radius: var(--radius-button);
}
.card-body { padding: 14px 16px 16px; }
.title {
  min-height: 42px;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: -0.01em;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.meta {
  margin-top: 8px;
  font-size: 13px;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  gap: 8px;
}
.meta span + span::before {
  content: '·';
  color: var(--text-tertiary);
  margin-right: 8px;
}

/* Apple 式浅灰骨架占位块 */
.card-skeleton { border-radius: var(--radius-card); }
.sk-thumb {
  aspect-ratio: 16/9;
  border-radius: var(--radius-card);
  background: #e8e8ed;
  animation: skeleton-pulse 1.8s ease-in-out infinite;
}
.sk-line {
  height: 14px;
  margin-top: 14px;
  border-radius: 7px;
  background: #e8e8ed;
  animation: skeleton-pulse 1.8s ease-in-out infinite;
}
.sk-line-short { width: 55%; }
@keyframes skeleton-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.empty {
  text-align: center;
  padding: 72px 0 56px;
}
.empty-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.02em;
}
.empty-sub {
  margin: 10px 0 24px;
  font-size: 15px;
  color: var(--text-secondary);
}

.pager {
  display: flex;
  justify-content: center;
  margin: 44px 0 8px;
}
.pager :deep(.el-pagination) {
  --el-pagination-text-color: var(--text-secondary);
  --el-pagination-button-disabled-color: #c7c7cc;
  font-weight: 500;
}
.pager :deep(.el-pager li) {
  min-width: 32px;
  height: 32px;
  line-height: 32px;
  border-radius: var(--radius-button);
  font-weight: 500;
  transition: background 0.3s var(--ease), color 0.3s var(--ease);
}
.pager :deep(.el-pager li.is-active) {
  background: var(--text);
  color: #fff;
}
.pager :deep(.btn-prev),
.pager :deep(.btn-next) {
  border-radius: var(--radius-button);
}
</style>
