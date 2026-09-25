#!/usr/bin/env python3
"""转码:按源高度选档,输出多码率 HLS + 封面(契约见设计文档 §13)。

用法: transcode.py <input> <out_dir>
产物: out_dir/master.m3u8、out_dir/{480p,720p,1080p}.m3u8 + 分片、out_dir/poster.jpg
stdout: {"renditions":[{"name","height","playlist"}], "poster", "duration_sec"}
"""
import json
import math
import os
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe_media import summarize  # noqa: E402

HLS_TIME = 4
PRESET = "veryfast"
CRF = "23"
AUDIO_BITRATE = "128k"

BANDWIDTH_ESTIMATE = {1080: 5000000, 720: 2800000, 480: 1400000, 360: 800000}


def ladder_for(height: int) -> list:
    if height >= 1080:
        return [1080, 720, 480]
    if height >= 720:
        return [720, 480]
    if height >= 480:
        return [480]
    return [max(180, height - height % 2)]


def even(value: int) -> int:
    return int(math.floor(value / 2) * 2)


def transcode_rendition(input_path: str, out_dir: str, height: int, has_audio: bool) -> dict:
    name = f"{height}p"
    playlist = os.path.join(out_dir, f"{name}.m3u8")
    segment_pattern = os.path.join(out_dir, f"{name}_%04d.ts")
    cmd = ["ffmpeg", "-v", "error", "-y", "-i", input_path,
           "-vf", f"scale=-2:{height}",
           "-c:v", "libx264", "-preset", PRESET, "-crf", CRF,
           "-c:a", "aac", "-b:a", AUDIO_BITRATE,
           "-sn", "-dn",
           "-hls_time", str(HLS_TIME),
           "-hls_playlist_type", "vod",
           "-hls_segment_filename", segment_pattern,
           playlist]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0 or not os.path.exists(playlist):
        raise RuntimeError(f"transcode {name} failed: {proc.stderr[-400:] if proc.stderr else 'no playlist'}")
    return {"name": name, "height": height, "playlist": os.path.basename(playlist)}


def extract_poster(input_path: str, out_dir: str, duration: float, source_height: int) -> str:
    poster = os.path.join(out_dir, "poster.jpg")
    at = min(1.0, duration * 0.1) if duration > 0 else 0
    height = even(min(source_height or 720, 720))
    cmd = ["ffmpeg", "-v", "error", "-y", "-ss", f"{at:.3f}", "-i", input_path,
           "-frames:v", "1", "-vf", f"scale=-2:{height}", "-q:v", "3", poster]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0 or not os.path.exists(poster):
        raise RuntimeError(f"poster extract failed: {proc.stderr[-300:] if proc.stderr else 'no file'}")
    return os.path.basename(poster)


def write_master(out_dir: str, renditions: list) -> str:
    master = os.path.join(out_dir, "master.m3u8")
    lines = ["#EXTM3U", "#EXT-X-VERSION:3"]
    for r in sorted(renditions, key=lambda x: -x["height"]):
        bandwidth = BANDWIDTH_ESTIMATE.get(r["height"], 1000000)
        lines.append(f"#EXT-X-STREAM-INF:BANDWIDTH={bandwidth},RESOLUTION=-2x{r['height']}")
        lines.append(r["playlist"])
    with open(master, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return os.path.basename(master)


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: transcode.py <input> <out_dir>", file=sys.stderr)
        return 2
    input_path, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)

    meta = summarize(input_path)
    height = meta["height"] or 480
    renditions = []
    with ThreadPoolExecutor(max_workers=min(3, os.cpu_count() or 2)) as pool:
        futures = [pool.submit(transcode_rendition, input_path, out_dir, h, meta["has_audio"])
                   for h in ladder_for(height)]
        for fut in futures:
            renditions.append(fut.result())
    poster = extract_poster(input_path, out_dir, meta["duration_sec"], height)
    master = write_master(out_dir, renditions)

    print(json.dumps({
        "renditions": sorted(renditions, key=lambda r: -r["height"]),
        "master": master,
        "poster": poster,
        "duration_sec": meta["duration_sec"],
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
