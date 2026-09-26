<template>
  <div class="channel">
    <header class="channel-head">
      <h1 class="channel-title">{{ currentName }}</h1>
      <p class="channel-sub" v-if="currentCategory">{{ fmtCount(currentCategory.count) }} 个视频</p>
    </header>

    <nav v-if="channels.length" class="chips" aria-label="分区切换">
      <button
        v-for="c in channels"
        :key="c.key"
        class="chip"
        :class="{ 'is-active': c.key === key }"
        @click="switchChannel(c.key)"
      >
        {{ c.name }}
      </button>
    </nav>

    <div v-if="loading" class="grid" aria-hidden="true">
      <div v-for="i in 8" :key="i" class="card-skeleton">
        <div class="sk-thumb"></div>
        <div class="sk-line"></div>
        <div class="sk-line sk-line-short"></div>
      </div>
    </div>

    <div v-else-if="!videos.length" class="empty">
      <p class="empty-title">这个分区还没有视频</p>
      <p class="empty-sub">换个分区逛逛,或者投下这里的第一稿。</p>
      <el-button type="primary" size="large" @click="$router.push('/')">去首页看看</el-button>
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
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api.js'

const route = useRoute()
const router = useRouter()

const key = computed(() => String(route.params.key || ''))

const channels = ref([])
const videos = ref([])
const total = ref(0)
const loading = ref(true)
const query = reactive({ page: 1, size: 12 })

const currentCategory = computed(() => channels.value.find((c) => c.key === key.value) || null)
const currentName = computed(() => currentCategory.value?.name || key.value || '分区')

async function loadCategories() {
  try {
    const list = await http.get('/videos/categories')
    channels.value = [...(list || [])].sort((a, b) => (b.count || 0) - (a.count || 0))
  } catch {
    channels.value = []
  }
}

async function load() {
  loading.value = true
  try {
    const data = await http.get('/videos', { params: { category: key.value, page: query.page, size: query.size } })
    videos.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function switchChannel(k) {
  if (k === key.value) return
  router.push(`/channel/${k}`)
}

function onPage(p) {
  query.page = p
  load()
  scrollTop()
}

function scrollTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

// 分区切换(路由复用同一组件):重置页码并重载
watch(key, () => {
  query.page = 1
  load()
  scrollTop()
})

watch(currentName, (name) => {
  document.title = name ? `${name} · toys-video` : 'toys-video'
})

onMounted(() => {
  document.title = `${currentName.value} · toys-video`
  loadCategories()
  load()
})

onBeforeUnmount(() => {
  document.title = 'toys-video'
})

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
.channel-head {
  text-align: center;
  padding: 20px 0 4px;
}
.channel-title {
  margin: 0;
  font-size: 34px;
  font-weight: 700;
  letter-spacing: -0.02em;
}
.channel-sub {
  margin: 10px 0 0;
  font-size: 14px;
  color: var(--text-secondary);
}

.chips {
  display: flex;
  gap: 10px;
  margin: 28px 0 0;
  overflow-x: auto;
  padding: 4px 2px;
  scrollbar-width: none;
}
.chips::-webkit-scrollbar { display: none; }
.chip {
  flex-shrink: 0;
  padding: 8px 18px;
  border: none;
  border-radius: var(--radius-button);
  background: var(--surface);
  box-shadow: var(--shadow-card);
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.3s var(--ease), color 0.3s var(--ease), transform 0.3s var(--ease);
}
.chip:hover { color: var(--text); transform: translateY(-1px); }
.chip.is-active {
  background: var(--text);
  color: #fff;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 24px;
  margin-top: 36px;
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
