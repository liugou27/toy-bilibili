<template>
  <div class="upload">
    <el-card shadow="never">
      <template #header><b>投稿视频</b></template>
      <el-form :model="form" label-width="80px">
        <el-form-item label="视频文件">
          <input ref="fileInput" type="file" accept=".mp4,.mkv,.mov,.avi,.flv" :disabled="busy" @change="onFile" />
          <div v-if="file" class="file-info">
            {{ file.name }} · {{ fmtSize(file.size) }}
            <el-tag v-if="resumed" size="small" type="success">已恢复上传</el-tag>
          </div>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="100" show-word-limit placeholder="给视频起个标题" :disabled="busy" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="2000" show-word-limit :disabled="busy" />
        </el-form-item>
        <el-form-item v-if="state === 'idle'">
          <el-button type="primary" :disabled="!file || !form.title.trim()" @click="submit">开始上传</el-button>
          <span class="hint">大文件将分片直传对象存储,支持断点续传与秒传</span>
        </el-form-item>
      </el-form>

      <!-- 计算指纹 -->
      <div v-if="state === 'hashing'" class="stage">
        <p>正在计算文件指纹({{ hashPercent }}%),用于秒传与断点续传…</p>
        <el-progress :percentage="hashPercent" :stroke-width="10" />
      </div>

      <!-- 分片上传 -->
      <div v-if="state === 'uploading'">
        <p>分片上传中 {{ uploadPercent }}%(已完成 {{ doneParts }}/{{ totalParts }} 片)</p>
        <el-progress :percentage="uploadPercent" :stroke-width="10" />
        <el-button style="margin-top: 12px" @click="cancel">暂停(可稍后重新选择文件续传)</el-button>
      </div>

      <!-- 提交审核 -->
      <div v-if="state === 'merging'">
        <p>分片合并中,完成后自动进入审核…</p>
        <el-progress :percentage="100" status="warning" :stroke-width="10" :indeterminate="true" :duration="2" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import http from '../api.js'

const router = useRouter()
const file = ref(null)
const fileInput = ref(null)
const form = reactive({ title: '', description: '' })
const state = ref('idle') // idle | hashing | uploading | merging
const hashPercent = ref(0)
const uploadPercent = ref(0)
const doneParts = ref(0)
const totalParts = ref(0)
const resumed = ref(false)
const busy = ref(false)

let md5Cache = ''
let abortFlag = false
let abortControllers = []

const MAX_SIZE = 2 * 1024 * 1024 * 1024
const CONCURRENCY = 3

function fmtSize(b) {
  return b > 1024 * 1024 * 1024 ? `${(b / 1024 / 1024 / 1024).toFixed(2)} GB` : `${(b / 1024 / 1024).toFixed(1)} MB`
}

function onFile(e) {
  const f = e.target.files[0]
  if (!f) return
  if (f.size > MAX_SIZE) {
    ElMessage.error('文件超过 2GB 限制')
    e.target.value = ''
    return
  }
  file.value = f
  md5Cache = ''
}

/** 计算文件 md5(Web Worker,不阻塞页面) */
function computeMd5(f) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('../md5.worker.js', import.meta.url), { type: 'module' })
    worker.onmessage = (ev) => {
      if (ev.data.type === 'progress') hashPercent.value = ev.data.percent
      if (ev.data.type === 'done') {
        worker.terminate()
        resolve(ev.data.md5)
      }
      if (ev.data.type === 'error') {
        worker.terminate()
        reject(new Error(ev.data.message))
      }
    }
    worker.onerror = (err) => {
      worker.terminate()
      reject(new Error(err.message || 'worker error'))
    }
    worker.postMessage({ file: f })
  })
}

async function submit() {
  abortFlag = false
  busy.value = true
  try {
    // 1. 指纹
    state.value = 'hashing'
    hashPercent.value = 0
    if (!md5Cache) {
      md5Cache = await computeMd5(file.value)
    }

    // 2. 初始化/续传/秒传
    const init = await http.post('/videos/upload/init', {
      fileName: file.value.name,
      fileSize: file.value.size,
      md5: md5Cache
    })
    if (init.instant) {
      ElMessage.success('秒传成功:相同内容已存在,无需上传')
      router.push('/my')
      return
    }
    const partSize = init.partSize
    const videoId = init.videoId
    const done = new Set(init.doneParts)
    totalParts.value = Math.ceil(file.value.size / partSize)
    doneParts.value = done.size
    resumed.value = done.size > 0

    // 3. 分片直传 MinIO(3 并发,单分片失败重试 3 次)
    state.value = 'uploading'
    const pending = []
    for (let p = 1; p <= totalParts.value; p++) {
      if (!done.has(p)) pending.push(p)
    }
    let finishedBytes = done.size * partSize
    const partial = new Map() // partNumber -> 已传字节
    const recompute = () => {
      const inflight = [...partial.values()].reduce((a, b) => a + b, 0)
      const uploaded = Math.min(finishedBytes + inflight, file.value.size)
      uploadPercent.value = Math.floor((uploaded / file.value.size) * 100)
    }

    const uploadOne = async (p) => {
      if (abortFlag) return
      const url = await http.get(`/videos/upload/${videoId}/presign/${p}`)
      const blob = file.value.slice((p - 1) * partSize, p * partSize)
      for (let attempt = 1; attempt <= 3; attempt++) {
        if (abortFlag) return
        const ctrl = new AbortController()
        abortControllers.push(ctrl)
        try {
          await axios.put(url, blob, {
            signal: ctrl.signal,
            headers: { 'Content-Type': 'application/octet-stream' },
            onUploadProgress: (ev) => {
              partial.set(p, ev.loaded || 0)
              recompute()
            }
          })
          partial.delete(p)
          finishedBytes += blob.size
          doneParts.value += 1
          recompute()
          return
        } catch (err) {
          partial.delete(p)
          recompute()
          if (abortFlag) return
          if (attempt === 3) throw err
          await new Promise((r) => setTimeout(r, 500 * Math.pow(2, attempt - 1)))
        }
      }
    }

    // 简单并发池
    const queue = [...pending]
    const workers = Array.from({ length: Math.min(CONCURRENCY, queue.length) }, async () => {
      while (queue.length && !abortFlag) {
        await uploadOne(queue.shift())
      }
    })
    await Promise.all(workers)
    if (abortFlag) {
      ElMessage.info('已暂停。重新选择同一文件即可从断点续传')
      return
    }

    // 4. 合并 + 提交审核
    state.value = 'merging'
    await http.post(`/videos/upload/${videoId}/complete`, {
      title: form.title.trim(),
      description: form.description.trim()
    })
    ElMessage.success('投稿成功,进入审核流程')
    router.push('/my')
  } catch (err) {
    // axios 拦截器已 toast;这里仅复位状态
  } finally {
    if (state.value !== 'merging') state.value = 'idle'
    busy.value = false
    abortControllers = []
  }
}

function cancel() {
  abortFlag = true
  abortControllers.forEach((c) => c.abort())
}
</script>

<style scoped>
.upload { max-width: 640px; margin: 24px auto; }
.file-info { color: #999; font-size: 12px; margin-top: 4px; display: flex; gap: 8px; align-items: center; }
.hint { color: #bbb; font-size: 12px; margin-left: 12px; }
.stage { margin-top: 8px; }
</style>
