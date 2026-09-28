<template>
  <div class="upload">
    <header class="page-head">
      <h1>投稿</h1>
      <p class="sub">大文件将分片直传对象存储,支持断点续传与秒传</p>
    </header>

    <el-card shadow="never">
      <input ref="fileInput" type="file" accept=".mp4,.mkv,.mov,.avi,.flv" :disabled="busy" @change="onFile" class="hidden-input" />

      <div class="studio">
        <div class="studio-left">
          <div
            v-if="!file"
            class="dropzone"
            :class="{ 'is-drag': dragOver }"
            @click="!busy && fileInput.click()"
            @dragover.prevent="dragOver = true"
            @dragleave.prevent="dragOver = false"
            @drop.prevent="onDrop"
          >
            <span class="dz-icon">
              <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 15V4" /><path d="m6.5 8.5 5.5-5.5 5.5 5.5" /><path d="M4 20h16" />
              </svg>
            </span>
            <p class="dz-title">拖拽视频到此处,或<span class="dz-link">点击选择文件</span></p>
            <p class="dz-hint">支持 MP4 / MKV / MOV / AVI / FLV,单个文件不超过 2GB</p>
          </div>

          <template v-else>
            <div class="preview-wrap">
              <video v-if="previewUrl" class="preview-video" :src="previewUrl" controls preload="metadata"></video>
            </div>
            <div class="file-card">
              <span class="file-badge">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="3" y="5" width="18" height="14" rx="3" /><path d="m10 9.5 5 2.5-5 2.5z" />
                </svg>
              </span>
              <div class="file-meta">
                <span class="file-name">{{ file.name }}</span>
                <span class="file-size">{{ fmtSize(file.size) }}</span>
              </div>
              <el-tag v-if="resumed" size="small" type="success">已恢复上传</el-tag>
              <button v-if="!busy" class="file-remove" type="button" @click="removeFile">移除</button>
            </div>
          </template>

          <template v-if="state !== 'idle'">
            <ol class="steps">
              <li class="step" :class="{ 'is-done': stepIdx() > 0, 'is-active': stepIdx() === 0 }">
                <span class="step-dot">
                  <svg v-if="stepIdx() > 0" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="m5 13 4 4L19 7" /></svg>
                  <template v-else>1</template>
                </span>
                <span class="step-label">计算指纹</span>
              </li>
              <li class="step-line" :class="{ 'is-filled': stepIdx() > 0 }"></li>
              <li class="step" :class="{ 'is-done': stepIdx() > 1, 'is-active': stepIdx() === 1 }">
                <span class="step-dot">
                  <svg v-if="stepIdx() > 1" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="m5 13 4 4L19 7" /></svg>
                  <template v-else>2</template>
                </span>
                <span class="step-label">分片上传</span>
              </li>
              <li class="step-line" :class="{ 'is-filled': stepIdx() > 1 }"></li>
              <li class="step" :class="{ 'is-active': stepIdx() === 2 }">
                <span class="step-dot">
                  <span v-if="stepIdx() < 2">3</span>
                </span>
                <span class="step-label">合并提交</span>
              </li>
            </ol>

            <!-- 计算指纹 -->
            <div v-if="state === 'hashing'" class="stage">
              <div class="stage-row">
                <p class="stage-text">正在计算文件指纹({{ hashPercent }}%),用于秒传与断点续传…</p>
              </div>
              <el-progress :percentage="hashPercent" :stroke-width="10" :show-text="false" />
            </div>

            <!-- 分片上传 -->
            <div v-if="state === 'uploading'" class="stage">
              <div class="stage-row">
                <p class="stage-text">分片上传中 {{ uploadPercent }}%(已完成 {{ doneParts }}/{{ totalParts }} 片)</p>
              </div>
              <el-progress :percentage="uploadPercent" :stroke-width="10" :show-text="false" />
              <div class="stage-actions">
                <el-button round @click="cancel">暂停</el-button>
                <span class="stage-hint">暂停后,重新选择同一文件即可续传</span>
              </div>
            </div>

            <!-- 提交审核 -->
            <div v-if="state === 'merging'" class="stage">
              <div class="stage-row">
                <p class="stage-text">分片合并中,完成后自动进入审核…</p>
              </div>
              <el-progress :percentage="100" status="warning" :stroke-width="10" :indeterminate="true" :duration="2" :show-text="false" />
            </div>
          </template>
        </div>

        <div class="studio-right">
          <div class="form-section">
            <h2 class="section-title">基础信息</h2>
            <div class="fields">
              <label class="field-label" for="upload-title">标题</label>
              <el-input id="upload-title" v-model="form.title" maxlength="100" show-word-limit placeholder="给视频起个标题" :disabled="busy" />

              <label class="field-label" for="upload-category">分区<span class="req">*</span></label>
              <el-select id="upload-category" v-model="form.category" class="category-select" placeholder="选择投稿分区" :disabled="busy">
                <el-option v-for="c in categories" :key="c.key" :label="c.name" :value="c.key" />
              </el-select>

              <label class="field-label" for="upload-tags">标签</label>
              <el-input
                id="upload-tags"
                v-model="tagInput"
                maxlength="16"
                placeholder="输入标签后回车添加,最多 5 个"
                :disabled="busy"
                @keydown.enter="onTagEnter"
              />
              <div v-if="form.tags.length" class="tag-list">
                <el-tag v-for="(t, i) in form.tags" :key="t" round closable @close="removeTag(i)">{{ t }}</el-tag>
              </div>
            </div>
          </div>

          <div class="form-section">
            <h2 class="section-title">简介</h2>
            <div class="fields">
              <el-input id="upload-desc" v-model="form.description" type="textarea" :rows="4" maxlength="2000" show-word-limit :disabled="busy" />
            </div>
          </div>

          <div class="submit-row">
            <el-button type="primary" size="large" round :disabled="busy || !file || !form.title.trim()" @click="submit">开始上传</el-button>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import http from '../api.js'

