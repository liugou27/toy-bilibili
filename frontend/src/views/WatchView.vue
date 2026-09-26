<template>
  <div class="watch">
    <div class="player-shell">
      <div class="player-wrap">
        <video ref="playerEl" class="player" controls playsinline></video>
        <div v-if="resumeHint !== null" class="resume-bar">
          <span>上次看到 {{ fmtTime(resumeHint) }}</span>
          <button class="resume-jump" @click="jumpToResume">跳转继续</button>
          <button class="resume-close" aria-label="关闭" @click="resumeHint = null">×</button>
        </div>
        <div v-if="playerError" class="player-error">
          <p class="player-error-title">视频加载失败</p>
          <p class="player-error-sub">请检查网络连接后重试</p>
          <el-button type="primary" size="large" @click="retry">重试</el-button>
        </div>
      </div>
    </div>
    <section v-if="detail" v-reveal class="watch-info reveal">
      <div class="title-row">
        <h1 class="watch-title">{{ detail.title }}</h1>
        <button class="like-btn" :class="{ 'is-liked': likedByMe }" :disabled="likePending" @click="toggleLike">
          <span class="like-icon">{{ likedByMe ? '♥' : '♡' }}</span>
          <span>{{ fmtCount(likeCount) }}</span>
        </button>
      </div>
      <div class="meta-bar">
        <span class="owner">{{ detail.ownerName }}</span>
        <span class="divider">·</span>
        <span>{{ fmtCount(detail.playCount) }} 播放</span>
        <template v-if="detail.publishedAt">
          <span class="divider">·</span>
          <span>{{ fmtDate(detail.publishedAt) }} 发布</span>
        </template>
      </div>
      <p v-if="detail.description" class="desc">{{ detail.description }}</p>
    </section>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Hls from 'hls.js'
import http from '../api.js'
import { auth } from '../auth.js'

const route = useRoute()
const playerEl = ref(null)
const detail = ref(null)
const playerError = ref(false)
let hls = null

const likeCount = ref(0)
const likedByMe = ref(false)
const likePending = ref(false)
const resumeHint = ref(null)
let lastPosSentAt = 0

async function load() {
  playerError.value = false
  detail.value = await http.get(`/videos/${route.params.id}`)
  likeCount.value = detail.value.likeCount || 0
  likedByMe.value = !!detail.value.likedByMe
  // 播放量异步计数
  http.post(`/videos/${route.params.id}/play`).catch(() => {})
  if (detail.value.playbackUrl) {
    startPlayer(detail.value.playbackUrl)
  }
}

/** 点赞/取消赞:乐观更新数字,失败回滚(错误提示由 api.js 拦截器统一弹出)。 */
async function toggleLike() {
  if (likePending.value) return
  const target = !likedByMe.value
  likePending.value = true
  likeCount.value += target ? 1 : -1
  likedByMe.value = target
  try {
    if (target) {
      await http.post(`/videos/${route.params.id}/like`)
    } else {
      await http.delete(`/videos/${route.params.id}/like`)
    }
  } catch {
    likeCount.value += target ? -1 : 1
    likedByMe.value = !target
  } finally {
    likePending.value = false
  }
}

function onLoadedMetadata() {
  const video = playerEl.value
  const resume = detail.value?.resumePosition
  if (video && resume > 5 && resume < video.duration - 10) {
    resumeHint.value = resume
  }
}

function jumpToResume() {
  const video = playerEl.value
  if (video) video.currentTime = resumeHint.value
  resumeHint.value = null
}

/** 播放中每 10s 节流上报进度;未登录不上报。 */
function onTimeUpdate() {
  const video = playerEl.value
  if (!video || video.paused || !video.duration) return
  const now = Date.now()
  if (now - lastPosSentAt < 10_000) return
  lastPosSentAt = now
  savePosition(video.currentTime)
}

function savePosition(position) {
  if (!auth.user) return
  http.post(`/videos/${route.params.id}/position`, { position }).catch(() => {})
}

function startPlayer(url) {
  const video = playerEl.value
  if (Hls.isSupported()) {
    hls = new Hls({ maxBufferLength: 30 })
    hls.loadSource(url)
    hls.attachMedia(video)
    hls.on(Hls.Events.ERROR, (_, data) => {
      if (data.fatal) {
        playerError.value = true
        destroyHls()
      }
    })
  } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
    video.src = url
  } else {
    playerError.value = true
  }
}

