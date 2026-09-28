<template>
  <div class="watch">
    <div class="watch-main">
      <div class="player-wrap">
        <div ref="playerEl" class="player"></div>
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
          <div class="action-group">
            <button class="action-btn" :class="{ 'is-liked': likedByMe }" :disabled="likePending" @click="toggleLike">
              <span class="action-icon">{{ likedByMe ? '♥' : '♡' }}</span>
              <span>{{ fmtCount(likeCount) }}</span>
            </button>
            <button class="action-btn" :class="{ 'is-faved': favoritedByMe }" :disabled="favPending" @click="toggleFavorite">
              <span class="action-icon">{{ favoritedByMe ? '★' : '☆' }}</span>
              <span>{{ favoritedByMe ? '已收藏' : '收藏' }}</span>
            </button>
          </div>
        </div>
        <div class="meta-bar">
          <span class="owner owner-link" @click="goUploader(detail.ownerId)">{{ detail.ownerName }}</span>
          <span class="divider">·</span>
          <span>{{ fmtCount(detail.playCount) }} 播放</span>
          <template v-if="detail.publishedAt">
            <span class="divider">·</span>
            <span>{{ fmtDate(detail.publishedAt) }} 发布</span>
          </template>
        </div>
        <div v-if="detail.category || (detail.tags && detail.tags.length)" class="chip-row">
          <button v-if="detail.category" class="chip chip-category" type="button" @click="goChannel">{{ categoryName }}</button>
          <span v-for="t in detail.tags || []" :key="t" class="chip chip-tag">{{ t }}</span>
        </div>
        <div v-if="detail.description" class="desc-block">
          <p class="desc" :class="{ 'is-collapsed': descLong && !descExpanded }">{{ detail.description }}</p>
          <button v-if="descLong" class="desc-toggle" type="button" @click="descExpanded = !descExpanded">{{ descExpanded ? '收起' : '展开' }}</button>
        </div>
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

    <aside class="watch-side">
      <div v-if="detail" class="owner-card">
        <span class="owner-avatar clickable" @click="goUploader(detail.ownerId)" title="查看 UP 主主页">{{ avatarChar(detail.ownerName) }}</span>
        <div class="owner-body clickable" @click="goUploader(detail.ownerId)" title="查看 UP 主主页">
          <span class="owner-name">{{ detail.ownerName }}</span>
          <span class="owner-sub">{{ fmtCount(detail.playCount) }} 播放<template v-if="followerCount != null"> · {{ fmtCount(followerCount) }} 粉丝</template></span>
        </div>
        <button v-if="auth.user && !isSelf" class="follow-pill" :class="{ 'is-followed': followed }"
                :disabled="followBusy" @click.stop="toggleFollow">
          {{ followed ? '已关注' : '关注' }}
        </button>
      </div>
      <section v-if="relatedList.length" v-reveal class="related-section reveal">
        <h2 class="related-title">相关推荐</h2>
        <ul class="related-list">
          <li v-for="v in relatedList" :key="v.id" class="related-item" @click="goRelated(v.id)">
            <div class="related-thumb">
              <img v-if="v.poster" :src="v.poster" loading="lazy" :alt="v.title" />
              <div v-else class="related-thumb-placeholder">暂无封面</div>
              <span v-if="v.durationSec" class="related-duration">{{ fmtTime(v.durationSec) }}</span>
            </div>
            <div class="related-body">
              <div class="related-item-title">{{ v.title }}</div>
              <div class="related-meta">
                <span>{{ v.ownerName || 'UP主' }}</span>
                <span>{{ fmtCount(v.playCount) }} 播放</span>
              </div>
            </div>
          </li>
        </ul>
      </section>
    </aside>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import Artplayer from 'artplayer'
import ArtplayerPluginDanmuku from 'artplayer-plugin-danmuku'
import Hls from 'hls.js'
import axios from 'axios'
import http from '../api.js'
import { auth } from '../auth.js'

const route = useRoute()
const router = useRouter()
const playerEl = ref(null)
const detail = ref(null)
const playerError = ref(false)
let art = null
let hls = null

const likeCount = ref(0)
const likedByMe = ref(false)
const likePending = ref(false)
const favoritedByMe = ref(false)
const favPending = ref(false)
const resumeHint = ref(null)
const playCounted = ref(false)
let lastPosSentAt = 0

// 分区与标签展示:分区名经 categories 接口解析,失败时回退显示分区 key
const categoryNames = ref({})
const descExpanded = ref(false)
const descLong = computed(() => (detail.value?.description || '').length > 120)
const categoryName = computed(() => {
  const key = detail.value?.category
  return key ? (categoryNames.value[key] || key) : ''
})

