#!/usr/bin/env python3
"""ffprobe 封装:输出视频元数据 JSON(契约见设计文档 §13)。

stdout: {"duration_sec","width","height","has_video","has_audio","format",
         "size_bytes","video_codec","avg_frame_rate"}
"""
import json
import subprocess
import sys


def probe(path: str) -> dict:
    cmd = ["ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", path]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "ffprobe failed")
    return json.loads(proc.stdout)


def summarize(path: str) -> dict:
    data = probe(path)
    streams = data.get("streams", [])
    fmt = data.get("format", {})
    video = next((s for s in streams if s.get("codec_type") == "video"), None)
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)
    try:
        duration = float(fmt.get("duration") or 0)
    except (TypeError, ValueError):
        duration = 0.0
    return {
        "duration_sec": round(duration, 3),
        "width": video.get("width") if video else None,
        "height": video.get("height") if video else None,
        "has_video": video is not None,
        "has_audio": audio is not None,
        "format": fmt.get("format_name"),
        "size_bytes": int(fmt.get("size") or 0),
        "video_codec": video.get("codec_name") if video else None,
        "avg_frame_rate": video.get("avg_frame_rate") if video else None,
    }


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: probe_media.py <input>", file=sys.stderr)
        sys.exit(2)
    print(json.dumps(summarize(sys.argv[1]), ensure_ascii=False))
