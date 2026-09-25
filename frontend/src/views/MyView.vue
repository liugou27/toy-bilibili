<template>
  <div class="my">
    <header class="page-head">
      <h1>我的投稿</h1>
      <p class="sub">进行中的投稿每 5 秒自动刷新</p>
    </header>

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
            <el-button v-if="row.status === 'TRANSCODE_FAILED'" size="small" round
                       :loading="retrying === row.id" @click="retry(row.id)">重试转码</el-button>
          </div>
        </li>
      </ul>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api.js'

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

.pending-pill {
  display: inline-flex; align-items: center; gap: 8px; margin-bottom: 16px; padding: 6px 14px;
  border-radius: var(--radius-button); background: rgba(0, 113, 227, 0.08);
  color: var(--accent); font-size: 13px; font-weight: 500;
}

.rows { list-style: none; margin: 0; padding: 0; }
.row { display: flex; align-items: center; gap: 16px; padding: 16px 2px; border-bottom: 1px solid var(--hairline); }
.row:last-child { border-bottom: none; }

.poster { width: 120px; aspect-ratio: 16 / 9; flex: none; object-fit: cover; border-radius: 10px; background: #ececf0; }
.poster-placeholder { display: flex; align-items: center; justify-content: center; color: var(--text-tertiary); font-size: 12px; }

.row-main { flex: 1; min-width: 0; }
.row-title { font-size: 16px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-meta { margin-top: 4px; font-size: 13px; color: var(--text-tertiary); }

.row-status { flex: none; width: 140px; display: flex; align-items: center; gap: 8px; }
.status-text { font-size: 14px; color: var(--text); }

.row-note { flex: none; width: 200px; }
.note { font-size: 13px; color: var(--text-secondary); }
.muted { color: var(--text-tertiary); }

.row-actions { flex: none; }

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
