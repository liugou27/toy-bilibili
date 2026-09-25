#!/usr/bin/env python3
"""轻量压测:多线程压公开 GET 接口,输出 RPS 与延迟分位数。

用法: python3 bench.py [url] [concurrency] [seconds]
示例: python3 scripts/bench.py "http://localhost:8080/api/videos?page=1&size=12" 50 30
"""
import http.client
import statistics
import sys
import threading
import time
from urllib.parse import urlparse


def bench(url: str, concurrency: int, duration: int) -> None:
    parsed = urlparse(url)
    host, port = parsed.hostname, parsed.port or 80
    path = parsed.path + ("?" + parsed.query if parsed.query else "")
    latencies = []
    errors = []
    stop = threading.Event()
    lock = threading.Lock()

    def worker() -> None:
        conn = http.client.HTTPConnection(host, port, timeout=5)
        while not stop.is_set():
            start = time.perf_counter()
            try:
                conn.request("GET", path, headers={"Connection": "keep-alive"})
                resp = conn.getresponse()
                resp.read()
                elapsed_ms = (time.perf_counter() - start) * 1000
                with lock:
                    if resp.status == 200:
                        latencies.append(elapsed_ms)
                    else:
                        errors.append(f"HTTP {resp.status}")
            except Exception as e:  # noqa: BLE001
                with lock:
                    errors.append(type(e).__name__)
                try:
                    conn.close()
                except Exception:  # noqa: BLE001
                    pass
                conn = http.client.HTTPConnection(host, port, timeout=5)

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(concurrency)]
    start = time.perf_counter()
    for t in threads:
        t.start()
    try:
        time.sleep(duration)
    finally:
        stop.set()
        for t in threads:
            t.join(timeout=10)
    wall = time.perf_counter() - start

    total = len(latencies)
    print(f"目标: {url}")
    print(f"并发: {concurrency}  时长: {wall:.1f}s  成功: {total}  错误: {len(errors)}")
    if errors:
        from collections import Counter
        print("错误分布:", dict(Counter(errors).most_common(3)))
    if not latencies:
        print("无成功请求")
        return
    latencies.sort()

    def pct(p: float) -> float:
        return latencies[min(len(latencies) - 1, int(len(latencies) * p))]

    print(f"QPS: {total / wall:.1f}")
    print(f"延迟 ms  p50={pct(0.50):.1f}  p90={pct(0.90):.1f}  p95={pct(0.95):.1f}  p99={pct(0.99):.1f}  max={latencies[-1]:.1f}")


if __name__ == "__main__":
    url = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/api/videos?page=1&size=12"
    concurrency = int(sys.argv[2]) if len(sys.argv) > 2 else 50
    duration = int(sys.argv[3]) if len(sys.argv) > 3 else 30
    bench(url, concurrency, duration)