function retry() {
  playerError.value = false
  destroyHls()
  if (detail.value?.playbackUrl) startPlayer(detail.value.playbackUrl)
}

function destroyHls() {
  if (hls) {
    hls.destroy()
    hls = null
  }
}

onMounted(() => {
  const video = playerEl.value
  if (!video) return
  video.addEventListener('loadedmetadata', onLoadedMetadata)
  video.addEventListener('timeupdate', onTimeUpdate)
})
onMounted(load)
onBeforeUnmount(destroyHls)
onBeforeUnmount(() => {
  const video = playerEl.value
  if (!video) return
  video.removeEventListener('loadedmetadata', onLoadedMetadata)
  video.removeEventListener('timeupdate', onTimeUpdate)
  // 卸载时补报一次进度,避免最后不足 10s 的观看丢失
  if (video.duration > 0 && video.currentTime > 0) {
    savePosition(video.currentTime)
  }
})

function fmtCount(n) {
  return n >= 10000 ? `${(n / 10000).toFixed(1)}万` : String(n || 0)
}
function fmtTime(sec) {
  const s = Math.floor(sec || 0)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}
function fmtDate(d) {
  return new Date(d).toLocaleDateString('zh-CN')
}

// v-reveal:进入视口后加 .is-visible 触发渐入(样式见 style.css 的 .reveal)
let revealObserver = null

function ensureRevealObserver() {
  if (revealObserver) return revealObserver
  revealObserver = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue
      revealObserver.unobserve(entry.target)
      entry.target.classList.add('is-visible')
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
.watch { max-width: 860px; margin: 0 auto; }

/* 播放器通栏:外扩负边距至视口全宽 */
.player-shell {
  width: 100vw;
  margin-left: calc(50% - 50vw);
}
.player-wrap {
  position: relative;
  background: #000;
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.18);
}
.player {
  width: 100%;
  aspect-ratio: 16/9;
  display: block;
  background: #000;
}
.player-error {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: #fff;
  background: rgba(0, 0, 0, 0.82);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}
.player-error-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
}
.player-error-sub {
  margin: 6px 0 18px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.65);
}

.watch-info { padding-bottom: 8px; }
.title-row {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}
.watch-title {
  margin: 28px 0 0;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.25;
  flex: 1;
  min-width: 0;
}
.like-btn {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 30px;
  padding: 5px 14px;
  border: 1px solid var(--hairline);
  border-radius: 999px;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  color: var(--text-secondary);
  transition: color 0.2s, border-color 0.2s, background 0.2s;
}
.like-btn:hover { color: var(--text); }
.like-btn:disabled { cursor: default; opacity: 0.7; }
.like-btn .like-icon { font-size: 16px; line-height: 1; }
.like-btn.is-liked {
  color: var(--danger);
  border-color: rgba(255, 59, 48, 0.35);
  background: rgba(255, 59, 48, 0.08);
  font-weight: 600;
}
.resume-bar {
  position: absolute;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 10px 7px 16px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.72);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  color: #fff;
  font-size: 14px;
}
.resume-jump {
  border: none;
  border-radius: 999px;
  padding: 4px 12px;
  cursor: pointer;
  background: var(--accent);
  color: #fff;
  font-size: 13px;
  font-weight: 500;
}
.resume-jump:hover { filter: brightness(1.1); }
.resume-close {
  border: none;
  background: transparent;
  padding: 2px 6px;
  cursor: pointer;
  color: rgba(255, 255, 255, 0.6);
  font-size: 16px;
  line-height: 1;
}
.resume-close:hover { color: #fff; }
.meta-bar {
  margin-top: 12px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 15px;
  color: var(--text-secondary);
}
.meta-bar .owner {
  color: var(--text);
  font-weight: 500;
}
.meta-bar .divider { color: var(--text-tertiary); }
.desc {
  margin: 24px 0 0;
  padding-top: 24px;
  border-top: 1px solid var(--hairline);
  font-size: 16px;
  line-height: 1.7;
  color: var(--text);
  white-space: pre-wrap;
}
</style>
