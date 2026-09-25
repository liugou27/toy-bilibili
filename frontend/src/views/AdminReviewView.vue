<template>
  <div class="admin">
    <el-card shadow="never">
      <template #header>
        <b>审核后台</b>
        <el-tag v-if="queue.length" type="danger" style="margin-left: 8px">{{ total }} 待审核</el-tag>
      </template>
      <el-empty v-if="!queue.length && !loading" description="队列为空" />
      <el-table v-else :data="queue" v-loading="loading">
        <el-table-column label="视频" min-width="260">
          <template #default="{ row }">
            <div class="v-title">{{ row.title }}</div>
            <div class="v-meta">{{ row.originalFilename }} · {{ fmtSize(row.sizeBytes) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="机审结论" width="150">
          <template #default="{ row }">
            <el-tag :type="verdictTag(row.autoVerdict)">{{ row.autoVerdict }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="提交时间" width="180">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button type="primary" size="small" @click="openDetail(row.videoId)">审核</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" title="审核详情" width="720px" destroy-on-close @closed="destroyPlayer">
      <div v-if="detail" v-loading="detailLoading">
        <video ref="reviewPlayer" :src="detail.presignedUrl" controls class="review-player"></video>
        <h4>{{ detail.report ? videoTitle : '' }}</h4>
        <el-alert v-if="detail.report.autoVerdict === 'AUTO_SUSPECT'" type="warning" :closable="false"
                  title="机审标记为可疑,请重点检查" style="margin-bottom: 12px" />
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="机审结论">
            <el-tag :type="verdictTag(detail.report.autoVerdict)">{{ detail.report.autoVerdict }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="黑帧占比">{{ blackRatio ?? '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="checks" size="small" style="margin-bottom: 16px">
          <el-table-column prop="name" label="检查项" width="180" />
          <el-table-column label="结果" width="80">
            <template #default="{ row }">
              <el-tag :type="row.passed ? 'success' : 'danger'" size="small">
                {{ row.passed ? '通过' : '未过' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="明细" />
        </el-table>
        <div class="actions">
          <el-button type="success" :loading="acting" @click="approve">通过并发布</el-button>
          <el-button type="danger" :loading="acting" @click="rejectDialog = true">拒绝</el-button>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="rejectDialog" title="拒绝原因" width="480px" append-to-body>
      <el-input v-model="rejectReason" type="textarea" :rows="3" placeholder="必填,将展示给投稿人" />
      <template #footer>
        <el-button @click="rejectDialog = false">取消</el-button>
        <el-button type="danger" :loading="acting" @click="reject">确认拒绝</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api.js'

const queue = ref([])
const total = ref(0)
const loading = ref(true)
const dialog = ref(false)
const detail = ref(null)
const detailLoading = ref(false)
const acting = ref(false)
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
  try {
    detail.value = await http.get(`/admin/moderation/${videoId}`)
  } finally {
    detailLoading.value = false
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
.admin { max-width: 1100px; margin: 24px auto; }
.review-player { width: 100%; max-height: 400px; background: #000; border-radius: 6px; }
.actions { display: flex; justify-content: flex-end; gap: 8px; }
.v-title { font-size: 14px; }
.v-meta { font-size: 12px; color: #999; margin-top: 4px; }
</style>