/** 直连 axios 静默拉取,避免接口未就绪时触发全局错误 toast */
async function loadCategoryNames() {
  try {
    const resp = await axios.get('/api/videos/categories')
    const body = resp?.data
    const list = Array.isArray(body) ? body : Array.isArray(body?.data) ? body.data : []
    const map = {}
    for (const c of list) {
      if (c && c.key != null && c.name != null) map[c.key] = c.name
    }
    if (Object.keys(map).length) categoryNames.value = map
  } catch {
    // 保持空映射,chip 回退显示分区 key
  }
}

function goChannel() {
  const key = detail.value?.category
  if (key) router.push(`/channel/${key}`)
}

async function load() {
  playerError.value = false
  destroyArt()
  resumeHint.value = null
  playCounted.value = false
  descExpanded.value = false
  detail.value = await http.get(`/videos/${route.params.id}`)
  likeCount.value = Number(detail.value.likeCount || 0)
  likedByMe.value = !!detail.value.likedByMe
  favoritedByMe.value = !!detail.value.favoritedByMe
  loadFollowState()
  if (detail.value.playbackUrl) {
    startPlayer(detail.value.playbackUrl)
  } else {
    playerError.value = true
  }
}

// —— 关注(播放页直接关注 UP 主)——
const followed = ref(false)
const followerCount = ref(null)
const followBusy = ref(false)
const isSelf = computed(() => auth.user && detail.value && String(auth.user.id) === String(detail.value.ownerId))

async function loadFollowState() {
  followed.value = false
  followerCount.value = null
  if (!detail.value) return
  const id = detail.value.ownerId
  try {
    const stats = await http.get(`/users/${id}/stats`)
    followerCount.value = Number(stats.follower || 0)
    if (auth.user && !isSelf.value) {
      followed.value = await http.get(`/users/${id}/followed`)
    }
  } catch { /* 资料缺失不阻塞播放 */ }
}