const router = useRouter()
const file = ref(null)
const fileInput = ref(null)
const previewUrl = ref('')
const form = reactive({ title: '', description: '', category: '', tags: [] })
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

/** 分区列表;接口失败或为空时回退内置分区(直连 axios,不触发全局错误提示) */
const categories = ref([])
const CATEGORY_FALLBACK = [
  { key: 'donghua', name: '动画' },
  { key: 'youxi', name: '游戏' },
  { key: 'keji', name: '科技' },
  { key: 'shenghuo', name: '生活' },
  { key: 'yinyue', name: '音乐' },
  { key: 'yingshi', name: '影视' },
  { key: 'zhishi', name: '知识' },
  { key: 'meishi', name: '美食' }
]

async function loadCategories() {
  let list = []
  try {
    const resp = await axios.get('/api/videos/categories')
    const body = resp?.data
    list = Array.isArray(body) ? body : Array.isArray(body?.data) ? body.data : []
  } catch {
    list = []
  }
  list = list.filter((c) => c && c.key != null && c.name != null)
  categories.value = list.length ? list : CATEGORY_FALLBACK
}
onMounted(loadCategories)

/** 标签:回车添加,最多 5 个、单个 ≤16 字、去重 */
const tagInput = ref('')

function onTagEnter(e) {
  if (e.isComposing || e.keyCode === 229) return
  e.preventDefault()
  addTag()
}

function addTag() {
  const t = tagInput.value.trim()
  if (!t) return
  if (form.tags.length >= 5) {
    ElMessage.warning('最多添加 5 个标签')
    return
  }
  if (t.length > 16) {
    ElMessage.warning('单个标签不超过 16 个字')
    return
  }
  if (form.tags.includes(t)) {
    ElMessage.warning(`标签「${t}」已存在`)
    tagInput.value = ''
    return
  }
  form.tags.push(t)
  tagInput.value = ''
}

function removeTag(i) {
  form.tags.splice(i, 1)
}

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
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  file.value = f
  previewUrl.value = URL.createObjectURL(f)
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
  if (!form.category) {
    ElMessage.warning('请选择投稿分区')
    return
  }
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
      md5: md5Cache,
      title: form.title.trim(),
      description: form.description.trim(),
      category: form.category || undefined,
      tags: tags.value.join(',')
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
      description: form.description.trim(),
      category: form.category,
      tags: tags.value.join(',')
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

const dragOver = ref(false)

/** 拖拽选择文件,复用隐藏 input 的 accept 白名单与 onFile 的 2GB 预校验 */
function onDrop(e) {
  dragOver.value = false
  const f = e.dataTransfer?.files?.[0]
  if (!f) return
  const exts = fileInput.value.accept.split(',')
  if (!exts.some((ext) => f.name.toLowerCase().endsWith(ext))) {
    ElMessage.error(`仅支持 ${exts.join(' / ')} 格式`)
    return
  }
  const dt = new DataTransfer()
  dt.items.add(f)
  fileInput.value.files = dt.files
  onFile({ target: fileInput.value })
}

/** 移除已选文件 */
function removeFile() {
  file.value = null
  md5Cache = ''
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
  if (fileInput.value) fileInput.value.value = ''
}

/** 当前所处阶段,用于步骤指示器 */
function stepIdx() {
  return { hashing: 0, uploading: 1, merging: 2 }[state.value] ?? -1
}

onBeforeUnmount(() => {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})
</script>

<style scoped>
.upload { max-width: 1180px; margin: 24px auto 56px; }

.page-head { margin-bottom: 24px; }
.page-head h1 { margin: 0 0 10px; font-size: clamp(32px, 5vw, 40px); line-height: 1.1; }
.page-head .sub { margin: 0; font-size: 17px; color: var(--text-secondary); }

