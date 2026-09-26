<template>
  <div class="watch">
    <div class="player-shell">
      <div class="player-wrap">
        <video ref="playerEl" class="player" controls playsinline></video>
        <div v-if="!playerError" class="danmaku-layer" :class="{ 'is-off': !danmakuOn }">
          <span
            v-for="d in flyingDanmaku"
            :key="d.key"
            class="danmaku-item"
            :style="{ top: `${6 + d.lane * 26}px` }"
            @animationend="removeFlying(d.key)"
          >{{ d.content }}</span>
        </div>
        <button
          v-if="!playerError"
          class="danmaku-toggle"
          :class="danmakuOn ? 'is-on' : 'is-off'"
          :aria-pressed="danmakuOn"
          :title="danmakuOn ? '关闭弹幕' : '开启弹幕'"
          @click="danmakuOn = !danmakuOn"
        >弹</button>
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

    <div class="danmaku-bar">
      <template v-if="auth.user">
        <input
          v-model="danmakuInput"
          class="danmaku-input"
          maxlength="100"
          placeholder="发个弹幕见证此刻"
          @keydown.enter="onDanmakuKeydown"
        />
        <button class="danmaku-send" :disabled="danmakuPending || !danmakuInput.trim()" @click="sendDanmaku">发送</button>
      </template>
      <router-link v-else class="login-pill" :to="{ path: '/login', query: { redirect: route.fullPath } }">登录后发弹幕</router-link>
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

    <section v-reveal class="comment-section reveal">
      <h2 class="comment-title">评论 {{ commentTotal }} 条</h2>
      <div v-if="auth.user" class="comment-editor">
        <textarea
          v-model="commentInput"
          class="comment-textarea"
          maxlength="500"
          rows="3"
          placeholder="写下你的评论"
        ></textarea>
        <div class="comment-editor-foot">
          <span class="comment-counter">{{ commentInput.length }}/500</span>
          <button class="comment-publish" :disabled="commentPending || !commentInput.trim()" @click="submitComment">发布</button>
        </div>
      </div>
      <div v-else class="comment-editor comment-editor-guest">
        <router-link class="login-pill" :to="{ path: '/login', query: { redirect: route.fullPath } }">登录后发表评论</router-link>
      </div>
      <ul v-if="comments.length" v-loading="commentLoading" class="comment-list">
        <li v-for="item in comments" :key="item.id" class="comment-item">
          <span class="comment-avatar">{{ avatarChar(item.nickname || item.username) }}</span>
          <div class="comment-body">
            <div class="comment-head">
              <span class="comment-user">{{ item.nickname || item.username }}</span>
              <span class="comment-time">{{ fmtRelative(item.createdAt) }}</span>
            </div>
            <p class="comment-content">{{ item.content }}</p>
          </div>
          <button v-if="isMyComment(item)" class="comment-delete" @click="removeComment(item)">删除</button>
        </li>
      </ul>
      <p v-else-if="commentLoaded && !commentLoading" class="comment-empty">还没有评论,来抢沙发</p>
      <div v-if="totalPages > 1" class="comment-pager">
        <button class="page-btn" :disabled="commentPage <= 1 || commentLoading" @click="goPage(commentPage - 1)">上一页</button>
        <span class="page-indicator">{{ commentPage }} / {{ totalPages }}</span>
        <button class="page-btn" :disabled="commentPage >= totalPages || commentLoading" @click="goPage(commentPage + 1)">下一页</button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
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
  likeCount.value = Number(detail.value.likeCount || 0)
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
  likeCount.value = Number(likeCount.value) + (target ? 1 : -1)
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

/** 弹幕:按 timeSec 升序维护游标,timeupdate 驱动,±0.5s 窗口内触发飞行。 */
const danmakuOn = ref(true)
const danmakuList = ref([])
const danmakuInput = ref('')
const danmakuPending = ref(false)
const flyingDanmaku = ref([])
let danmakuCursor = 0
let lastVideoTime = 0
let danmakuSeq = 0
let lastLane = -1

