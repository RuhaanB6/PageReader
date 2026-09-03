#!/usr/bin/env python3
"""Check a collection folder take by take, before you shoot the next setup.

The point is to catch a bad take while the scene is still on your desk. Each
take is judged on count, sharpness, and how much the frames actually differ from
one another -- a take of 40 near-identical frames is worth about as much as one
frame, and that is invisible from the count alone.

Usage:
  tools/verify-collection.py <collection-dir> [--review]
"""
import argparse
import csv
import sys
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np

MIN_FRAMES = 12
MIN_DIVERSITY = 12.0   # mean L1 distance between 32x32 thumbnails, 0..255


def thumbs(paths, n=24):
    step = max(1, len(paths) // n)
    out = []
    for p in paths[::step][:n]:
        img = cv2.imread(str(p), cv2.IMREAD_GRAYSCALE)
        if img is not None:
            out.append(cv2.resize(img, (32, 32)).astype(np.float32))
    return out


def diversity(ts):
    """Mean pairwise thumbnail distance. Low means you barely moved."""
    if len(ts) < 2:
        return 0.0
    d = [np.abs(ts[i] - ts[j]).mean()
         for i in range(len(ts)) for j in range(i + 1, len(ts))]
    return float(np.mean(d))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("collection")
    ap.add_argument("--review", action="store_true", help="also write contact sheets")
    args = ap.parse_args()

    root = Path(args.collection)
    frames = root / "frames"
    if not frames.is_dir():
        sys.exit(f"no frames/ under {root}")

    sharp = defaultdict(list)
    man = root / "manifest.csv"
    if man.exists():
        for row in csv.DictReader(man.open()):
            try:
                sharp[row["take"]].append(float(row["sharpness"]))
            except (KeyError, ValueError):
                pass

    # Rejection tallies, when the app recorded them.
    rejects = {}
    tl = root / "takes.csv"
    if tl.exists():
        for row in csv.DictReader(tl.open()):
            rejects[row["take"]] = row

    by_take = defaultdict(list)
    for p in sorted(frames.glob("*.jpg")):
        by_take[p.name.rsplit("_", 1)[0]].append(p)

    if not by_take:
        sys.exit("no frames recorded")

    print(f"{root.name}\n")
    print(f"{'take':<16}{'frames':>7}{'sharp p50':>11}{'diversity':>11}  notes")
    print("-" * 62)

    total = 0
    problems = []
    for take in sorted(by_take):
        paths = by_take[take]
        total += len(paths)
        s = sorted(sharp.get(take, []))
        p50 = s[len(s) // 2] if s else float("nan")
        div = diversity(thumbs(paths))

        notes = []
        if len(paths) < MIN_FRAMES:
            notes.append("TOO FEW")
        if div < MIN_DIVERSITY:
            notes.append("TOO UNIFORM")
        if s and p50 < 40:
            notes.append("soft")
        if notes:
            problems.append((take, ", ".join(notes)))

        print(f"{take:<16}{len(paths):>7}{p50:>11.0f}{div:>11.1f}  {', '.join(notes)}")
        r = rejects.get(take)
        if r:
            k, b, d, sh = (int(r[x]) for x in ("kept", "blur", "duplicate", "shake"))
            seen = k + b + d + sh
            print(f"{'':<16}{'':>7}{'':>11}{'':>11}  "
                  f"rejected: blur {b} · dup {d} · shake {sh}"
                  + (f"  ({100*k/seen:.0f}% kept of {seen} judged)" if seen else ""))
            if seen and b / seen > 0.5:
                print(f"{'':<34}! blur-dominated -- sweeping too fast, or the gate is too tight")
            if seen and d / seen > 0.8:
                print(f"{'':<34}! duplicate-dominated -- move more, or loosen novelty")

    missing = [t for t in (
        "full-overhead", "partial-left", "partial-right", "partial-top",
        "partial-bottom", "distance", "tilted", "steep", "negative",
    ) if t not in by_take]

    print(f"\n{total} frames across {len(by_take)} takes")
    if missing:
        print(f"not yet shot: {', '.join(missing)}")
    if problems:
        print("\nreshoot these:")
        for take, why in problems:
            print(f"  {take:<16} {why}")
    else:
        print("\nall takes look usable")

    partial = sum(len(v) for k, v in by_take.items() if k.startswith("partial"))
    if total and partial / total < 0.30:
        print(f"\n! only {100*partial/total:.0f}% partial-edge frames. Those drive the "
              f"guidance cues; aim for ~40%.")

    if args.review:
        import subprocess
        out = root / "review"
        out.mkdir(exist_ok=True)
        for take, paths in sorted(by_take.items()):
            d = out / take
            d.mkdir(exist_ok=True)
            for p in paths:
                (d / p.name).write_bytes(p.read_bytes())
        print(f"\nper-take folders -> {out}")
        print("contact sheets:")
        for take in sorted(by_take):
            print(f"  tools/review-labels.py {out/take} <labels> --out {out}/_sheets_{take}")


if __name__ == "__main__":
    main()
