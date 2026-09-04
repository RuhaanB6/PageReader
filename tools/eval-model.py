#!/usr/bin/env python3
"""Score a trained page detector on IoU, not on whether it emitted a box.

tools/test-model.py reports detection rate, which is the wrong question: a model
that emits one frame-filling box every time scores 100% and is useless. That is
exactly what happened to the smoke model -- mAP50 0.976 on its own split, 89%
of the frame boxed on an unseen scene, and the detection-rate metric called it
finished.

Three things are measured here, and two of them need no labels at all:

  negatives   frames whose name starts with negative_ have empty ground truth
              by construction. Any box is a false positive. Exact, no labelling.
  localisation where a YOLO .txt label exists, IoU against it.
  geometry    on unlabelled positives, the distribution of box area. No ground
              truth, so this diagnoses rather than scores -- but a median area
              far above what a page plausibly occupies is the carpet-boxing
              signature.

Usage:
  tools/eval-model.py <weights.pt> --images <dir> [--labels <dir>] [--conf 0.25]
"""
import argparse
import sys
from collections import defaultdict
from pathlib import Path


def xywhn_to_xyxy(x, y, w, h, W, H):
    return [(x - w / 2) * W, (y - h / 2) * H, (x + w / 2) * W, (y + h / 2) * H]


def iou(a, b):
    ix0, iy0 = max(a[0], b[0]), max(a[1], b[1])
    ix1, iy1 = min(a[2], b[2]), min(a[3], b[3])
    iw, ih = max(0.0, ix1 - ix0), max(0.0, iy1 - iy0)
    inter = iw * ih
    if inter <= 0:
        return 0.0
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua > 0 else 0.0


def pct(n, d):
    return f"{100 * n / d:.0f}%" if d else "n/a"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("weights")
    ap.add_argument("--images", required=True)
    ap.add_argument("--labels", default=None,
                    help="YOLO .txt dir; matched by stem. Optional.")
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--iou-hit", type=float, default=0.5)
    ap.add_argument("--imgsz", type=int, default=256)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    from ultralytics import YOLO

    imgs = sorted(p for p in Path(args.images).rglob("*")
                  if p.suffix.lower() in (".jpg", ".jpeg", ".png"))
    if args.limit:
        imgs = imgs[:args.limit]
    if not imgs:
        sys.exit(f"no images under {args.images}")

    labels = Path(args.labels) if args.labels else None
    model = YOLO(args.weights)

    # take -> tallies
    neg = defaultdict(lambda: {"n": 0, "fp": 0, "confs": []})
    pos = defaultdict(lambda: {"n": 0, "det": 0, "areas": [], "confs": []})
    loc = {"n": 0, "hit": 0, "ious": [], "miss": 0, "extra": 0}

    for p in imgs:
        take = p.name.rsplit("_", 1)[0]
        r = model.predict(str(p), imgsz=args.imgsz, conf=args.conf,
                          device="cpu", verbose=False)[0]
        H, W = r.orig_shape
        boxes = [b.tolist() for b in r.boxes.xyxy] if r.boxes is not None else []
        confs = [float(c) for c in r.boxes.conf] if r.boxes is not None else []

        gt = []
        if labels:
            lf = labels / (p.stem + ".txt")
            if lf.exists():
                for line in lf.read_text().split("\n"):
                    f = line.split()
                    if len(f) >= 5:
                        gt.append(xywhn_to_xyxy(*map(float, f[1:5]), W, H))

        is_neg = take == "negative" or (labels and (labels / (p.stem + ".txt")).exists()
                                        and not gt)
        if is_neg:
            d = neg[take]
            d["n"] += 1
            if boxes:
                d["fp"] += 1
                d["confs"] += confs
            continue

        d = pos[take]
        d["n"] += 1
        if boxes:
            d["det"] += 1
            d["confs"] += confs
            d["areas"] += [((b[2] - b[0]) * (b[3] - b[1])) / (W * H) for b in boxes]

        if gt:
            loc["n"] += len(gt)
            used = set()
            for g in gt:
                best, bi = 0.0, -1
                for i, b in enumerate(boxes):
                    if i in used:
                        continue
                    v = iou(g, b)
                    if v > best:
                        best, bi = v, i
                if bi >= 0:
                    used.add(bi)
                loc["ious"].append(best)
                if best >= args.iou_hit:
                    loc["hit"] += 1
                else:
                    loc["miss"] += 1
            loc["extra"] += max(0, len(boxes) - len(used))

    print(f"\n{Path(args.weights).parent.parent.name}  ·  conf>={args.conf}  "
          f"·  imgsz={args.imgsz}  ·  {len(imgs)} frames\n")

    if loc["n"]:
        m = sorted(loc["ious"])
        print("LOCALISATION  (labelled frames -- the metric that matters)")
        print(f"  ground-truth boxes     {loc['n']}")
        print(f"  hits at IoU>={args.iou_hit}        {loc['hit']}  ({pct(loc['hit'], loc['n'])})")
        print(f"  median IoU             {m[len(m)//2]:.2f}")
        print(f"  mean IoU               {sum(m)/len(m):.2f}")
        print(f"  spurious extra boxes   {loc['extra']}")
        print()

    if neg:
        tn = sum(d["n"] for d in neg.values())
        tf = sum(d["fp"] for d in neg.values())
        print("NEGATIVES  (empty by construction -- any box is a false positive)")
        for t, d in sorted(neg.items()):
            c = d["confs"]
            extra = f"  conf med {sorted(c)[len(c)//2]:.2f}" if c else ""
            print(f"  {t:<14} {d['fp']:>4}/{d['n']:<4} false positives  "
                  f"({pct(d['fp'], d['n'])}){extra}")
        print(f"  {'TOTAL':<14} {tf:>4}/{tn:<4}  ({pct(tf, tn)} of empty frames got a box)")
        print()

    if pos:
        print("POSITIVES  (no ground truth -- diagnostic, not a score)")
        print(f"  {'take':<16}{'frames':>7}{'detected':>10}{'area p50':>10}{'conf p50':>10}")
        for t, d in sorted(pos.items()):
            a = sorted(d["areas"])
            c = sorted(d["confs"])
            print(f"  {t:<16}{d['n']:>7}{pct(d['det'], d['n']):>10}"
                  f"{(f'{a[len(a)//2]:.0%}' if a else '-'):>10}"
                  f"{(f'{c[len(c)//2]:.2f}' if c else '-'):>10}")
        alla = sorted(x for d in pos.values() for x in d["areas"])
        if alla:
            big = sum(1 for x in alla if x > 0.75)
            print(f"\n  median box area {alla[len(alla)//2]:.0%} of frame; "
                  f"{pct(big, len(alla))} of boxes cover >75%")
            if alla[len(alla) // 2] > 0.7:
                print("  ! frame-filling boxes -- the shortcut a single-scene model learns")


if __name__ == "__main__":
    main()
