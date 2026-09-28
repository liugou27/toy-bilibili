<template>
  <div class="user-home">
    <!-- 用户不存在 -->
    <div v-if="notFound" class="empty">
      <p class="empty-title">用户不存在</p>
      <p class="empty-sub">TA 可能已注销,或链接有误。</p>
      <el-button type="primary" size="large" @click="$router.push('/')">回到首页</el-button>
    </div>

    <template v-else>
      <!-- 头部横幅卡 -->
      <section v-if="profileLoading" class="banner" aria-hidden="true">
        <div class="banner-sk-avatar"></div>
        <div class="banner-sk-body">
          <div class="banner-sk-line"></div>
          <div class="banner-sk-line banner-sk-line-short"></div>
        </div>
      </section>
      <section v-else-if="profile" class="banner reveal" v-reveal>
        <div class="banner-avatar">
          <img v-if="profile.avatar" :src="profile.avatar" :alt="profile.name" />
          <span v-else>{{ (profile.name || 'U').slice(0, 1).toUpperCase() }}</span>
        </div>
        <div class="banner-info">
          <h1 class="banner-name">{{ profile.name || 'UP主' }}</h1>
          <p class="banner-stats">
            {{ fmtCount(profile.videoCount) }} 个投稿
            <span class="dot">·</span>
            总播放 {{ fmtCount(profile.totalPlayCount) }}
          </p>
        </div>
        <div v-if="!isSelf" class="banner-side">
          <button v-if="auth.user" class="follow-pill" :class="{ 'is-followed': followed }"
                  :disabled="followBusy" @click="toggleFollow">
            {{ followed ? '已关注' : '关注' }}
          </button>
          <p v-if="followerCount != null" class="follower-count">{{ fmtCount(followerCount) }} 粉丝</p>
        </div>
      </section>

      <!-- 投稿网格 -->
      <div v-if="videosLoading" class="grid" aria-hidden="true">
        <div v-for="i in 8" :key="i" class="card-skeleton">
          <div class="sk-thumb"></div>
          <div class="sk-line"></div>
          <div class="sk-line sk-line-short"></div>
        </div>
      </div>

      <div v-else-if="!videos.length" class="empty">
        <p class="empty-title">TA 还没有发布视频</p>
        <p class="empty-sub">先去首页看看别人的作品吧。</p>
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
              <span>{{ v.ownerName || profile?.name || 'UP主' }}</span>
              <span>{{ fmtCount(v.playCount) }} 播放</span>
            </div>
          </div>
        </article>
      </div>

      <div v-if="!videosLoading && total > query.size" class="pager">
        <el-pagination
          layout="prev, pager, next"
          :total="total"
          :page-size="query.size"
          :current-page="query.page"
          @current-change="onPage"
        />
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api.js'
import { auth } from '../auth.js'

const props = defineProps({
  id: { type: [String, Number], required: true }
})

const route = useRoute()
const router = useRouter()

const profile = ref(null)
const profileLoading = ref(true)
const notFound = ref(false)

const videos = ref([])
const total = ref(0)
const videosLoading = ref(true)
const query = reactive({ page: 1, size: 12 })

const uploaderId = computed(() => props.id)

const followed = ref(false)
const followBusy = ref(false)
const followerCount = ref(null)
const isSelf = computed(() => String(auth.user?.id) === String(uploaderId.value))

async function loadProfile() {
  profileLoading.value = true
  notFound.value = false
  profile.value = null
  try {
    profile.value = await http.get(`/videos/uploader/${uploaderId.value}/profile`)
    document.title = `${profile.value?.name || 'UP主'} · toys-video`
  } catch (err) {
    if (err?.response?.status === 404) {
      notFound.value = true
      document.title = 'toys-video'
    }
  } finally {
    profileLoading.value = false
  }
}

async function loadVideos() {
  videosLoading.value = true
  try {
    const data = await http.get(`/videos/uploader/${uploaderId.value}/videos`, {
      params: { page: query.page, size: query.size }
    })
    videos.value = data.list
    total.value = data.total
  } finally {
    videosLoading.value = false
  }
}

// —— 关注态 / 粉丝数 ——
async function loadFollowState() {
  followed.value = false
  followerCount.value = null
  if (auth.user && !isSelf.value) {
    try {
      followed.value = await http.get(`/users/${uploaderId.value}/followed`)
    } catch {
      // 状态拉取失败按未关注展示,点击关注时以后端结果为准
    }
  }
  try {
    followerCount.value = (await http.get(`/users/${uploaderId.value}/stats`)).follower
  } catch {
    // 统计失败则不展示粉丝数
  }
}

async function toggleFollow() {
  if (!auth.user) {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (followBusy.value) return
  followBusy.value = true
  const next = !followed.value
  const delta = next ? 1 : -1
  followed.value = next
  if (followerCount.value != null) followerCount.value += delta
  try {
    if (next) {
      await http.post(`/users/${uploaderId.value}/follow`)
    } else {
      await http.delete(`/users/${uploaderId.value}/follow`)
    }
  } catch {
    followed.value = !next
    if (followerCount.value != null) followerCount.value -= delta
  } finally {
    followBusy.value = false
  }
}

function reload() {
  query.page = 1
  loadProfile()
  loadVideos()
  loadFollowState()
  scrollTop()
}

function onPage(p) {
  query.page = p
  loadVideos()
  scrollTop()
}

function scrollTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

// 路由复用同一组件,id 变化时整体重载
watch(uploaderId, reload)

onMounted(() => {
  document.title = 'UP主 · toys-video'
  loadProfile()
  loadVideos()
  loadFollowState()
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
.banner {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 32px;
  background: var(--surface);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}
.banner-avatar {
  flex-shrink: 0;
  width: 88px;
  height: 88px;
  border-radius: 50%;
  overflow: hidden;
  background: linear-gradient(150deg, var(--accent), #5ac8fa);
  color: #fff;
  font-size: 34px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}
.banner-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.banner-name {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.02em;
}
.banner-stats {
  margin: 10px 0 0;
  font-size: 14px;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
}
.banner-stats .dot { margin: 0 10px; color: var(--text-tertiary); }

.banner-side { margin-left: auto; flex: none; text-align: center; }
.follow-pill {
  min-width: 88px;
  padding: 8px 22px;
  border: none;
  border-radius: 999px;
  background: var(--accent);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(0, 113, 227, 0.28);
  transition: background 0.25s var(--ease), color 0.25s var(--ease),
    box-shadow 0.25s var(--ease), transform 0.2s var(--ease), opacity 0.2s var(--ease);
}
.follow-pill:hover { transform: translateY(-1px); }
.follow-pill:active { transform: scale(0.97); }
.follow-pill:disabled { opacity: 0.6; cursor: default; transform: none; }
.follow-pill.is-followed {
  background: rgba(120, 120, 128, 0.12);
  color: var(--text-secondary);
  box-shadow: inset 0 0 0 1px var(--hairline);
}
.follower-count { margin: 8px 0 0; font-size: 12px; color: var(--text-tertiary); }

.banner-sk-avatar {
  flex-shrink: 0;
  width: 88px;
  height: 88px;
  border-radius: 50%;
  background: #e8e8ed;
  animation: skeleton-pulse 1.8s ease-in-out infinite;
}
.banner-sk-body { flex: 1; }
.banner-sk-line {
  height: 20px;
  width: 32%;
  border-radius: 10px;
  background: #e8e8ed;
  animation: skeleton-pulse 1.8s ease-in-out infinite;
}
.banner-sk-line-short { width: 20%; margin-top: 14px; }
@keyframes skeleton-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
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
