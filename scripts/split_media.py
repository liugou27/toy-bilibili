#!/usr/bin/env python3
"""切源:按关键帧无损切段(-c copy),供段级分布式转码使用。

用法: split_media.py <input> <out_dir> [segment_seconds=60]
stdout: {"single":bool, "ladder":[1080,720,480], "duration_sec":X, "poster":"poster.jpg"|null,
         "segments":[{"index":0,"file":"seg_0000.mp4","duration_sec":61.2},...]}
- single=true 表示无需切割,段源直接用原片(Java 侧跳过段上传)
- 切割点在关键帧上,实际段长 >= segment_seconds;poster 从源片抽帧
"""
import json
import math
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe_media import summarize  # noqa: E402
from transcode import ladder_for  # noqa: E402


def even(value: int) -> int:
    return int(math.floor(value / 2) * 2)


def extract_poster(input_path: str, out_dir: str, duration: float, height: int) -> str:
    poster = os.path.join(out_dir, "poster.jpg")
    at = min(1.0, duration * 0.1) if duration > 0 else 0
    h = even(min(height or 720, 720))
    cmd = ["ffmpeg", "-v", "error", "-y", "-ss", f"{at:.3f}", "-i", input_path,
           "-frames:v", "1", "-vf", f"scale=-2:{h}", "-q:v", "3", poster]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0 or not os.path.exists(poster):
        raise RuntimeError(f"poster extract failed: {proc.stderr[-300:] if proc.stderr else 'no file'}")
    return os.path.basename(poster)


def main() -> int:
    if len(sys.argv) not in (3, 4):
        print("usage: split_media.py <input> <out_dir> [segment_seconds]", file=sys.stderr)
        return 2
    input_path, out_dir = sys.argv[1], sys.argv[2]
    seg_seconds = int(sys.argv[3]) if len(sys.argv) == 4 else 60
    os.makedirs(out_dir, exist_ok=True)

    meta = summarize(input_path)
    height = meta["height"] or 480
    duration = meta["duration_sec"] or 0
    ladder = ladder_for(height)
    poster = extract_poster(input_path, out_dir, duration, height)

    if duration <= seg_seconds * 1.5:
        print(json.dumps({
            "single": True, "ladder": ladder, "duration_sec": duration, "poster": poster,
            "segments": [{"index": 0, "file": None, "duration_sec": duration}],
        }, ensure_ascii=False))
        return 0

    pattern = os.path.join(out_dir, "seg_%04d.mp4")
    cmd = ["ffmpeg", "-v", "error", "-y", "-i", input_path,
           "-c", "copy", "-sn", "-dn",
           "-f", "segment", "-segment_time", str(seg_seconds),
           "-segment_format", "mp4", "-reset_timestamps", "1",
           pattern]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"split failed: {proc.stderr[-400:] if proc.stderr else 'unknown'}")

    segments = []
    for name in sorted(os.listdir(out_dir)):
        if not (name.startswith("seg_") and name.endswith(".mp4")):
            continue
        seg_meta = summarize(os.path.join(out_dir, name))
        segments.append({
            "index": len(segments),
            "file": name,
            "duration_sec": seg_meta["duration_sec"],
        })
    if not segments:
        raise RuntimeError("split produced no segments")
    total = sum(s["duration_sec"] or 0 for s in segments)
    if abs(total - duration) > max(5.0, duration * 0.02):
        raise RuntimeError(f"segment durations {total:.1f}s deviate from source {duration:.1f}s")

    print(json.dumps({
        "single": False, "ladder": ladder, "duration_sec": duration, "poster": poster,
        "segments": segments,
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
