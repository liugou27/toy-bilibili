<template>
  <div class="my">
    <header class="page-head">
      <h1>我的空间</h1>
      <p class="sub">投稿、收藏、历史与关注</p>
    </header>

    <div class="seg" role="tablist">
      <button v-for="t in TABS" :key="t.key" class="seg-item" :class="{ active: tab === t.key }"
              role="tab" :aria-selected="tab === t.key" @click="switchTab(t.key)">{{ t.label }}</button>
    </div>

    <Transition name="tab-fade" mode="out-in">
      <!-- 我的投稿 -->
      <section v-if="tab === 'videos'" key="videos" class="tab-pane">
        <p class="tab-sub">进行中的投稿每 5 秒自动刷新</p>
        <el-card shadow="never">
          <span v-if="pendingCount > 0" class="pending-pill">
            <span class="dot dot-blue is-pulse"></span>
            {{ pendingCount }} 个进行中(自动刷新)
          </span>
          <el-empty v-if="!videos.length && !loading" description="还没有投稿,点右上角「投稿」试试" />
          <ul v-else class="rows" v-loading="loading">
            <li v-for="row in videos" :key="row.id" class="row">
              <img v-if="row.poster" :src="row.poster" class="poster" loading="lazy" />
              <div v-else class="poster poster-placeholder">无封面</div>
              <div class="row-main">
                <div class="row-title">{{ row.title }}</div>
                <div class="row-meta">{{ fmtCount(row.playCount) }} 播放</div>
              </div>
              <div class="row-status">
                <span class="dot" :class="dotClass(row.status)"></span>
                <span class="status-text">{{ statusText(row.status) }}</span>
              </div>
              <div class="row-note">
                <span v-if="row.note" class="note">{{ row.note }}</span>
                <span v-else class="note muted">{{ statusHint(row.status) }}</span>
              </div>
              <div class="row-actions">
                <el-button v-if="row.status === 'PUBLISHED'" text type="primary"
                           @click="$router.push(`/watch/${row.id}`)">查看</el-button>
                <el-popover :ref="(el) => setPopRef(row.id, el)" placement="bottom-end" :width="320"
                            trigger="click" @show="openEdit(row)">
                  <template #reference>
                    <el-button text type="primary">编辑</el-button>
                  </template>
                  <div v-loading="descLoading" class="edit-form">
                    <el-input v-model="editForm.title" maxlength="100" show-word-limit placeholder="标题" />
                    <el-input v-model="editForm.description" type="textarea" :rows="3" maxlength="2000"
                              show-word-limit placeholder="简介(选填)" />
                    <div class="edit-actions">
                      <el-button size="small" @click="cancelEdit(row.id)">取消</el-button>
                      <el-button size="small" type="primary" :loading="saving" @click="saveEdit">保存</el-button>
                    </div>
                  </div>
                </el-popover>
                <el-button text type="danger" :loading="deleting === row.id" @click="remove(row)">删除</el-button>
                <el-button v-if="row.status === 'TRANSCODE_FAILED'" size="small" round
                           :loading="retrying === row.id" @click="retry(row.id)">重试转码</el-button>
              </div>
            </li>
          </ul>
        </el-card>
      </section>

      <!-- 我的收藏 -->
      <section v-else-if="tab === 'favorites'" key="favorites" class="tab-pane">
        <el-card shadow="never">
          <el-empty v-if="!fav.list.length && !fav.loading" description="还没有收藏,去播放页点收藏吧" />
          <template v-else>
            <ul class="rows rows-clickable" v-loading="fav.loading">
              <li v-for="row in fav.list" :key="row.id" class="row row-link"
                  @click="$router.push(`/watch/${row.id}`)">
                <img v-if="row.poster" :src="row.poster" class="poster" loading="lazy" />
                <div v-else class="poster poster-placeholder">无封面</div>
                <div class="row-main">
                  <div class="row-title">{{ row.title }}</div>
                  <div class="row-meta">{{ fmtCount(row.playCount) }} 播放 · {{ row.ownerName }}</div>
                </div>
                <span class="row-chevron">›</span>
              </li>
            </ul>
            <div v-if="fav.total > PAGE_SIZE" class="pager">
              <el-button text :disabled="fav.page <= 1" @click="goPage(fav, fav.page - 1, loadFavorites)">上一页</el-button>
              <span class="pager-info">{{ fav.page }} / {{ pageCount(fav.total) }}</span>
              <el-button text :disabled="fav.page >= pageCount(fav.total)"
                         @click="goPage(fav, fav.page + 1, loadFavorites)">下一页</el-button>
            </div>
          </template>
        </el-card>
      </section>

      <!-- 播放历史 -->
      <section v-else-if="tab === 'history'" key="history" class="tab-pane">
        <el-card shadow="never">
          <el-empty v-if="!hist.list.length && !hist.loading" description="还没有观看记录,去发现页看看吧" />
          <template v-else>
            <ul class="rows rows-clickable" v-loading="hist.loading">
              <li v-for="row in hist.list" :key="row.id" class="row row-link"
                  @click="$router.push(`/watch/${row.id}`)">
                <img v-if="row.poster" :src="row.poster" class="poster" loading="lazy" />
                <div v-else class="poster poster-placeholder">无封面</div>
                <div class="row-main">
                  <div class="row-title">{{ row.title }}</div>
                  <div class="row-meta">{{ fmtCount(row.playCount) }} 播放 · {{ row.ownerName }}</div>
                </div>
                <div class="row-progress">看到 {{ fmtPos(row.positionSec) }}</div>
                <span class="row-chevron">›</span>
              </li>
            </ul>
            <div v-if="hist.total > PAGE_SIZE" class="pager">
              <el-button text :disabled="hist.page <= 1" @click="goPage(hist, hist.page - 1, loadHistory)">上一页</el-button>
              <span class="pager-info">{{ hist.page }} / {{ pageCount(hist.total) }}</span>
              <el-button text :disabled="hist.page >= pageCount(hist.total)"
                         @click="goPage(hist, hist.page + 1, loadHistory)">下一页</el-button>
            </div>
          </template>
        </el-card>
      </section>

      <!-- 我的关注 -->
      <section v-else-if="tab === 'follows'" key="follows" class="tab-pane">
        <el-card shadow="never">
          <el-empty v-if="!follows.list.length && !follows.loading" description="还没有关注的人" />
          <template v-else>
            <ul class="rows rows-clickable" v-loading="follows.loading">
              <li v-for="row in follows.list" :key="row.userId" class="row row-link"
                  @click="$router.push(`/uploader/${row.userId}`)">
                <img v-if="row.avatar" :src="row.avatar" class="u-avatar" loading="lazy" />
                <span v-else class="u-avatar u-avatar-fallback">{{ avatarChar(row) }}</span>
                <div class="row-main">
                  <div class="row-title">{{ row.nickname || row.username }}</div>
                  <div class="row-meta">@{{ row.username }} · 关注于 {{ fmtDate(row.followedAt) }}</div>
                </div>
                <span class="row-chevron">›</span>
              </li>
            </ul>
            <div v-if="follows.total > PAGE_SIZE" class="pager">
              <el-button text :disabled="follows.page <= 1" @click="goPage(follows, follows.page - 1, loadFollows)">上一页</el-button>
              <span class="pager-info">{{ follows.page }} / {{ pageCount(follows.total) }}</span>
              <el-button text :disabled="follows.page >= pageCount(follows.total)"
                         @click="goPage(follows, follows.page + 1, loadFollows)">下一页</el-button>
            </div>
          </template>
        </el-card>
      </section>

      <!-- 我的粉丝 -->
      <section v-else key="fans" class="tab-pane">
        <el-card shadow="never">
          <el-empty v-if="!fans.list.length && !fans.loading" description="还没有粉丝" />
          <template v-else>
            <ul class="rows rows-clickable" v-loading="fans.loading">
              <li v-for="row in fans.list" :key="row.userId" class="row row-link"
                  @click="$router.push(`/uploader/${row.userId}`)">
                <img v-if="row.avatar" :src="row.avatar" class="u-avatar" loading="lazy" />
                <span v-else class="u-avatar u-avatar-fallback">{{ avatarChar(row) }}</span>
                <div class="row-main">
                  <div class="row-title">{{ row.nickname || row.username }}</div>
                  <div class="row-meta">@{{ row.username }} · 关注于 {{ fmtDate(row.followedAt) }}</div>
                </div>
                <span class="row-chevron">›</span>
              </li>
            </ul>
            <div v-if="fans.total > PAGE_SIZE" class="pager">
              <el-button text :disabled="fans.page <= 1" @click="goPage(fans, fans.page - 1, loadFans)">上一页</el-button>
              <span class="pager-info">{{ fans.page }} / {{ pageCount(fans.total) }}</span>
              <el-button text :disabled="fans.page >= pageCount(fans.total)"
                         @click="goPage(fans, fans.page + 1, loadFans)">下一页</el-button>
            </div>
          </template>
        </el-card>
      </section>
    </Transition>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api.js'
