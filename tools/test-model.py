#!/usr/bin/env python3
"""Run a trained model over a folder of images and report how it did.

Point this at frames the model has never seen. The interesting number is not
mAP on a held-out split of the same shoot -- that mostly measures memorisation
of one scene -- but the detection rate on a genuinely different surface,
document and lighting.

Usage:
  tools/test-model.py <weights.pt> <image-dir> [--out out] [--conf 0.25]
"""
import argparse
import statistics as st
from pathlib import Path

import cv2
import numpy as np

CELL = 320


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("weights")
    ap.add_argument("images")
    ap.add_argument("--out", default="/tmp/pr-modeltest")
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--limit", type=int, default=60)
    ap.add_argument("--cols", type=int, default=6)
    args = ap.parse_args()

    from ultralytics import YOLO

    model = YOLO(args.weights)
    paths = sorted(p for p in Path(args.images).rglob("*")
                   if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
    if not paths:
        raise SystemExit(f"no images under {args.images}")
    step = max(1, len(paths) // args.limit)
    paths = paths[::step][:args.limit]

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    cells, confs, hits, areas = [], [], 0, []
    for i, p in enumerate(paths):
        img = cv2.imread(str(p))
        if img is None:
            continue
        H, W = img.shape[:2]
        r = model.predict(str(p), conf=args.conf, imgsz=256, verbose=False)[0]

        best = None
        if len(r.boxes):
            k = int(r.boxes.conf.argmax())
            best = (r.boxes.xyxy[k].tolist(), float(r.boxes.conf[k]))

        if best:
            hits += 1
            (x1, y1, x2, y2), c = best
            confs.append(c)
            areas.append(((x2 - x1) * (y2 - y1)) / (W * H))
            cv2.rectangle(img, (int(x1), int(y1)), (int(x2), int(y2)),
                          (0, 255, 0), max(2, W // 250))
            cv2.putText(img, f"{c:.2f}", (int(x1) + 5, int(y1) + 34),
                        cv2.FONT_HERSHEY_SIMPLEX, 1.1, (0, 255, 0), 3)

        s = CELL / max(H, W)
        small = cv2.resize(img, (int(W * s), int(H * s)))
        cell = np.full((CELL, CELL, 3), 30, np.uint8)
        yo, xo = (CELL - small.shape[0]) // 2, (CELL - small.shape[1]) // 2
        cell[yo:yo + small.shape[0], xo:xo + small.shape[1]] = small
        if not best:
            cv2.rectangle(cell, (0, 0), (CELL - 1, CELL - 1), (0, 0, 200), 4)
        cv2.putText(cell, str(i), (6, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)
        cells.append(cell)

    rows = []
    for r0 in range(0, len(cells), args.cols):
        row = cells[r0:r0 + args.cols]
        while len(row) < args.cols:
            row.append(np.full((CELL, CELL, 3), 30, np.uint8))
        rows.append(np.hstack(row))
    cv2.imwrite(str(out / "result.jpg"), np.vstack(rows), [cv2.IMWRITE_JPEG_QUALITY, 88])

    n = len(cells)
    print(f"{args.weights}  ->  {args.images}")
    print(f"  {n} frames, detected in {hits} ({100*hits/max(n,1):.0f}%)  conf>={args.conf}")
    if confs:
        print(f"  confidence  min={min(confs):.2f} median={st.median(confs):.2f} max={max(confs):.2f}")
        print(f"  box area    median {100*st.median(areas):.0f}% of frame")
    print(f"  contact sheet -> {out/'result.jpg'}  (red border = missed)")


if __name__ == "__main__":
    main()
