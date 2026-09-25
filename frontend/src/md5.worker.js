// 文件 md5 计算Worker:分块读取 + 增量摘要,避免大文件阻塞 UI
import SparkMD5 from 'spark-md5'

const CHUNK = 8 * 1024 * 1024 // 8MB

self.onmessage = async (e) => {
  const { file } = e.data
  try {
    const spark = new SparkMD5.ArrayBuffer()
    const total = Math.max(1, Math.ceil(file.size / CHUNK))
    for (let i = 0; i < total; i++) {
      const buf = await file.slice(i * CHUNK, (i + 1) * CHUNK).arrayBuffer()
      spark.append(buf)
      self.postMessage({ type: 'progress', percent: Math.round(((i + 1) / total) * 100) })
    }
    self.postMessage({ type: 'done', md5: spark.end() })
  } catch (err) {
    self.postMessage({ type: 'error', message: String(err) })
  }
}
