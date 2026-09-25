<template>
  <div class="my">
    <el-card shadow="never">
      <template #header>
        <b>我的投稿</b>
        <el-tag v-if="pendingCount > 0" type="info" style="margin-left: 8px">
          {{ pendingCount }} 个进行中(自动刷新)
        </el-tag>
      </template>
      <el-empty v-if="!videos.length && !loading" description="还没有投稿,点右上角「投稿」试试" />
      <el-table v-else :data="videos" v-loading="loading">
        <el-table-column label="视频" min-width="280">
          <template #default="{ row }">
            <div class="v-cell">
              <img v-if="row.poster" :src="row.poster" class="v-poster" />
              <div v-else class="v-poster v-placeholder">无封面</div>
              <div>
                <div class="v-title">{{ row.title }}</div>
                <div class="v-meta">{{ fmtCount(row.playCount) }} 播放</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="170">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="180">
          <template #default="{ row }">
            <span v-if="row.note" class="note">{{ row.note }}</span>
            <span v-else class="note muted">{{ statusHint(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PUBLISHED'" text type="primary"
                       @click="$router.push(`/watch/${row.id}`)">查看</el-button>
            <el-button v-if="row.status === 'TRANSCODE_FAILED'" text type="warning"
                       :loading="retrying === row.id" @click="retry(row.id)">重试转码</el-button>
          </template>
        </el-table-column>
      </el-table>
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
</script>

<style scoped>
.my { max-width: 1000px; margin: 24px auto; }
.v-cell { display: flex; gap: 10px; align-items: center; }
.v-poster { width: 96px; aspect-ratio: 16/9; object-fit: cover; border-radius: 4px; background: #eee; }
.v-placeholder { display: flex; align-items: center; justify-content: center; color: #bbb; font-size: 12px; }
.v-title { font-size: 14px; }
.v-meta { font-size: 12px; color: #999; margin-top: 4px; }
.note { font-size: 12px; color: #666; }
.muted { color: #bbb; }
</style>