async function toggleFollow() {
  if (followBusy.value) return
  followBusy.value = true
  const target = !followed.value
  try {
    if (target) await http.post(`/users/${detail.value.ownerId}/follow`)
    else await http.delete(`/users/${detail.value.ownerId}/follow`)
    followed.value = target
    if (followerCount.value != null) followerCount.value += target ? 1 : -1
  } catch { /* 拦截器已 toast */ }
  followBusy.value = false
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

/** 收藏/取消收藏:乐观切换,失败回滚;未登录跳登录页并在返回后回到本页。 */
async function toggleFavorite() {
  if (!auth.user) {
    ElMessage.info('登录后即可收藏')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (favPending.value) return
  const target = !favoritedByMe.value
  favPending.value = true
  favoritedByMe.value = target
  try {
    if (target) {
      await http.post(`/videos/${route.params.id}/favorite`)
    } else {
      await http.delete(`/videos/${route.params.id}/favorite`)
    }
  } catch {
    favoritedByMe.value = !target
  } finally {
    favPending.value = false
  }
}

function jumpToResume() {
  if (art) art.currentTime = resumeHint.value
  resumeHint.value = null
}

/** 播放中每 10s 节流上报进度;未登录不上报。 */
function onTimeUpdate() {
  if (!art || art.paused || !art.duration) return
  const now = Date.now()
  if (now - lastPosSentAt < 10_000) return
  lastPosSentAt = now
  savePosition(art.currentTime)
}

function savePosition(position) {
  if (!auth.user) return
  http.post(`/videos/${route.params.id}/position`, { position }).catch(() => {})
}

function onPlayerReady() {
  if (!art) return
  const resume = detail.value?.resumePosition
  if (resume > 5 && resume < art.duration - 10) {
    resumeHint.value = resume
  }
}

/** 播放量计数:每次进入视频只在首次播放时上报一次。 */
function onFirstPlay() {
  if (playCounted.value) return
  playCounted.value = true
  http.post(`/videos/${route.params.id}/play`).catch(() => {})
}

function handlePlayerError() {
  if (playerError.value) return
  playerError.value = true
  // 延迟一拍销毁,避免在播放器自身事件回调里同步 destroy;校验实例防止与重试竞争
  const failing = art
  setTimeout(() => {
    if (art === failing) destroyArt()
  })
}

/** hls.js 接入:保留 Hls.isSupported 分支与 Safari 原生 fallback。 */
function attachM3u8(video, url) {
  destroyHls()
  if (Hls.isSupported()) {
    hls = new Hls({ maxBufferLength: 30 })
    hls.loadSource(url)
    hls.attachMedia(video)
    hls.on(Hls.Events.ERROR, (_, data) => {
      if (data.fatal) {
        destroyHls()
        handlePlayerError()
      }
    })
  } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
    video.src = url
  } else {
    handlePlayerError()
  }
}

function startPlayer(url) {
  const container = playerEl.value
  if (!container) return
  const videoId = route.params.id
  art = new Artplayer({
    container,
    url,
    type: 'm3u8',
    customType: { m3u8: attachM3u8 },
    plugins: [
      ArtplayerPluginDanmuku({
        danmuku: async () => {
          try {
            const data = await http.get(`/videos/${videoId}/danmaku`)
            return (Array.isArray(data) ? data : [])
              .map((d) => ({ time: Number(d.timeSec) || 0, text: d.content, color: '#FFFFFF' }))
              .sort((a, b) => a.time - b.time)
          } catch {
            return []
          }
        },
        speed: 5,
        margin: [10, '25%'],
        opacity: 1,
        color: '#FFFFFF',
        mode: 0,
        fontSize: 20,
      }),
    ],
    autoplay: false,
    setting: true,
    playbackRate: true,
    aspectRatio: true,
    flip: true,
    fullscreen: true,
    fullscreenWeb: true,
    miniProgressBar: true,
    airplay: true,
    pip: true,
    screenshot: true,
  })
  art.on('ready', onPlayerReady)
  art.on('video:play', onFirstPlay)
  art.on('video:timeupdate', onTimeUpdate)
  art.on('video:error', handlePlayerError)
}

function retry() {
  playerError.value = false
  destroyArt()
  if (detail.value?.playbackUrl) {
    startPlayer(detail.value.playbackUrl)
  } else {
    playerError.value = true
  }
}

function destroyArt(removeHtml = true) {
  destroyHls()
  if (art) {
    art.destroy(removeHtml)
    art = null
  }
}

function destroyHls() {
  if (hls) {
    hls.destroy()
    hls = null
  }
}

/** 弹幕发送:timeSec 取播放器当前时间,成功后经插件 emit 立即上屏。 */
const danmakuInput = ref('')
const danmakuPending = ref(false)

function onDanmakuKeydown(e) {
  if (e.isComposing || e.keyCode === 229) return
  sendDanmaku()
}

async function sendDanmaku() {
  const content = danmakuInput.value.trim()
  if (!content || danmakuPending.value) return
  const timeSec = Math.floor(art?.currentTime || 0)
  danmakuPending.value = true
  try {
    await http.post(`/videos/${route.params.id}/danmaku`, { timeSec, content })
    danmakuInput.value = ''
    art?.danmuku?.emit({ text: content, time: art.currentTime, color: '#FFFFFF' })
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

/** 相关推荐:id 变化时清空旧数据再拉取;为空或失败时整个区块不渲染。 */
const relatedList = ref([])

async function loadRelated() {
  relatedList.value = []
  try {
    const data = await http.get(`/videos/${route.params.id}/related`, { params: { size: 10 } })
    relatedList.value = Array.isArray(data?.list) ? data.list : []
  } catch {
    relatedList.value = []
  }
}

function goRelated(id) {
  if (String(id) === String(route.params.id)) return
  router.push(`/watch/${id}`)
}

// 路由组件在 /watch/:id 之间跳转时被复用,需手动重载全部页面状态并回到顶部
watch(() => route.params.id, (id) => {
  if (!id) return
  window.scrollTo({ top: 0 })
  load()
  loadComments(1)
  loadRelated()
})

onMounted(load)
onMounted(loadRelated)
onMounted(loadCategoryNames)
onMounted(() => loadComments(1))
onBeforeUnmount(() => {
  // 卸载时补报一次进度,避免最后不足 10s 的观看丢失
  if (art && art.duration > 0 && art.currentTime > 0) {
    savePosition(art.currentTime)
  }
  destroyArt(false)
})

function goUploader(id) {
  if (id) router.push(`/uploader/${id}`)
}

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
.watch {
  max-width: 1360px;
  margin: 0 auto;
  display: flex;
  align-items: flex-start;
  gap: 28px;
}
.watch-main {
  flex: 1;
  min-width: 0;
}
.watch-side {
  flex: none;
  width: 340px;
}

/* 播放器 */
.player-wrap {
  position: relative;
  background: #000;
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.18);
}
.player {
  width: 100%;
  aspect-ratio: 16 / 9;
  background: #000;
}
.player-error {
  position: absolute;
  inset: 0;
  z-index: 100;
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

.resume-bar {
  position: absolute;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 90;
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
  margin: 20px 0 0;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.25;
  flex: 1;
  min-width: 0;
}
.action-group {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 24px;
}
.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 14px;
  border: 1px solid var(--hairline);
  border-radius: 999px;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  color: var(--text-secondary);
  transition: color 0.2s, border-color 0.2s, background 0.2s;
}
.action-btn:hover { color: var(--text); }
.action-btn:disabled { cursor: default; opacity: 0.7; }
.action-btn .action-icon { font-size: 16px; line-height: 1; }
.action-btn.is-liked {
  color: var(--danger);
  border-color: rgba(255, 59, 48, 0.35);
  background: rgba(255, 59, 48, 0.08);
  font-weight: 600;
}
.action-btn.is-faved {
  color: var(--accent);
  border-color: rgba(0, 113, 227, 0.35);
  background: rgba(0, 113, 227, 0.08);
  font-weight: 600;
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
.chip-row {
  margin-top: 12px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  display: inline-flex;
  align-items: center;
  height: 26px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 13px;
  line-height: 1;
}
.chip-category {
  border: none;
  background: rgba(0, 113, 227, 0.08);
  color: var(--accent);
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;
}
.chip-category:hover { background: rgba(0, 113, 227, 0.16); }
.chip-tag {
  border: 1px solid var(--hairline);
  background: transparent;
  color: var(--text-secondary);
}
.desc-block {
  margin: 24px 0 0;
  padding-top: 24px;
  border-top: 1px solid var(--hairline);
}
.desc {
  margin: 0;
  font-size: 16px;
  line-height: 1.7;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
}
.desc.is-collapsed {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
  overflow: hidden;
}
.desc-toggle {
  margin-top: 8px;
  padding: 2px 0;
  border: none;
  background: transparent;
  color: var(--accent);
  font-family: inherit;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}

.clickable { cursor: pointer; }
.owner-card .clickable:hover .owner-name { color: var(--accent); }
.owner-link { cursor: pointer; }
.owner-link:hover { color: var(--accent); }
.follow-pill {
  margin-left: auto; flex-shrink: 0; border: none; cursor: pointer;
  background: var(--accent); color: #fff; font-size: 13px; font-weight: 600;
  padding: 7px 18px; border-radius: 980px;
  transition: background .25s var(--ease), transform .25s var(--ease);
}
.follow-pill:hover { transform: scale(1.03); }
.follow-pill.is-followed { background: #e8e8ed; color: var(--text-secondary); }
.follow-pill:disabled { opacity: .6; cursor: default; }
.owner-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--hairline);
  border-radius: 16px;
  background: var(--surface);
}
.owner-avatar {
  flex: none;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  font-weight: 600;
  user-select: none;
}
.owner-body {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.owner-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
}
.owner-sub {
  margin-top: 3px;
  font-size: 12px;
  color: var(--text-tertiary);
}

.related-section { padding: 0; }
.related-title {
  margin: 24px 0 0;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--text);
}
.related-list {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.related-item {
  display: flex;
  gap: 12px;
  padding: 8px;
  border-radius: 14px;
  cursor: pointer;
  transition: transform 0.3s var(--ease), background 0.3s var(--ease), box-shadow 0.3s var(--ease);
}
.related-item:hover {
  transform: translateY(-2px);
  background: var(--surface);
  box-shadow: var(--shadow-card);
}
.related-thumb {
  position: relative;
  flex: none;
  width: 160px;
  aspect-ratio: 16/9;
  border-radius: 12px;
  overflow: hidden;
  background: #e8e8ed;
}
.related-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.related-thumb-placeholder {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--text-tertiary);
  background: linear-gradient(150deg, #ececf1, #e3e3e8);
}
.related-duration {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 2px 7px;
  border-radius: var(--radius-button);
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  color: #fff;
  font-size: 11px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.related-body {
  flex: 1;
  min-width: 0;
  padding-top: 2px;
}
.related-item-title {
  font-size: 14px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: -0.01em;
  color: var(--text);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.related-meta {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}
.related-meta span + span::before {
  content: '·';
  color: var(--text-tertiary);
  margin-right: 8px;
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

@media (max-width: 1024px) {
  .watch {
    flex-direction: column;
    gap: 0;
  }
  .watch-side {
    width: 100%;
  }
  .clickable { cursor: pointer; }
.owner-card .clickable:hover .owner-name { color: var(--accent); }
.owner-link { cursor: pointer; }
.owner-link:hover { color: var(--accent); }
.follow-pill {
  margin-left: auto; flex-shrink: 0; border: none; cursor: pointer;
  background: var(--accent); color: #fff; font-size: 13px; font-weight: 600;
  padding: 7px 18px; border-radius: 980px;
  transition: background .25s var(--ease), transform .25s var(--ease);
}
.follow-pill:hover { transform: scale(1.03); }
.follow-pill.is-followed { background: #e8e8ed; color: var(--text-secondary); }
.follow-pill:disabled { opacity: .6; cursor: default; }
.owner-card {
    margin-top: 16px;
  }
}
</style>
