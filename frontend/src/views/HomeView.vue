<template>
  <div>
    <div v-if="loading" class="grid">
      <el-skeleton v-for="i in 8" :key="i" class="card-skeleton" animated />
    </div>
    <el-empty v-else-if="!videos.length" description="还没有视频,来投个稿吧" />
    <div v-else class="grid">
      <div v-for="v in videos" :key="v.id" class="card" @click="$router.push(`/watch/${v.id}`)">
        <div class="thumb">
          <img v-if="v.poster" :src="v.poster" loading="lazy" />
          <div v-else class="thumb-placeholder">暂无封面</div>
          <span v-if="v.durationSec" class="duration">{{ fmtDuration(v.durationSec) }}</span>
        </div>
        <div class="card-body">
          <div class="title">{{ v.title }}</div>
          <div class="meta">
            <span>{{ v.ownerName || 'UP主' }}</span>
            <span>{{ fmtCount(v.playCount) }} 播放</span>
          </div>
        </div>
      </div>
    </div>
    <div v-if="total > query.size" class="pager">
      <el-pagination
        layout="prev, pager, next"
        :total="total"
        :page-size="query.size"
        :current-page="query.page"
        @current-change="onPage"
      />
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api.js'

const route = useRoute()
const videos = ref([])
const total = ref(0)
const loading = ref(true)
const query = reactive({ page: 1, size: 12, keyword: route.query.q || '' })

async function load() {
  loading.value = true
  try {
    const data = await http.get('/videos', { params: query })
    videos.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function onPage(p) {
  query.page = p
  load()
}

watch(() => route.query.q, (q) => {
  query.keyword = q || ''
  query.page = 1
  load()
})

onMounted(load)

function fmtDuration(sec) {
  const s = Math.round(sec)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

function fmtCount(n) {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万`
  return String(n || 0)
}
</script>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
  margin-top: 8px;
}
.card { cursor: pointer; background: #fff; border-radius: 8px; overflow: hidden; transition: box-shadow .2s; }
.card:hover { box-shadow: 0 4px 16px rgba(0,0,0,.12); }
.thumb { position: relative; aspect-ratio: 16/9; background: #eee; }
.thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.thumb-placeholder { display: flex; align-items: center; justify-content: center; height: 100%; color: #aaa; }
.duration {
  position: absolute; right: 6px; bottom: 6px;
  background: rgba(0,0,0,.7); color: #fff; font-size: 12px;
  padding: 1px 6px; border-radius: 4px;
}
.card-body { padding: 10px 12px; }
.title { font-size: 14px; font-weight: 500; line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.meta { margin-top: 6px; font-size: 12px; color: #999; display: flex; gap: 12px; }
.card-skeleton { background: #fff; border-radius: 8px; height: 200px; }
.pager { display: flex; justify-content: center; margin: 24px 0; }
</style>