.hidden-input { display: none; }

.studio {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 400px;
  gap: 28px;
  align-items: start;
}

/* 左列:本地预览 + 上传进度 */
.preview-wrap { border-radius: 14px; overflow: hidden; background: #000; }
.preview-video { display: block; width: 100%; aspect-ratio: 16 / 9; background: #000; }

.studio-left .file-card { margin-top: 14px; }

.dropzone {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 56px 24px; text-align: center; cursor: pointer;
  border: 1.5px dashed rgba(0, 0, 0, 0.18); border-radius: 20px; background: var(--bg);
  transition: border-color 0.4s var(--ease), background 0.4s var(--ease), transform 0.4s var(--ease);
}
.dropzone:hover { border-color: rgba(0, 0, 0, 0.3); }
.dropzone.is-drag { border-color: var(--accent); background: rgba(0, 113, 227, 0.05); transform: scale(1.01); }

.dz-icon {
  width: 56px; height: 56px; margin-bottom: 16px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  background: rgba(0, 113, 227, 0.08); color: var(--accent);
  transition: transform 0.4s var(--ease);
}
.dropzone.is-drag .dz-icon { transform: translateY(-4px) scale(1.06); }

.dz-title { margin: 0 0 6px; font-size: 16px; font-weight: 600; color: var(--text); }
.dz-link { color: var(--accent); margin-left: 4px; }
.dz-hint { margin: 0; font-size: 13px; color: var(--text-tertiary); }

.file-card {
  display: flex; align-items: center; gap: 14px;
  padding: 14px 16px;
  border: 1px solid var(--hairline); border-radius: 14px; background: var(--bg);
}
.file-badge {
  width: 40px; height: 40px; flex: none; border-radius: 10px;
  display: inline-flex; align-items: center; justify-content: center;
  background: rgba(0, 113, 227, 0.08); color: var(--accent);
}
.file-meta { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.file-name { font-size: 14px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-size { font-size: 12px; color: var(--text-tertiary); }
.file-remove {
  flex: none; padding: 6px 14px; border: none; border-radius: var(--radius-button);
  background: transparent; color: var(--text-secondary); font-size: 13px; font-family: inherit; cursor: pointer;
  transition: background 0.4s var(--ease), color 0.4s var(--ease);
}
.file-remove:hover { background: rgba(0, 0, 0, 0.05); color: var(--text); }

.steps { display: flex; align-items: center; list-style: none; margin: 28px 0 20px; padding: 0; }
.step { flex: none; display: flex; align-items: center; gap: 10px; }
.step-dot {
  width: 28px; height: 28px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 600; background: rgba(0, 0, 0, 0.06); color: var(--text-tertiary);
  transition: background 0.4s var(--ease), color 0.4s var(--ease), box-shadow 0.4s var(--ease);
}
.step-label { font-size: 14px; color: var(--text-tertiary); transition: color 0.4s var(--ease); }
.step-line { flex: 1; min-width: 24px; height: 2px; margin: 0 14px; border-radius: 1px; background: var(--hairline); transition: background 0.4s var(--ease); }
.step.is-active .step-dot { background: var(--accent); color: #fff; box-shadow: 0 0 0 5px rgba(0, 113, 227, 0.12); }
.step.is-active .step-label { color: var(--text); font-weight: 600; }
.step.is-done .step-dot { background: var(--success); color: #fff; }
.step.is-done .step-label { color: var(--text-secondary); }
.step-line.is-filled { background: var(--success); }

.stage { border-top: 1px solid var(--hairline); padding-top: 20px; }
.stage-row { display: flex; align-items: baseline; gap: 12px; margin-bottom: 12px; }
.stage-text { flex: 1; margin: 0; font-size: 14px; color: var(--text-secondary); }
.stage-actions { display: flex; align-items: center; gap: 14px; margin-top: 20px; }
.stage-hint { font-size: 13px; color: var(--text-tertiary); }

/* 右列:表单 */
.studio-right { min-width: 0; }
.section-title { margin: 0; font-size: 16px; font-weight: 600; letter-spacing: -0.01em; color: var(--text); }
.form-section { padding-bottom: 20px; }
.form-section + .form-section { margin-top: 20px; border-top: 1px solid var(--hairline); padding-top: 20px; }
.fields { display: flex; flex-direction: column; gap: 10px; margin-top: 14px; }
.field-label { font-size: 14px; font-weight: 600; color: var(--text); }
.field-label:not(:first-child) { margin-top: 8px; }
.req { margin-left: 2px; color: var(--danger); }
.category-select { width: 100%; }
.tag-list { display: flex; flex-wrap: wrap; gap: 8px; }

.submit-row { display: flex; align-items: center; margin-top: 24px; }

@media (max-width: 960px) {
  .studio { grid-template-columns: 1fr; }
}
</style>
