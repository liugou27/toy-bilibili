<template>
  <div class="upload">
    <el-card shadow="never">
      <template #header><b>投稿视频</b></template>
      <el-form :model="form" label-width="80px">
        <el-form-item label="视频文件">
          <input ref="fileInput" type="file" accept=".mp4,.mkv,.mov,.avi,.flv" @change="onFile" />
          <div v-if="file" class="file-info">
            {{ file.name }} · {{ (file.size / 1024 / 1024).toFixed(1) }} MB
          </div>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="100" show-word-limit placeholder="给视频起个标题" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="uploading" :disabled="!file || !form.title.trim()" @click="submit">
            {{ uploading ? `上传中 ${progress}%` : '发布' }}
          </el-button>
          <el-button v-if="uploading" @click="cancel">取消</el-button>
        </el-form-item>
        <el-form-item v-if="uploading">
          <el-progress :percentage="progress" style="width: 100%" />
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api.js'

const router = useRouter()
const file = ref(null)
const fileInput = ref(null)
const uploading = ref(false)
const progress = ref(0)
const form = reactive({ title: '', description: '' })
let abortCtrl = null

const MAX_SIZE = 2 * 1024 * 1024 * 1024

function onFile(e) {
  const f = e.target.files[0]
  if (!f) return
  if (f.size > MAX_SIZE) {
    ElMessage.error('文件超过 2GB 限制')
    e.target.value = ''
    return
  }
  file.value = f
}

function cancel() {
  abortCtrl?.abort()
  uploading.value = false
  progress.value = 0
}

async function submit() {
  uploading.value = true
  progress.value = 0
  abortCtrl = new AbortController()
  const fd = new FormData()
  fd.append('file', file.value)
  fd.append('title', form.title.trim())
  if (form.description.trim()) fd.append('description', form.description.trim())
  try {
    await http.post('/videos', fd, {
      signal: abortCtrl.signal,
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => {
        if (e.total) progress.value = Math.round((e.loaded / e.total) * 100)
      }
    })
    ElMessage.success('投稿成功,进入审核流程')
    router.push('/my')
  } catch (e) {
    if (e.code !== 'ERR_CANCELED') {
      uploading.value = false
    }
  } finally {
    uploading.value = false
  }
}
</script>

<style scoped>
.upload { max-width: 640px; margin: 24px auto; }
.file-info { color: #999; font-size: 12px; margin-top: 4px; }
</style>