import { auth } from '../auth.js'

const TABS = [
  { key: 'videos', label: '我的投稿' },
  { key: 'favorites', label: '我的收藏' },
  { key: 'history', label: '播放历史' },
  { key: 'follows', label: '我的关注' },
  { key: 'fans', label: '我的粉丝' }
]
const PAGE_SIZE = 20
const tab = ref('videos')

const TERMINAL = new Set(['PUBLISHED', 'REJECTED'])
const videos = ref([])
const loading = ref(true)
const retrying = ref(null)
let timer = null

const pendingCount = computed(() =>
  videos.value.filter((v) => !TERMINAL.has(v.status)).length)

async function load() {
  try {
    const data = await http.get('/my/videos', { params: { page: 1, size: 50 } })
    videos.value = data.list
    // 有进行中的投稿时 5 秒轮询,全部终态后停止
    if (pendingCount.value > 0 && !timer) {
      timer = setInterval(load, 5000)
    } else if (pendingCount.value === 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  } finally {
    loading.value = false
  }
}

async function retry(id) {
  retrying.value = id
  try {
    await http.post(`/videos/${id}/retry`)
    ElMessage.success('已重新提交转码')
    load()
  } finally {
    retrying.value = null
  }
}

// —— 编辑 / 删除 ——
const popRefs = new Map()
const saving = ref(false)
const descLoading = ref(false)
const deleting = ref(null)
const editingId = ref(null)
const editForm = reactive({ title: '', description: '' })

function setPopRef(id, el) {
  if (el) popRefs.set(id, el)
  else popRefs.delete(id)
}

function openEdit(row) {
  editingId.value = row.id
  editForm.title = row.title
  editForm.description = ''
  descLoading.value = true
  // 列表接口不含简介,打开时拉取详情补齐,避免保存时误清空
  http.get(`/videos/${row.id}`)
    .then((d) => {
      if (editingId.value === row.id) editForm.description = d.description || ''
    })
    .catch(() => {})
    .finally(() => {
      descLoading.value = false
    })
}

function cancelEdit(id) {
  popRefs.get(id)?.hide()
  editingId.value = null
}

async function saveEdit() {
  if (!editForm.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  saving.value = true
  try {
    await http.patch(`/videos/${editingId.value}`, {
      title: editForm.title.trim(),
      description: editForm.description.trim()
    })
    ElMessage.success('已保存')
    popRefs.get(editingId.value)?.hide()
    editingId.value = null
    load()
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.title}」吗?删除后不可恢复。`, '删除投稿', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  deleting.value = row.id
  try {
    await http.delete(`/videos/${row.id}`)
    ElMessage.success('已删除')
    load()
  } finally {
    deleting.value = null
  }
}

// —— 收藏 / 历史 / 关注 / 粉丝(懒加载,进入 tab 才拉取,再次进入简单刷新) ——
const fav = reactive({ list: [], total: 0, page: 1, loading: false })
const hist = reactive({ list: [], total: 0, page: 1, loading: false })
const follows = reactive({ list: [], total: 0, page: 1, loading: false })
const fans = reactive({ list: [], total: 0, page: 1, loading: false })

function switchTab(key) {
  if (tab.value === key) return
  tab.value = key
  if (key === 'favorites') loadFavorites()
  else if (key === 'history') loadHistory()
  else if (key === 'follows') loadFollows()
  else if (key === 'fans') loadFans()
}

async function fetchPage(state, url) {
  state.loading = true
  try {
    const data = await http.get(url, { params: { page: state.page, size: PAGE_SIZE } })
    state.list = data.list || []
    state.total = data.total || 0
  } finally {
    state.loading = false
  }
}
const loadFavorites = () => fetchPage(fav, '/my/videos/favorites')
const loadHistory = () => fetchPage(hist, '/my/videos/history')
const loadFollows = () => fetchPage(follows, `/users/${auth.user.id}/follows`)
const loadFans = () => fetchPage(fans, `/users/${auth.user.id}/fans`)

function goPage(state, page, loader) {
  state.page = page
  loader()
}

function pageCount(total) {
  return Math.max(1, Math.ceil(total / PAGE_SIZE))
}

onMounted(load)
onBeforeUnmount(() => timer && clearInterval(timer))

const STATUS_MAP = {
  UPLOADED: ['info', '已上传'],
  AUTO_SCREENING: ['primary', '机审中'],
  UNDER_REVIEW: ['warning', '等待人工审核'],
  APPROVED: ['success', '审核通过'],
  TRANSCODING: ['primary', '转码中'],
  PUBLISHED: ['success', '已发布'],
  REJECTED: ['danger', '未通过'],
  TRANSCODE_FAILED: ['danger', '转码失败']
}
function statusTag(s) { return (STATUS_MAP[s] || ['info'])[0] }
function statusText(s) { return (STATUS_MAP[s] || [null, s])[1] }
function statusHint(s) {
  return { AUTO_SCREENING: '正在抽帧检测,约 1 分钟', TRANSCODING: '正在转码 HLS,请稍候', UNDER_REVIEW: '管理员审核中' }[s] || ''
}
function fmtCount(n) { return n >= 10000 ? `${(n / 10000).toFixed(1)}万` : String(n || 0) }

/** 关注/粉丝行:头像兜底取昵称或用户名首字 */
function avatarChar(row) {
  return (row.nickname || row.username || 'U').slice(0, 1).toUpperCase()
}

function fmtDate(d) {
  return d ? new Date(d).toLocaleDateString('zh-CN') : ''
}

/** 秒 → m:ss / h:mm:ss */
function fmtPos(sec) {
  const s = Math.max(0, Math.floor(sec || 0))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const r = s % 60
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m)
  return h > 0 ? `${h}:${mm}:${String(r).padStart(2, '0')}` : `${mm}:${String(r).padStart(2, '0')}`
}

/** 状态圆点配色:绿色=已发布/通过,蓝色脉冲=进行中,琥珀=等待人工,红色=失败 */
function dotClass(s) {
  if (s === 'PUBLISHED' || s === 'APPROVED') return 'dot-green'
  if (s === 'REJECTED' || s === 'TRANSCODE_FAILED') return 'dot-red'
  if (s === 'UNDER_REVIEW') return 'dot-amber'
  return 'dot-blue is-pulse'
}
</script>

<style scoped>
.my { max-width: 1000px; margin: 24px auto 56px; }

.page-head { margin-bottom: 24px; }
.page-head h1 { margin: 0 0 10px; font-size: clamp(32px, 5vw, 40px); line-height: 1.1; }
.page-head .sub { margin: 0; font-size: 17px; color: var(--text-secondary); }

/* Apple 式分段控件 */
.seg {
  display: inline-flex; gap: 4px; padding: 3px; margin-bottom: 20px;
  background: rgba(120, 120, 128, 0.12); border-radius: 10px;
}
.seg-item {
  border: none; background: transparent; cursor: pointer;
  padding: 6px 18px; border-radius: 8px;
  font-size: 14px; font-weight: 500; font-family: inherit; color: var(--text-secondary);
  transition: color 0.25s var(--ease), background 0.25s var(--ease), box-shadow 0.25s var(--ease);
}
.seg-item:hover { color: var(--text); }
.seg-item.active {
  background: #fff; color: var(--text);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12), 0 0 0 0.5px rgba(0, 0, 0, 0.04);
}

.tab-pane { display: block; }
.tab-sub { margin: 0 0 12px; font-size: 14px; color: var(--text-tertiary); }

.tab-fade-enter-active, .tab-fade-leave-active { transition: opacity 0.22s var(--ease), transform 0.22s var(--ease); }
.tab-fade-enter-from, .tab-fade-leave-to { opacity: 0; transform: translateY(6px); }

.pending-pill {
  display: inline-flex; align-items: center; gap: 8px; margin-bottom: 16px; padding: 6px 14px;
  border-radius: var(--radius-button); background: rgba(0, 113, 227, 0.08);
  color: var(--accent); font-size: 13px; font-weight: 500;
}

.rows { list-style: none; margin: 0; padding: 0; }
.row { display: flex; align-items: center; gap: 16px; padding: 16px 2px; border-bottom: 1px solid var(--hairline); }
.row:last-child { border-bottom: none; }

.rows-clickable .row { margin: 0 -12px; padding: 16px 14px; border-radius: 10px; transition: background 0.2s var(--ease); }
.rows-clickable .row:hover { background: rgba(120, 120, 128, 0.08); }
.row-link { cursor: pointer; }
.row-link:hover .row-title { color: var(--accent); }

.poster { width: 120px; aspect-ratio: 16 / 9; flex: none; object-fit: cover; border-radius: 10px; background: #ececf0; }
.poster-placeholder { display: flex; align-items: center; justify-content: center; color: var(--text-tertiary); font-size: 12px; }

/* 关注/粉丝行的圆形头像 */
.u-avatar {
  flex: none; width: 44px; height: 44px; border-radius: 50%; object-fit: cover;
  background: linear-gradient(150deg, var(--accent), #5ac8fa);
}
.u-avatar-fallback {
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 18px; font-weight: 600;
}

.row-main { flex: 1; min-width: 0; }
.row-title { font-size: 16px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; transition: color 0.2s var(--ease); }
.row-meta { margin-top: 4px; font-size: 13px; color: var(--text-tertiary); }

.row-status { flex: none; width: 140px; display: flex; align-items: center; gap: 8px; }
.status-text { font-size: 14px; color: var(--text); }

.row-note { flex: none; width: 200px; }
.note { font-size: 13px; color: var(--text-secondary); }
.muted { color: var(--text-tertiary); }

.row-actions { flex: none; }

.row-progress { flex: none; font-size: 13px; color: var(--text-secondary); font-variant-numeric: tabular-nums; }
.row-chevron { flex: none; color: var(--text-tertiary); font-size: 20px; line-height: 1; transition: color 0.2s var(--ease); }
.row-link:hover .row-chevron { color: var(--accent); }

.pager { display: flex; align-items: center; justify-content: center; gap: 12px; padding-top: 16px; }
.pager-info { font-size: 13px; color: var(--text-tertiary); font-variant-numeric: tabular-nums; }

.edit-form { display: flex; flex-direction: column; gap: 10px; }
.edit-actions { display: flex; justify-content: flex-end; gap: 8px; }

.dot { flex: none; width: 8px; height: 8px; border-radius: 50%; }
.dot-green { background: var(--success); }
.dot-blue { background: var(--accent); }
.dot-red { background: var(--danger); }
.dot-amber { background: var(--warning); }
.dot-blue.is-pulse { animation: dot-pulse 1.8s ease-in-out infinite; }

@keyframes dot-pulse {
  0% { box-shadow: 0 0 0 0 rgba(0, 113, 227, 0.35); }
  70% { box-shadow: 0 0 0 7px rgba(0, 113, 227, 0); }
  100% { box-shadow: 0 0 0 0 rgba(0, 113, 227, 0); }
}
</style>