async function loadDanmaku() {
  try {
    const data = await http.get(`/videos/${route.params.id}/danmaku`)
    danmakuList.value = (Array.isArray(data) ? data : [])
      .map((d) => ({ id: d.id, timeSec: Number(d.timeSec) || 0, content: d.content, shown: false }))
      .sort((a, b) => a.timeSec - b.timeSec)
    danmakuCursor = 0
    lastVideoTime = 0
  } catch {
    danmakuList.value = []
  }
}

function lowerBound(timeSec) {
  const list = danmakuList.value
  let lo = 0
  let hi = list.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (list[mid].timeSec < timeSec) lo = mid + 1
    else hi = mid
  }
  return lo
}

/** 游标推进:向后 seek 重置游标,已展示/被跳过的弹幕不补播;关闭时只消费不渲染。 */
function onDanmakuTick() {
  const video = playerEl.value
  if (!video) return
  const t = video.currentTime
  if (t < lastVideoTime - 1) {
    danmakuCursor = lowerBound(t - 0.5)
  }
  lastVideoTime = t
  const list = danmakuList.value
  while (danmakuCursor < list.length && list[danmakuCursor].timeSec <= t + 0.5) {
    const item = list[danmakuCursor]
    danmakuCursor++
    if (item.shown) continue
    item.shown = true
    if (danmakuOn.value && item.timeSec >= t - 0.5) {
      flyDanmaku(item.content)
    }
  }
}

function flyDanmaku(content) {
  let lane = Math.floor(Math.random() * 4)
  if (lane === lastLane) lane = (lane + 1) % 4
  lastLane = lane
  flyingDanmaku.value.push({ key: ++danmakuSeq, content, lane })
}

function removeFlying(key) {
  flyingDanmaku.value = flyingDanmaku.value.filter((d) => d.key !== key)
}

function insertDanmakuSorted(item) {
  const list = danmakuList.value
  let i = list.length
  while (i > 0 && list[i - 1].timeSec > item.timeSec) i--
  list.splice(i, 0, item)
}

function onDanmakuKeydown(e) {
  if (e.isComposing || e.keyCode === 229) return
  sendDanmaku()
}

/** 发送弹幕:timeSec 取播放器当前时间,成功后立即飞行。 */
async function sendDanmaku() {
  const content = danmakuInput.value.trim()
  if (!content || danmakuPending.value) return
  const timeSec = Math.floor(playerEl.value?.currentTime || 0)
  danmakuPending.value = true
  try {
    await http.post(`/videos/${route.params.id}/danmaku`, { timeSec, content })
    danmakuInput.value = ''
    if (danmakuOn.value) flyDanmaku(content)
    insertDanmakuSorted({ id: `local-${danmakuSeq}`, timeSec, content, shown: true })
  } catch {
    // 违规内容 1004 等错误已由 api.js 拦截器 toast
  } finally {
    danmakuPending.value = false
  }
}

/** 评论区 */
const COMMENT_SIZE = 20
const comments = ref([])
const commentTotal = ref(0)
const commentPage = ref(1)
const commentLoaded = ref(false)
const commentLoading = ref(false)
const commentInput = ref('')
const commentPending = ref(false)
const totalPages = computed(() => Math.max(1, Math.ceil(commentTotal.value / COMMENT_SIZE)))

async function loadComments(page = commentPage.value) {
  commentLoading.value = true
  try {
    const data = await http.get(`/videos/${route.params.id}/comments`, { params: { page, size: COMMENT_SIZE } })
    comments.value = Array.isArray(data?.list) ? data.list : []
    commentTotal.value = Number(data?.total || 0)
    commentPage.value = Number(data?.page || page)
  } catch {
    // 错误提示由 api.js 拦截器统一弹出
  } finally {
    commentLoaded.value = true
    commentLoading.value = false
  }
}

