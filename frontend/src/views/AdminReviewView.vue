<template>
  <div class="admin">
    <header class="page-head">
      <h1>审核中心</h1>
      <p class="sub">复核机审结果,通过后进入转码与发布流程</p>
    </header>

    <el-card shadow="never">
      <template #header>
        <div class="card-bar">
          <span class="card-title">待审核队列</span>
          <span v-if="queue.length" class="count-pill"><span class="dot"></span>{{ total }} 待审核</span>
        </div>
      </template>
      <el-empty v-if="!queue.length && !loading" description="队列为空" />
      <el-table v-else :data="queue" v-loading="loading">
        <el-table-column label="视频" min-width="260">
          <template #default="{ row }">
            <div class="v-title">
              {{ row.title }}
              <span class="claim-badge" :class="row.claimedBy ? 'claimed' : 'free'">
                {{ row.claimedBy ? '已被认领' : '可认领' }}
              </span>
            </div>
            <div class="v-meta">{{ row.originalFilename }} · {{ fmtSize(row.sizeBytes) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="机审结论" width="160">
          <template #default="{ row }">
            <span class="verdict" :class="'v-' + verdictTag(row.autoVerdict)">
              <span class="dot"></span>{{ row.autoVerdict }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="提交时间" width="180">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190">
          <template #default="{ row }">
            <el-button v-if="!row.claimedBy" size="small" round :loading="claiming" @click="claim(row.videoId)">认领</el-button>
            <el-button type="primary" size="small" round @click="openDetail(row.videoId)">审核</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" title="审核详情" width="720px" destroy-on-close @closed="destroyPlayer">
      <div v-if="detail" v-loading="detailLoading">
        <video ref="reviewPlayer" :src="detail.presignedUrl" controls class="review-player"></video>
        <h4 class="review-title">{{ detail.report ? videoTitle : '' }}</h4>
        <el-alert v-if="detail.report.autoVerdict === 'AUTO_SUSPECT'" type="warning" :closable="false"
                  title="机审标记为可疑,请重点检查" style="margin-bottom: 12px" />
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="机审结论">
            <span class="verdict" :class="'v-' + verdictTag(detail.report.autoVerdict)">
              <span class="dot"></span>{{ detail.report.autoVerdict }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="黑帧占比">{{ blackRatio ?? '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="checks" size="small" style="margin-bottom: 16px">
          <el-table-column prop="name" label="检查项" width="180" />
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <span class="verdict" :class="row.passed ? 'v-success' : 'v-danger'">
                <span class="dot"></span>{{ row.passed ? '通过' : '未过' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="明细" />
        </el-table>
        <div class="actions">
          <el-button type="success" round :loading="acting" @click="approve">通过并发布</el-button>
          <el-button type="danger" round :loading="acting" @click="rejectDialog = true">拒绝</el-button>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="rejectDialog" title="拒绝原因" width="480px" append-to-body>
      <el-input v-model="rejectReason" type="textarea" :rows="3" placeholder="必填,将展示给投稿人" />
      <template #footer>
        <el-button round @click="rejectDialog = false">取消</el-button>
        <el-button type="danger" round :loading="acting" @click="reject">确认拒绝</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api.js'
import { auth } from '../auth.js'

const queue = ref([])
const total = ref(0)
const loading = ref(true)
const dialog = ref(false)
const detail = ref(null)
const detailLoading = ref(false)
const acting = ref(false)
const claiming = ref(false)
const rejectDialog = ref(false)
const rejectReason = ref('')
const reviewPlayer = ref(null)
let currentVideoId = null
let pollTimer = null

const videoTitle = computed(() => {
  const item = queue.value.find((q) => q.videoId === currentVideoId)
  return item?.title || ''
})
const report = computed(() => {
  const r = detail.value?.report
  if (!r) return null
  let auto = r.autoReport
  if (typeof auto === 'string') {
    try { auto = JSON.parse(auto) } catch { auto = {} }
  }
  return { ...r, autoReport: auto || {} }
})
const blackRatio = computed(() => report.value?.autoReport?.black_ratio)
const checks = computed(() => report.value?.autoReport?.checks || [])

async function load() {
  try {
    const data = await http.get('/admin/moderation/queue', { params: { page: 1, size: 50 } })
    queue.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

async function openDetail(videoId) {
  currentVideoId = videoId
  detail.value = null
  detailLoading.value = true
  dialog.value = true
  claimSilently(videoId)
  try {
    detail.value = await http.get(`/admin/moderation/${videoId}`)
  } finally {
    detailLoading.value = false
  }
}

async function claim(videoId) {
  if (claiming.value) return
  claiming.value = true
  try {
    await http.post(`/admin/moderation/${videoId}/claim`)
    ElMessage.success('已认领')
    load()
  } catch {
    // 失败原因由 http 拦截器统一提示(如已被其他审核员认领)
  } finally {
    claiming.value = false
  }
}

/** 打开审核详情时静默认领:直接走 fetch,失败(可能已被他人认领)不弹错误,仅提示返回队列。 */
async function claimSilently(videoId) {
  try {
    const resp = await fetch(`/api/admin/moderation/${videoId}/claim`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${auth.token()}` }
    })
    const body = await resp.json()
    if (body.code === 0) {
      load()
    } else {
      ElMessage.warning('该任务可能已被其他审核员认领,请返回队列刷新')
    }
  } catch {
    ElMessage.warning('该任务可能已被其他审核员认领,请返回队列刷新')
  }
}

async function approve() {
  acting.value = true
  try {
    await http.post(`/admin/moderation/${currentVideoId}/approve`)
    ElMessage.success('已通过,进入转码队列')
    close()
    load()
  } finally {
    acting.value = false
  }
}

async function reject() {
  if (!rejectReason.value.trim()) {
    ElMessage.warning('请填写拒绝原因')
    return
  }
  acting.value = true
  try {
    await http.post(`/admin/moderation/${currentVideoId}/reject`, { reason: rejectReason.value.trim() })
    ElMessage.success('已拒绝')
    rejectDialog.value = false
    close()
    load()
  } finally {
    acting.value = false
  }
}

function close() {
  dialog.value = false
  destroyPlayer()
  detail.value = null
  rejectReason.value = ''
}

function destroyPlayer() {
  if (reviewPlayer.value) {
    reviewPlayer.value.pause()
  }
}

function verdictTag(v) {
  return { AUTO_PASS: 'success', AUTO_SUSPECT: 'warning', AUTO_FAIL: 'danger' }[v] || 'info'
}

function fmtSize(b) {
  if (!b) return '-'
  return b > 1024 * 1024 * 1024
    ? `${(b / 1024 / 1024 / 1024).toFixed(1)} GB`
    : `${(b / 1024 / 1024).toFixed(1)} MB`
}
function fmtTime(d) {
  return new Date(d).toLocaleString('zh-CN')
}

onMounted(() => {
  load()
  pollTimer = setInterval(load, 10000)
})
onBeforeUnmount(() => pollTimer && clearInterval(pollTimer))
</script>

<style scoped>
.admin { max-width: 1100px; margin: 24px auto 56px; }

.page-head { margin-bottom: 24px; }
.page-head h1 { margin: 0 0 10px; font-size: clamp(32px, 5vw, 40px); line-height: 1.1; }
.page-head .sub { margin: 0; font-size: 17px; color: var(--text-secondary); }

.card-bar { display: flex; align-items: center; justify-content: space-between; }
.card-title { font-size: 16px; font-weight: 600; }
.count-pill {
  display: inline-flex; align-items: center; gap: 8px; padding: 6px 14px;
  border-radius: var(--radius-button); background: rgba(227, 0, 0, 0.07);
  color: var(--danger); font-size: 13px; font-weight: 500;
}

.v-title { font-size: 15px; font-weight: 500; }
.v-meta { margin-top: 4px; font-size: 12px; color: var(--text-tertiary); }

.claim-badge {
  margin-left: 8px; padding: 2px 8px; border-radius: 999px;
  font-size: 11px; font-weight: 500; white-space: nowrap; vertical-align: 2px;
}
.claim-badge.claimed { background: rgba(120, 120, 128, 0.12); color: var(--text-secondary); }
.claim-badge.free { background: rgba(48, 209, 88, 0.12); color: var(--success); }

.verdict { display: inline-flex; align-items: center; gap: 7px; font-size: 13px; }
.dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
.v-success { color: var(--success); }
.v-warning { color: var(--warning); }
.v-danger { color: var(--danger); }
.v-info { color: var(--text-tertiary); }

.review-player { display: block; width: 100%; max-height: 400px; background: #000; border-radius: 16px; }
.review-title { margin: 16px 0 12px; font-size: 17px; }
.actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 20px; }
</style>
