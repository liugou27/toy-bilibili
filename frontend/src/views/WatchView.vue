<template>
  <div class="watch">
    <div class="player-shell">
      <div class="player-wrap">
        <video ref="playerEl" class="player" controls playsinline></video>
        <div v-if="playerError" class="player-error">
          <p class="player-error-title">视频加载失败</p>
          <p class="player-error-sub">请检查网络连接后重试</p>
          <el-button type="primary" size="large" @click="retry">重试</el-button>
        </div>
      </div>
    </div>
    <section v-if="detail" v-reveal class="watch-info reveal">
      <h1 class="watch-title">{{ detail.title }}</h1>
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

const route = useRoute()
const playerEl = ref(null)
const detail = ref(null)
const playerError = ref(false)
let hls = null

async function load() {
  playerError.value = false
  detail.value = await http.get(`/videos/${route.params.id}`)
  // 播放量异步计数
  http.post(`/videos/${route.params.id}/play`).catch(() => {})
  if (detail.value.playbackUrl) {
    startPlayer(detail.value.playbackUrl)
  }
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

onMounted(load)
onBeforeUnmount(destroyHls)

function fmtCount(n) {
  return n >= 10000 ? `${(n / 10000).toFixed(1)}万` : String(n || 0)
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
.watch-title {
  margin: 28px 0 0;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.25;
}
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
