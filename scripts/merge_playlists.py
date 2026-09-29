#!/usr/bin/env python3
"""组装:把各段各档的播放表按段序拼接成最终 media playlist + master。

用法: merge_playlists.py <in_dir> <out_dir> <ladder "1080,720,480"> <seg_count>
in_dir: 各段中间播放表 {H}p_{seg:04d}.m3u8(由 Java 从对象存储下载)
段间以 #EXT-X-DISCONTINUITY 标记(分段独立编码,时间戳不连续,播放器据此正确切换)。
stdout: {"renditions":[{"name","height","playlist"}], "master":"master.m3u8"}
"""
import json
import math
import os
import sys

BANDWIDTH_ESTIMATE = {1080: 5000000, 720: 2800000, 480: 1400000, 360: 800000}


def parse_entries(path: str) -> list:
    """读取段播放表,返回 [(extinf, uri), ...]。"""
    entries = []
    pending_inf = None
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line.startswith("#EXTINF:"):
                pending_inf = line
            elif line and not line.startswith("#"):
                if pending_inf is None:
                    raise RuntimeError(f"{path}: media uri without EXTINF: {line}")
                entries.append((pending_inf, line))
                pending_inf = None
    if not entries:
        raise RuntimeError(f"{path}: no media entries")
    return entries


def merge_rendition(in_dir: str, out_dir: str, height: int, seg_count: int) -> dict:
    name = f"{height}p"
    all_entries = []
    target = 0.0
    for seg in range(seg_count):
        seg_playlist = os.path.join(in_dir, f"{name}_{seg:04d}.m3u8")
        if not os.path.exists(seg_playlist):
            raise RuntimeError(f"missing segment playlist: {seg_playlist}")
        entries = parse_entries(seg_playlist)
        if seg > 0:
            all_entries.append(("#EXT-X-DISCONTINUITY", None))
        all_entries.extend(entries)
        for inf, _ in entries:
            try:
                target = max(target, float(inf.split(":", 1)[1].rstrip(",")))
            except ValueError:
                pass

    playlist = os.path.join(out_dir, f"{name}.m3u8")
    lines = ["#EXTM3U", "#EXT-X-VERSION:3",
             f"#EXT-X-TARGETDURATION:{math.ceil(target) or HLS_FALLBACK_TARGET}",
             "#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-PLAYLIST-TYPE:VOD"]
    for inf, uri in all_entries:
        lines.append(inf)
        if uri:
            lines.append(uri)
    lines.append("#EXT-X-ENDLIST")
    with open(playlist, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return {"name": name, "height": height, "playlist": f"{name}.m3u8"}


HLS_FALLBACK_TARGET = 4


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
    if len(sys.argv) != 5:
        print("usage: merge_playlists.py <in_dir> <out_dir> <ladder> <seg_count>", file=sys.stderr)
        return 2
    in_dir, out_dir, ladder_arg, seg_count = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
    ladder = [int(h) for h in ladder_arg.split(",") if h.strip()]
    os.makedirs(out_dir, exist_ok=True)

    renditions = [merge_rendition(in_dir, out_dir, h, seg_count) for h in ladder]
    master = write_master(out_dir, renditions)
    print(json.dumps({"renditions": sorted(renditions, key=lambda r: -r["height"]),
                      "master": master}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
