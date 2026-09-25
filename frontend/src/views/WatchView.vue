<template>
  <div class="watch">
    <div class="player-wrap">
      <video ref="playerEl" class="player" controls playsinline></video>
      <div v-if="playerError" class="player-error">
        <p>视频加载失败</p>
        <el-button type="primary" @click="retry">重试</el-button>
      </div>
    </div>
    <h1 class="title">{{ detail?.title }}</h1>
    <div class="meta-bar">
      <span class="owner">{{ detail?.ownerName }}</span>
      <span>{{ fmtCount(detail?.playCount) }} 播放</span>
      <span v-if="detail?.publishedAt">{{ fmtDate(detail.publishedAt) }} 发布</span>
    </div>
    <el-card v-if="detail?.description" shadow="never" class="desc">
      {{ detail.description }}
    </el-card>
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
</script>

<style scoped>
.watch { max-width: 860px; margin: 0 auto; }
.player-wrap { position: relative; background: #000; border-radius: 8px; overflow: hidden; }
.player { width: 100%; aspect-ratio: 16/9; display: block; }
.player-error {
  position: absolute; inset: 0; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 12px; color: #fff; background: rgba(0,0,0,.8);
}
.title { font-size: 20px; margin: 16px 0 8px; }
.meta-bar { color: #999; font-size: 13px; display: flex; gap: 16px; }
.desc { margin-top: 16px; white-space: pre-wrap; }
</style>
