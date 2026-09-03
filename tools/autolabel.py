#!/usr/bin/env python3
"""Propose YOLO-format `page` boxes for a folder of photos, using FastSAM.

Labelling 300 images by hand is the slow step in this project. This does the
bulk of it: FastSAM proposes every object mask in the image, and each mask is
scored on how page-like it is. You then correct the rejects rather than drawing
300 boxes from scratch.

Scoring deliberately mirrors MaskToQuad.rectangularity -- a sheet of paper very
nearly fills its own minimum-area rectangle, while a carpet, a keyboard or a
shadow does not. The difference is that FastSAM's masks are vastly better than
the Otsu threshold the app falls back on, so the same test is far more reliable
here than it is on device.

Boxes are the VISIBLE extent, clipped to the frame. A page running off the edge
gets a box that touches that edge -- which is exactly the signal the guidance
layer reads as "move that way", so partial pages must be labelled, not skipped.

Usage:
  tools/autolabel.py <image-dir> [--out labels] [--min-score 0.55] [--limit N]
"""
import argparse
import math
import sys
from pathlib import Path

import cv2
import numpy as np


def rectangularity(mask_u8):
    """Mask area over its minimum-area rectangle, plus the box itself."""
    cnts, _ = cv2.findContours(mask_u8, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return 0.0, None
    c = max(cnts, key=cv2.contourArea)
    area = cv2.contourArea(c)
    if area <= 0:
        return 0.0, None
    (_, _), (w, h), _ = cv2.minAreaRect(c)
    if w <= 0 or h <= 0:
        return 0.0, None
    return min(area / (w * h), 1.0), cv2.boundingRect(c)


PAIR_LIMIT = 6


def adjacent(m1, m2, W, H, gap_frac=0.02):
    """True if two masks touch or nearly touch, so their union is plausible."""
    b1, b2 = cv2.boundingRect(m1), cv2.boundingRect(m2)
    g = max(4, int(gap_frac * max(W, H)))
    x1, y1, w1, h1 = b1
    x2, y2, w2, h2 = b2
    return not (x1 > x2 + w2 + g or x2 > x1 + w1 + g or
                y1 > y2 + h2 + g or y2 > y1 + h1 + g)


def score_mask(mask_u8, frame_area, frame_wh, take=""):
    """How much this mask looks like a page. 0 rejects it outright."""
    rect, box = rectangularity(mask_u8)
    if box is None:
        return 0.0, None
    x, y, w, h = box
    W, H = frame_wh
    area_frac = (w * h) / frame_area

    # Background rejection. The first pass scored the carpet, not the page, on
    # every single frame: a surface filling the view is both huge and perfectly
    # rectangular, so it beat the notebook sitting on top of it. A region
    # touching all four borders is the scene, not an object in it.
    m = max(3, int(0.01 * max(W, H)))
    touches = ((x <= m) + (y <= m) + (x + w >= W - m) + (y + h >= H - m))

    # In the `distance` take the page is deliberately brought closer than the
    # frame, so it legitimately overflows all four borders -- the very shape
    # that means "background" anywhere else. Without this exemption the close
    # half of every distance take is dropped; it cost 14 of 23 frames.
    allow_full = take == "distance"
    if touches >= 4 and not allow_full:
        return 0.0, None

    upper = 0.985 if allow_full else 0.80
    if not (0.02 < area_frac < upper):
        return 0.0, None
    # Reject slivers. Real pages stay within roughly 1:4 either way even when
    # foreshortened.
    aspect = max(w, h) / max(1, min(w, h))
    if aspect > 6.0:
        return 0.0, None
    # Perspective genuinely costs rectangularity: a page seen at a steep angle
    # is a trapezoid, and a trapezoid fills less of its bounding rectangle than
    # a square-on sheet does. At 0.55 this dropped 8 of 21 steep frames and 7 of
    # 23 tilted ones -- correct answers rejected for being correctly foreshortened.
    if rect < 0.45:
        return 0.0, None

    # Size preference peaks rather than saturates, so a frame-filling region
    # cannot tie a well-framed page on size and then win on shape.
    #
    # The peak sat at 0.22 with a narrow spread, which turned out to punish
    # correct answers: a page filling 65% of the frame scored 0.49 while a lit
    # sub-region of that same page scored 0.94, so a hard shadow across the
    # sheet reliably produced a box around half of it. Pages genuinely run from
    # 20% to 80% of the frame, so the curve has to be broad enough to say so.
    peak = 0.40
    area_score = math.exp(-((math.log(area_frac / peak)) ** 2) / (2 * 1.1 ** 2))

    # Objects sitting away from the edges are more likely to be the subject.
    edge_penalty = 1.0 if allow_full else (1.0 - 0.15 * touches)

    return (rect ** 2) * area_score * edge_penalty, box


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("images")
    ap.add_argument("--out", default=None, help="label dir (default <images>/../labels)")
    ap.add_argument("--min-score", type=float, default=0.30)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--model", default="FastSAM-s.pt")
    args = ap.parse_args()

    from ultralytics import FastSAM

    img_dir = Path(args.images)
    paths = sorted(
        p for p in img_dir.rglob("*")
        if p.suffix.lower() in {".jpg", ".jpeg", ".png"}
    )
    if args.limit:
        paths = paths[: args.limit]
    if not paths:
        sys.exit(f"no images under {img_dir}")

    out_dir = Path(args.out) if args.out else img_dir.parent / "labels"
    out_dir.mkdir(parents=True, exist_ok=True)

    model = FastSAM(args.model)
    kept = skipped = 0
    report = []

    for i, p in enumerate(paths, 1):
        img = cv2.imread(str(p))
        if img is None:
            continue
        H, W = img.shape[:2]
        frame_area = float(H * W)

        # The take is encoded in the filename by the collector, and it carries
        # real information the pixels do not.
        take = p.stem.rsplit("_", 1)[0]

        # A `negative` take is a bare surface by construction. Guessing from
        # pixels boxed shadow edges and panel seams in 16 of 21 frames, and a
        # false box on a negative is worse than no label at all -- it teaches
        # the model that empty desk is a page.
        if take == "negative":
            (out_dir / (p.stem + ".txt")).write_text("")
            skipped += 1
            report.append((p.name, 0.0, None))
            if i % 25 == 0 or i == len(paths):
                print(f"  {i}/{len(paths)}  boxed={kept} empty={skipped}", flush=True)
            continue

        res = model(str(p), verbose=False, retina_masks=True, imgsz=640, conf=0.4, iou=0.9)
        best = (0.0, None)
        masks = []
        if res and res[0].masks is not None:
            for m in res[0].masks.data.cpu().numpy():
                mask_u8 = (cv2.resize(m.astype(np.uint8), (W, H),
                                      interpolation=cv2.INTER_NEAREST) * 255)
                sc, box = score_mask(mask_u8, frame_area, (W, H), take)
                if sc > best[0]:
                    best = (sc, box)
                if box is not None or mask_u8.any():
                    masks.append(mask_u8)

        # Anything lying across a page -- a shadow edge, a glare band, a pen --
        # makes the segmenter return it in pieces, and the best single piece is
        # then a box around part of the sheet. Scoring the union of nearby pairs
        # recovers the whole page without special-casing any one cause.
        masks = sorted(masks, key=lambda m: -int((m > 0).sum()))[:PAIR_LIMIT]
        for a in range(len(masks)):
            for b in range(a + 1, len(masks)):
                if not adjacent(masks[a], masks[b], W, H):
                    continue
                union = cv2.bitwise_or(masks[a], masks[b])
                sc, box = score_mask(union, frame_area, (W, H), take)
                if sc > best[0]:
                    best = (sc, box)

        # At the close end of a distance sweep the page covers the whole view
        # with no boundary visible, so there is nothing for a segmenter to find.
        # An empty label would be wrong: "page overflows the frame" is precisely
        # the state that has to produce "move back", and a model that predicts
        # nothing there leaves the app silent when the user is closest to a
        # usable shot. The page is in view by construction in this take.
        if take == "distance" and (best[1] is None or best[0] < args.min_score):
            label = out_dir / (p.stem + ".txt")
            label.write_text("0 0.500000 0.500000 0.995000 0.995000\n")
            kept += 1
            report.append((p.name, 1.0, (0, 0, W, H)))
            if i % 25 == 0 or i == len(paths):
                print(f"  {i}/{len(paths)}  boxed={kept} empty={skipped}", flush=True)
            continue

        label = out_dir / (p.stem + ".txt")
        if best[1] is not None and best[0] >= args.min_score:
            x, y, w, h = best[1]
            # YOLO format: class cx cy w h, all normalised.
            label.write_text(
                f"0 {(x + w / 2) / W:.6f} {(y + h / 2) / H:.6f} {w / W:.6f} {h / H:.6f}\n"
            )
            kept += 1
            report.append((p.name, best[0], (x, y, w, h)))
        else:
            # An empty file is a valid YOLO negative -- and negatives are how the
            # model learns that a bare desk is not a document.
            label.write_text("")
            skipped += 1
            report.append((p.name, best[0], None))

        if i % 25 == 0 or i == len(paths):
            print(f"  {i}/{len(paths)}  boxed={kept} empty={skipped}", flush=True)

    print(f"\n{kept} boxed, {skipped} left empty (negatives / low confidence)")
    print(f"labels -> {out_dir}")
    print("\nReview them before training:")
    print(f"  tools/review-labels.py {img_dir} {out_dir}")


if __name__ == "__main__":
    main()
