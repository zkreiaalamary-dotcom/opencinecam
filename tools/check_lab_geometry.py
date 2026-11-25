#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Replay the lab repeated-half screen using an independent decoder in coded orientation."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def distances(rgb, width, height):
    def mad(dx, dy):
        total = 0
        row_bytes = 3 * (width - dx)
        for y in range(height - dy):
            i = 3 * y * width
            j = 3 * ((y + dy) * width + dx)
            total += sum(abs(a - b) for a, b in zip(rgb[i:i + row_bytes], rgb[j:j + row_bytes]))
        return total / (row_bytes * (height - dy))

    return [mad(width // 2, 0), mad(width // 4, 0), mad(0, height // 2), mad(0, height // 4)]


def screen(rows):
    horizontal = [h < 4 and q > 15 for h, q, _, _ in rows]
    vertical = [v < 4 and q > 15 for _, _, v, q in rows]
    if sum(horizontal) >= 2:
        return "REVIEW_LEFT_RIGHT"
    if sum(vertical) >= 2:
        return "REVIEW_TOP_BOTTOM"
    if any(horizontal + vertical):
        return "INCONCLUSIVE"
    if sum(qx > 15 and qy > 15 for _, qx, _, qy in rows) >= 2:
        return "NO_REPEAT_FLAG"
    return "INCONCLUSIVE"


def inspect(path, controls=False):
    streams = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-count_packets",
        "-show_entries", "stream=width,height,nb_read_packets,nb_frames", "-of", "json", str(path),
    ]))["streams"]
    if not streams:
        raise ValueError("No video stream")
    stream = streams[0]
    count = next((str(stream.get(key)) for key in ("nb_frames", "nb_read_packets")
                  if str(stream.get(key)).isdigit()), None)
    if count is None or int(count) < 1:
        raise ValueError("Frame count unavailable")
    n = int(count)
    indices = sorted({n // 4, n // 2, 3 * n // 4})
    # Keep multiples of four for exact shifts, preserve coded orientation and approximate aspect.
    scale = 192 / max(stream["width"], stream["height"])
    w, h = (max(4, 4 * round(stream[key] * scale / 4)) for key in ("width", "height"))
    which = "+".join(f"eq(n,{i})" for i in indices)
    raw = subprocess.check_output([
        "ffmpeg", "-nostdin", "-v", "error", "-noautorotate", "-threads", "2", "-i", str(path),
        "-map", "0:v:0", "-vf", f"select='{which}',scale={w}:{h}:flags=area,format=rgb24",
        "-fps_mode", "passthrough", "-frames:v", str(len(indices)), "-f", "rawvideo", "pipe:1",
    ])
    size = w * h * 3
    if len(raw) != size * len(indices):
        raise ValueError("Decoder did not return every requested picture")
    frames = [raw[i:i + size] for i in range(0, len(raw), size)]
    measured = [distances(f, w, h) for f in frames]
    with path.open("rb") as src:
        sha = hashlib.file_digest(src, "sha256").hexdigest()
    result = {"sha256": sha, "decodedIndices": indices, "sampleRaster": [w, h],
              "madHalfXQuarterXHalfYQuarterY": measured, "screen": screen(measured)}
    if controls:
        # Duplicate real textured halves after decoding to test the decision rule on this exact scene.
        x = [b"".join(f[i:i + w * 3 // 2] * 2 for i in range(0, size, w * 3)) for f in frames]
        y = [f[:size // 2] * 2 for f in frames]
        result["injected"] = {axis: screen([distances(f, w, h) for f in copies])
                              for axis, copies in (("horizontal", x), ("vertical", y))}
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("clips", nargs="+", type=Path)
    parser.add_argument("--controls", action="store_true", help="also inject horizontal and vertical repeats")
    args = parser.parse_args()
    failed = False
    for clip in args.clips:
        try:
            print(json.dumps(inspect(clip, args.controls)))
        except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
            print(json.dumps({"clip": clip.name, "screen": "UNVERIFIED", "error": str(error)}))
            failed = True
    raise SystemExit(int(failed))
