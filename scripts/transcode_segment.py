#!/usr/bin/env python3
"""段转码:对单个分段输出各档位 HLS 分片,分片名带段序号前缀保证全局唯一。

用法: transcode_segment.py <seg_file> <out_dir> <seg_index> <ladder "1080,720,480">
产物: out_dir/{H}p_{seg:04d}_%04d.ts + {H}p_{seg:04d}.m3u8(段内播放表,组装时拼接)
stdout: {"playlists":["480p_0000.m3u8",...], "duration_sec":段时长}
"""
import json
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe_media import summarize  # noqa: E402

HLS_TIME = 4
PRESET = "veryfast"
CRF = "23"
AUDIO_BITRATE = "128k"


def transcode_rendition(input_path: str, out_dir: str, height: int, seg_index: int) -> str:
    playlist = os.path.join(out_dir, f"{height}p_{seg_index:04d}.m3u8")
    seg_pattern = os.path.join(out_dir, f"{height}p_{seg_index:04d}_%04d.ts")
    cmd = ["ffmpeg", "-v", "error", "-y", "-i", input_path,
           "-vf", f"scale=-2:{height}",
           "-c:v", "libx264", "-preset", PRESET, "-crf", CRF,
           "-c:a", "aac", "-b:a", AUDIO_BITRATE,
           "-sn", "-dn",
           "-hls_time", str(HLS_TIME),
           "-hls_playlist_type", "vod",
           "-hls_segment_filename", seg_pattern,
           playlist]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0 or not os.path.exists(playlist):
        raise RuntimeError(f"segment transcode {height}p failed: "
                           f"{proc.stderr[-400:] if proc.stderr else 'no playlist'}")
    return os.path.basename(playlist)


def main() -> int:
    if len(sys.argv) != 5:
        print("usage: transcode_segment.py <seg_file> <out_dir> <seg_index> <ladder>", file=sys.stderr)
        return 2
    seg_file, out_dir, seg_index, ladder_arg = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
    ladder = [int(h) for h in ladder_arg.split(",") if h.strip()]
    os.makedirs(out_dir, exist_ok=True)

    with ThreadPoolExecutor(max_workers=min(3, os.cpu_count() or 2)) as pool:
        playlists = list(pool.map(
            lambda h: transcode_rendition(seg_file, out_dir, h, seg_index), ladder))

    meta = summarize(seg_file)
    print(json.dumps({"playlists": playlists, "duration_sec": meta["duration_sec"]},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
