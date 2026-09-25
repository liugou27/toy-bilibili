#!/usr/bin/env python3
"""自动初筛:ffprobe 元数据校验 + 均匀抽帧 + 规则判定(契约见设计文档 §13)。

规则:
- 硬失败(AUTO_FAIL):无视频流、时长不在 [1s, 7200s]、分辨率低于下限、画面基本全黑。
- 可疑(AUTO_SUSPECT):无音频轨、纯色/静帧、黑帧占比偏高。
- 其余 AUTO_PASS。

stdout: {"verdict": "AUTO_PASS|AUTO_SUSPECT|AUTO_FAIL",
         "checks": [{"name","passed","detail"}...],
         "black_ratio": 0.0-1.0, "static_suspect": bool}
"""
import json
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe_media import summarize  # noqa: E402

MIN_WIDTH = 240
MIN_HEIGHT = 180
MIN_DURATION = 1.0
MAX_DURATION = 7200.0
FRAME_SIZE = 64          # 归一化灰度图边长
BLACK_MEAN_THRESHOLD = 8.0
BLACK_RATIO_FAIL = 0.8
BLACK_RATIO_SUSPECT = 0.3
STATIC_DIFF_THRESHOLD = 2.0


def check(name: str, passed: bool, detail: str) -> dict:
    return {"name": name, "passed": passed, "detail": detail}


def frame_gray_mean(path: str) -> list:
    """读取抽帧图,返回 FRAME_SIZE*FRAME_SIZE 的灰度均值矩阵的一维列表。"""
    cmd = ["ffmpeg", "-v", "error", "-i", path, "-vf", f"scale={FRAME_SIZE}:{FRAME_SIZE}",
           "-f", "rawvideo", "-pix_fmt", "gray", "-"]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0 or not proc.stdout:
        raise RuntimeError("frame decode failed: " + proc.stderr.decode(errors="ignore")[:200])
    raw = proc.stdout[: FRAME_SIZE * FRAME_SIZE]
    return list(raw)


def extract_frames(input_path: str, duration: float, count: int, workdir: str) -> list:
    frames = []
    for i in range(count):
        t = duration * (i + 0.5) / count
        out = os.path.join(workdir, f"frame_{i:03d}.jpg")
        cmd = ["ffmpeg", "-v", "error", "-ss", f"{t:.3f}", "-i", input_path,
               "-frames:v", "1", "-y", out]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode == 0 and os.path.exists(out) and os.path.getsize(out) > 0:
            frames.append(out)
    return frames


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("input")
    parser.add_argument("--frames", type=int, default=8)
    parser.add_argument("--workdir", default=None)
    args = parser.parse_args()

    checks = []
    meta = summarize(args.input)
    hard_fail = False

    ok_video = meta["has_video"]
    checks.append(check("has_video_stream", ok_video, "video stream exists" if ok_video else "no video stream"))
    hard_fail |= not ok_video

    duration = meta["duration_sec"]
    ok_dur = MIN_DURATION <= duration <= MAX_DURATION
    checks.append(check("duration_range", ok_dur, f"duration={duration}s"))
    hard_fail |= not ok_dur

    width, height = meta["width"], meta["height"]
    ok_res = width is not None and height is not None and width >= MIN_WIDTH and height >= MIN_HEIGHT
    checks.append(check("resolution_floor", ok_res, f"{width}x{height}"))
    hard_fail |= not ok_res

    if meta["has_audio"]:
        checks.append(check("audio_stream", True, "audio stream exists"))
    else:
        checks.append(check("audio_stream", False, "no audio stream"))

    black_ratio = 0.0
    static_suspect = False
    if not hard_fail:
        with tempfile.TemporaryDirectory(prefix="autoscreen-") as tmp:
            frames = extract_frames(args.input, duration, args.frames, tmp)
            checks.append(check("frame_extract", len(frames) > 0, f"extracted {len(frames)} frames"))
            if frames:
                grays = [frame_gray_mean(f) for f in frames]
                means = [sum(g) / len(g) for g in grays]
                black_frames = sum(1 for m in means if m < BLACK_MEAN_THRESHOLD)
                black_ratio = black_frames / len(grays)
                checks.append(check("black_frame_ratio", black_ratio < BLACK_RATIO_FAIL,
                                    f"black_ratio={black_ratio:.2f}"))
                if black_ratio >= BLACK_RATIO_SUSPECT and black_ratio < BLACK_RATIO_FAIL:
                    checks.append(check("black_frame_suspect", False,
                                        f"black_ratio={black_ratio:.2f} suspicious"))
                n = min(len(grays), 3)
                if n >= 2:
                    base = grays[0]
                    diffs = [sum(abs(a - b) for a, b in zip(base, g)) / len(base) for g in grays[1:n]]
                    static_suspect = all(d < STATIC_DIFF_THRESHOLD for d in diffs)
                    if static_suspect:
                        checks.append(check("static_picture", False, "frames nearly identical"))

    if hard_fail or black_ratio >= BLACK_RATIO_FAIL:
        verdict = "AUTO_FAIL"
    elif static_suspect or any(not c["passed"] for c in checks):
        verdict = "AUTO_SUSPECT"
    else:
        verdict = "AUTO_PASS"

    print(json.dumps({
        "verdict": verdict,
        "checks": checks,
        "black_ratio": round(black_ratio, 4),
        "static_suspect": static_suspect,
        "meta": meta,
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
