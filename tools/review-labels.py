#!/usr/bin/env python3
"""Render a contact sheet of images with their proposed boxes drawn on.

Auto-labels are only useful if checking them is faster than drawing them. This
lays every image out in a grid with its box overlaid and its index printed, so a
whole batch can be scanned at a glance and the bad ones noted by number.

Usage:
  tools/review-labels.py <image-dir> <label-dir> [--out review] [--cols 6]
"""
import argparse
from pathlib import Path

import cv2
import numpy as np

CELL = 320


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("images")
    ap.add_argument("labels")
    ap.add_argument("--out", default="review")
    ap.add_argument("--cols", type=int, default=6)
    ap.add_argument("--per-sheet", type=int, default=36)
    args = ap.parse_args()

    img_dir, lbl_dir = Path(args.images), Path(args.labels)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    paths = sorted(p for p in img_dir.rglob("*")
                   if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
    cells, index = [], []

    for i, p in enumerate(paths):
        img = cv2.imread(str(p))
        if img is None:
            continue
        H, W = img.shape[:2]
        lbl = lbl_dir / (p.stem + ".txt")
        boxes = []
        if lbl.exists() and lbl.read_text().strip():
            for line in lbl.read_text().strip().splitlines():
                _, cx, cy, w, h = (float(v) for v in line.split())
                boxes.append((int((cx - w / 2) * W), int((cy - h / 2) * H),
                              int(w * W), int(h * H)))

        for (x, y, w, h) in boxes:
            cv2.rectangle(img, (x, y), (x + w, y + h), (0, 255, 0), max(2, W // 300))

        scale = CELL / max(H, W)
        img = cv2.resize(img, (int(W * scale), int(H * scale)))
        cell = np.full((CELL, CELL, 3), 30, np.uint8)
        yo, xo = (CELL - img.shape[0]) // 2, (CELL - img.shape[1]) // 2
        cell[yo:yo + img.shape[0], xo:xo + img.shape[1]] = img

        # Red border on empties so negatives stand out from missed detections.
        if not boxes:
            cv2.rectangle(cell, (0, 0), (CELL - 1, CELL - 1), (0, 0, 200), 4)
        cv2.putText(cell, str(i), (6, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.8,
                    (255, 255, 255), 2)
        cells.append(cell)
        index.append((i, p.name, len(boxes)))

    n = 0
    for start in range(0, len(cells), args.per_sheet):
        chunk = cells[start:start + args.per_sheet]
        rows = []
        for r in range(0, len(chunk), args.cols):
            row = chunk[r:r + args.cols]
            while len(row) < args.cols:
                row.append(np.full((CELL, CELL, 3), 30, np.uint8))
            rows.append(np.hstack(row))
        sheet = np.vstack(rows)
        f = out_dir / f"sheet{n:02d}.jpg"
        cv2.imwrite(str(f), sheet, [cv2.IMWRITE_JPEG_QUALITY, 88])
        print(f"  {f}  ({len(chunk)} images)")
        n += 1

    (out_dir / "index.txt").write_text(
        "\n".join(f"{i}\t{name}\t{'box' if b else 'EMPTY'}" for i, name, b in index)
    )
    boxed = sum(1 for _, _, b in index if b)
    print(f"\n{boxed}/{len(index)} boxed. Red border = no box proposed.")
    print(f"index -> {out_dir/'index.txt'}")


if __name__ == "__main__":
    main()