function goPage(page) {
  const target = Math.min(Math.max(1, page), totalPages.value)
  if (target === commentPage.value || commentLoading.value) return
  loadComments(target)
}

async function submitComment() {
  const content = commentInput.value.trim()
  if (!content || commentPending.value) return
  commentPending.value = true
  try {
    await http.post(`/videos/${route.params.id}/comments`, { content })
    commentInput.value = ''
    ElMessage.success('评论已发布')
    await loadComments(1)
  } catch {
    // 违规内容 1004 等错误已由 api.js 拦截器 toast
  } finally {
    commentPending.value = false
  }
}

function isMyComment(item) {
  return !!auth.user && String(auth.user.id) === String(item.userId)
}

async function removeComment(item) {
  try {
    await ElMessageBox.confirm('删除后不可恢复,确定删除这条评论吗?', '删除评论', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await http.delete(`/videos/${route.params.id}/comments/${item.id}`)
    if (comments.value.length <= 1 && commentPage.value > 1) {
      commentPage.value -= 1
    }
    await loadComments()
  } catch {
    // 错误提示由 api.js 拦截器统一弹出
  }
}

onMounted(() => {
  const video = playerEl.value
  if (!video) return
  video.addEventListener('loadedmetadata', onLoadedMetadata)
  video.addEventListener('timeupdate', onTimeUpdate)
  video.addEventListener('timeupdate', onDanmakuTick)
})
onMounted(load)
onMounted(loadDanmaku)
onMounted(() => loadComments(1))
onBeforeUnmount(destroyHls)
onBeforeUnmount(() => {
  const video = playerEl.value
  if (!video) return
  video.removeEventListener('loadedmetadata', onLoadedMetadata)
  video.removeEventListener('timeupdate', onTimeUpdate)
  video.removeEventListener('timeupdate', onDanmakuTick)
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
function fmtRelative(d) {
  const t = new Date(d).getTime()
  if (Number.isNaN(t)) return ''
  const diff = Date.now() - t
  const min = 60_000
  const hour = 60 * min
  const day = 24 * hour
  if (diff < min) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / min)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  if (diff < 30 * day) return `${Math.floor(diff / day)} 天前`
  return new Date(t).toLocaleDateString('zh-CN')
}
function avatarChar(name) {
  const ch = (name || '').trim().charAt(0)
  return (ch || '匿').toUpperCase()
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

.danmaku-layer {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 50%;
  z-index: 2;
  overflow: hidden;
  pointer-events: none;
  transition: opacity 0.25s var(--ease), visibility 0.25s;
}
.danmaku-layer.is-off {
  opacity: 0;
  visibility: hidden;
}
.danmaku-item {
  position: absolute;
  left: 100%;
  font-size: 15px;
  font-weight: 500;
  line-height: 1.4;
  color: #fff;
  white-space: nowrap;
  text-shadow:
    1px 0 1px #000,
    -1px 0 1px #000,
    0 1px 1px #000,
    0 -1px 1px #000,
    1px 1px 2px rgba(0, 0, 0, 0.8),
    -1px -1px 2px rgba(0, 0, 0, 0.8);
  animation: danmaku-fly 8s linear forwards;
  will-change: transform;
}
@keyframes danmaku-fly {
  from { transform: translateX(0); }
  to { transform: translateX(calc(-100vw - 100% - 48px)); }
}
.danmaku-toggle {
  position: absolute;
  right: 14px;
  bottom: 58px;
  z-index: 4;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  color: rgba(255, 255, 255, 0.9);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s, color 0.2s, transform 0.2s var(--ease);
}
.danmaku-toggle:hover { transform: scale(1.06); }
.danmaku-toggle.is-on { background: var(--accent); color: #fff; }
.danmaku-toggle.is-off { background: rgba(0, 0, 0, 0.55); color: rgba(255, 255, 255, 0.4); }
.danmaku-toggle.is-off::after {
  content: '';
  position: absolute;
  left: 8px;
  right: 8px;
  top: 50%;
  height: 1.5px;
  border-radius: 1px;
  background: rgba(255, 255, 255, 0.8);
  transform: rotate(-45deg);
}

.danmaku-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 0 4px;
}
.danmaku-input {
  flex: 1;
  min-width: 0;
  height: 38px;
  padding: 0 16px;
  border: 1px solid var(--hairline);
  border-radius: 999px;
  background: var(--surface);
  font: inherit;
  font-size: 14px;
  color: var(--text);
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.danmaku-input::placeholder { color: var(--text-tertiary); }
.danmaku-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(0, 113, 227, 0.14);
}
.danmaku-send {
  flex: none;
  height: 38px;
  padding: 0 20px;
  border: none;
  border-radius: 999px;
  background: var(--accent);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s, opacity 0.2s;
}
.danmaku-send:hover { background: var(--accent-hover); }
.danmaku-send:disabled { opacity: 0.4; cursor: default; }
.login-pill {
  display: inline-flex;
  align-items: center;
  height: 38px;
  padding: 0 18px;
  border-radius: 999px;
  background: rgba(0, 113, 227, 0.08);
  color: var(--accent);
  font-size: 14px;
  font-weight: 500;
  transition: background 0.2s;
}
.login-pill:hover { background: rgba(0, 113, 227, 0.14); }

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

.comment-section { padding: 8px 0 64px; }
.comment-title {
  margin: 24px 0 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--text);
}
.comment-editor { margin-top: 16px; }
.comment-editor-guest { display: flex; }
.comment-textarea {
  display: block;
  width: 100%;
  box-sizing: border-box;
  min-height: 76px;
  padding: 12px 16px;
  border: 1px solid var(--hairline);
  border-radius: 14px;
  background: var(--surface);
  font: inherit;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text);
  resize: vertical;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.comment-textarea::placeholder { color: var(--text-tertiary); }
.comment-textarea:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(0, 113, 227, 0.14);
}
.comment-editor-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  margin-top: 10px;
}
.comment-counter {
  font-size: 12px;
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
}
.comment-publish {
  height: 34px;
  padding: 0 18px;
  border: none;
  border-radius: 999px;
  background: var(--accent);
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s, opacity 0.2s;
}
.comment-publish:hover { background: var(--accent-hover); }
.comment-publish:disabled { opacity: 0.4; cursor: default; }
.comment-list {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
}
.comment-item {
  display: flex;
  gap: 12px;
  padding: 16px 0;
  border-bottom: 1px solid var(--hairline);
}
.comment-avatar {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  font-weight: 600;
  user-select: none;
}
.comment-body {
  flex: 1;
  min-width: 0;
}
.comment-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.comment-user {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}
.comment-time {
  font-size: 12px;
  color: var(--text-tertiary);
}
.comment-content {
  margin: 4px 0 0;
  font-size: 15px;
  line-height: 1.6;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
}
.comment-delete {
  flex: none;
  align-self: flex-start;
  border: none;
  background: transparent;
  padding: 2px 4px;
  font-size: 12px;
  color: var(--text-tertiary);
  cursor: pointer;
  transition: color 0.2s;
}
.comment-delete:hover { color: var(--danger); }
.comment-empty {
  margin: 32px 0 0;
  text-align: center;
  font-size: 14px;
  color: var(--text-tertiary);
}
.comment-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin-top: 20px;
}
.page-btn {
  height: 32px;
  padding: 0 16px;
  border: 1px solid var(--hairline);
  border-radius: 999px;
  background: transparent;
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: color 0.2s, border-color 0.2s, opacity 0.2s;
}
.page-btn:hover:not(:disabled) {
  color: var(--text);
  border-color: var(--text-tertiary);
}
.page-btn:disabled { opacity: 0.4; cursor: default; }
.page-indicator {
  font-size: 13px;
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
}
</style>
