#!/usr/bin/env python3
"""Split a collection into what still needs a human and what only needs a glance.

Two very different jobs, so two outputs:

  needs-labels/   frames with no box at all -- draw one
  verify/         contact sheets of the auto-labelled frames -- scan, note the
                  index of anything wrong

Negatives are excluded from both: they are correctly empty by construction.

Usage:
  tools/label-todo.py <collection-dir>
"""
import shutil
import sys
from collections import Counter
from pathlib import Path


def main(root):
    root = Path(root)
    frames, labels = root / "frames", root / "labels"
    if not labels.is_dir():
        sys.exit(f"no labels/ under {root} -- run tools/autolabel.py first")

    todo_dir = root / "needs-labels"
    if todo_dir.exists():
        shutil.rmtree(todo_dir)
    todo_dir.mkdir()

    todo, boxed, by_take = [], [], Counter()
    for img in sorted(frames.glob("*.jpg")):
        take = img.stem.rsplit("_", 1)[0]
        if take == "negative":
            continue
        lbl = labels / (img.stem + ".txt")
        if lbl.exists() and lbl.read_text().strip():
            boxed.append(img)
        else:
            todo.append(img)
            by_take[take] += 1

    for img in todo:
        shutil.copy2(img, todo_dir / img.name)
        # An empty .txt beside it, so a labelling tool opens the pair cleanly
        # and export lands back in YOLO format.
        (todo_dir / (img.stem + ".txt")).write_text("")

    print(f"{root.name}\n")
    print(f"  {len(boxed)} auto-labelled  ->  scan the contact sheets")
    print(f"  {len(todo)} need a box      ->  {todo_dir}")
    if by_take:
        print("\n  missing boxes by take:")
        for t, n in by_take.most_common():
            print(f"    {t:<16} {n}")

    print(f"""
DRAW THE MISSING {len(todo)}
  .venv/bin/pip install labelImg
  .venv/bin/labelImg {todo_dir} 2>/dev/null

  In labelImg: press 'w' to draw, type 'page', 'd' for next, ctrl+s to save.
  Set the format to YOLO (button on the left toolbar) before you start.
  Box the VISIBLE part of the page only -- do not guess past the frame edge.

  Then copy the labels back:
    cp {todo_dir}/*.txt {labels}/

SCAN THE REST
  .venv/bin/python tools/review-labels.py {frames} {labels} --out {root}/verify
  Open {root}/verify/sheet*.jpg and note the index of any wrong box.
""")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
