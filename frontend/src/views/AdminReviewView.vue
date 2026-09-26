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

    <el-card shadow="never" class="sw-card">
      <template #header>
        <div class="card-bar">
          <span class="card-title">敏感词库</span>
          <div class="sw-head-actions">
            <el-input v-model="wKeyword" class="sw-search" placeholder="搜索敏感词" clearable @input="onKeywordInput" />
            <el-button size="small" round @click="openImport">批量导入</el-button>
            <el-button size="small" round type="primary" @click="openAdd">新增</el-button>
          </div>
        </div>
      </template>
      <el-empty v-if="!words.length && !wLoading" description="词库为空" />
      <el-table v-else :data="words" v-loading="wLoading">
        <el-table-column label="词" min-width="200">
          <template #default="{ row }"><span class="sw-word">{{ row.word }}</span></template>
        </el-table-column>
        <el-table-column label="级别" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.level === 'REJECT' ? 'danger' : 'warning'">
              {{ row.level === 'REJECT' ? '拒绝' : '人工审核' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="140">
          <template #default="{ row }">{{ row.category || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ row.status === 'ENABLED' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button size="small" round :loading="busyId === row.id" :disabled="busyId !== null && busyId !== row.id"
                       @click="toggleStatus(row)">
              {{ row.status === 'ENABLED' ? '停用' : '启用' }}
            </el-button>
            <el-button size="small" round type="danger" plain :disabled="busyId !== null" @click="removeWord(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="wTotal > W_SIZE" class="sw-foot">
        <el-pagination
          size="small" background layout="prev, pager, next"
          :total="wTotal" :page-size="W_SIZE"
          v-model:current-page="wPage" @current-change="loadWords"
        />
      </div>
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

    <el-dialog v-model="wordDialog" title="新增敏感词" width="440px" append-to-body>
      <el-form label-width="56px">
        <el-form-item label="词语" required>
          <el-input v-model="wordForm.word" maxlength="50" placeholder="必填" @keyup.enter="addWord" />
        </el-form-item>
        <el-form-item label="级别">
          <el-radio-group v-model="wordForm.level">
            <el-radio value="REJECT">拒绝</el-radio>
            <el-radio value="REVIEW">人工审核</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="wordForm.category" maxlength="30" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button round @click="wordDialog = false">取消</el-button>
        <el-button type="primary" round :loading="adding" @click="addWord">新增</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importDialog" title="批量导入敏感词" width="480px" append-to-body>
      <div class="sw-import-level">
        <span class="sw-import-label">级别</span>
        <el-radio-group v-model="importLevel">
          <el-radio value="REJECT">拒绝</el-radio>
          <el-radio value="REVIEW">人工审核</el-radio>
        </el-radio-group>
      </div>
      <el-input v-model="importText" type="textarea" :rows="8" placeholder="每行一个敏感词" />
      <p class="sw-import-hint">将导入 {{ importCount }} 个词,自动去空行与重复</p>
      <template #footer>
        <el-button round @click="importDialog = false">取消</el-button>
        <el-button type="primary" round :loading="importing" :disabled="!importCount" @click="importWords">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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

const W_SIZE = 10
const words = ref([])
const wTotal = ref(0)
const wPage = ref(1)
const wKeyword = ref('')
const wLoading = ref(false)
const wordDialog = ref(false)
const adding = ref(false)
const importDialog = ref(false)
const importing = ref(false)
const importText = ref('')
const importLevel = ref('REJECT')
const busyId = ref(null)
const wordForm = ref({ word: '', level: 'REJECT', category: '' })
let kwTimer = null

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

const importCount = computed(() => parseImport().length)

function parseImport() {
  return [...new Set(importText.value.split(/\r?\n/).map((s) => s.trim()).filter(Boolean))]
}

async function loadWords() {
  wLoading.value = true
  try {
    const data = await http.get('/admin/sensitive-words', {
      params: { page: wPage.value, size: W_SIZE, keyword: wKeyword.value.trim() || undefined }
    })
    words.value = data.list
    wTotal.value = data.total
  } finally {
    wLoading.value = false
  }
}

function onKeywordInput() {
  clearTimeout(kwTimer)
  kwTimer = setTimeout(() => {
    wPage.value = 1
    loadWords()
  }, 300)
}

function openAdd() {
  wordForm.value = { word: '', level: 'REJECT', category: '' }
  wordDialog.value = true
}

async function addWord() {
  const word = wordForm.value.word.trim()
  if (!word) {
    ElMessage.warning('请输入敏感词')
    return
  }
  adding.value = true
  try {
    await http.post('/admin/sensitive-words', {
      word,
      level: wordForm.value.level,
      category: wordForm.value.category.trim() || undefined
    })
    ElMessage.success('已新增,词库即时生效')
    wordDialog.value = false
    loadWords()
  } finally {
    adding.value = false
  }
}

function openImport() {
  importText.value = ''
  importLevel.value = 'REJECT'
  importDialog.value = true
}

async function importWords() {
  const parsed = parseImport()
  if (!parsed.length) {
    ElMessage.warning('请输入至少一个敏感词')
    return
  }
  importing.value = true
  try {
    await http.post('/admin/sensitive-words/import', { words: parsed, level: importLevel.value })
    ElMessage.success(`已导入 ${parsed.length} 个词,词库即时生效`)
    importDialog.value = false
    loadWords()
  } finally {
    importing.value = false
  }
}

async function toggleStatus(row) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  const label = next === 'DISABLED' ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确定${label}敏感词「${row.word}」吗?${next === 'DISABLED' ? '停用后该词不再参与匹配。' : ''}`,
      `${label}敏感词`,
      { type: 'warning', confirmButtonText: label, cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  busyId.value = row.id
  try {
    await http.patch(`/admin/sensitive-words/${row.id}/status`, { status: next })
    ElMessage.success(`已${label}`)
    loadWords()
  } finally {
    busyId.value = null
  }
}

async function removeWord(row) {
  try {
    await ElMessageBox.confirm(`确定删除敏感词「${row.word}」吗?删除后不可恢复。`, '删除敏感词', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  busyId.value = row.id
  try {
    await http.delete(`/admin/sensitive-words/${row.id}`)
    ElMessage.success('已删除')
    const lastPage = Math.max(1, Math.ceil((wTotal.value - 1) / W_SIZE))
    if (wPage.value > lastPage) wPage.value = lastPage
    loadWords()
  } finally {
    busyId.value = null
  }
}

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
  loadWords()
  pollTimer = setInterval(load, 10000)
})
onBeforeUnmount(() => {
  pollTimer && clearInterval(pollTimer)
  clearTimeout(kwTimer)
})
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

.sw-card { margin-top: 24px; }
.sw-head-actions { display: flex; align-items: center; gap: 8px; }
.sw-search { width: 180px; }
.sw-search :deep(.el-input__wrapper) { border-radius: var(--radius-button); }
.sw-word { font-weight: 500; }
.sw-foot { display: flex; justify-content: flex-end; margin-top: 16px; }
.sw-import-level { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.sw-import-label { font-size: 14px; color: var(--text-secondary); }
.sw-import-hint { margin: 8px 0 0; font-size: 12px; color: var(--text-tertiary); }
</style>
