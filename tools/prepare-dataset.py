#!/usr/bin/env python3
"""Split images + labels into a YOLO dataset and write data.yaml.

Usage:
  tools/prepare-dataset.py <image-dir> <label-dir> [--out dataset] [--val 0.2] [--test 0.1]
"""
import argparse
import random
import shutil
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("images")
    ap.add_argument("labels")
    ap.add_argument("--out", default="dataset")
    ap.add_argument("--val", type=float, default=0.2)
    ap.add_argument("--test", type=float, default=0.1)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    img_dir, lbl_dir, out = Path(args.images), Path(args.labels), Path(args.out)
    paths = sorted(p for p in img_dir.rglob("*")
                   if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
    if not paths:
        raise SystemExit(f"no images under {img_dir}")

    random.Random(args.seed).shuffle(paths)
    n = len(paths)
    n_val, n_test = int(n * args.val), int(n * args.test)
    splits = {
        "val": paths[:n_val],
        "test": paths[n_val:n_val + n_test],
        "train": paths[n_val + n_test:],
    }

    empties = boxed = 0
    for split, items in splits.items():
        (out / "images" / split).mkdir(parents=True, exist_ok=True)
        (out / "labels" / split).mkdir(parents=True, exist_ok=True)
        for p in items:
            shutil.copy2(p, out / "images" / split / p.name)
            src = lbl_dir / (p.stem + ".txt")
            dst = out / "labels" / split / (p.stem + ".txt")
            text = src.read_text() if src.exists() else ""
            dst.write_text(text)
            if text.strip():
                boxed += 1
            else:
                empties += 1

    # `path` has to be absolute: Ultralytics resolves a relative one against its
    # own configured datasets directory, not against the yaml's location, so
    # "path: ." silently looks for images next to the repo root and fails.
    (out / "data.yaml").write_text(
        f"path: {out.resolve()}\n"
        "train: images/train\n"
        "val: images/val\n"
        "test: images/test\n"
        "nc: 1\n"
        "names: [page]\n"
    )

    print(f"{n} images -> {out}")
    for split, items in splits.items():
        print(f"  {split:<6} {len(items)}")
    print(f"\n{boxed} with a box, {empties} background negatives "
          f"({100 * empties / max(n, 1):.0f}%)")
    if empties / max(n, 1) < 0.08:
        print("  ! few negatives -- add photos of bare desks/carpets to cut false positives")
    print(f"\nTrain:\n  .venv/bin/yolo train model=yolov8n.pt data={out}/data.yaml "
          f"imgsz=256 epochs=100 batch=16 name=page")
    print(f"Then export:\n  .venv/bin/yolo export model=runs/detect/page/weights/best.pt "
          f"format=onnx imgsz=256 opset=12 simplify=True")


if __name__ == "__main__":
    main()
