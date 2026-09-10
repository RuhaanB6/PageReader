#!/usr/bin/env python3
"""Remove frames too soft to be worth labelling.

The collector's blur gate is scene-relative, which is right for comparing frames
within one scene but blind to a take that is uniformly bad: if every frame of a
take is soft, the running median drops with them and the gate keeps letting
them through. The steep take came in at p50 556 against 1250-2050 everywhere
else, and its worst frames had no visible page edge at all.

So the floor here is *session*-relative: a fraction of the median across every
take in the collection, which no single bad take can drag down.

Usage:
  tools/prune-blurry.py <collection-dir> [--frac 0.30] [--apply]
"""
import argparse
import csv
import shutil
import statistics as st
from collections import defaultdict
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("collection")
    ap.add_argument("--frac", type=float, default=0.30,
                    help="drop frames below this fraction of the session median")
    ap.add_argument("--apply", action="store_true", help="actually move them")
    args = ap.parse_args()

    root = Path(args.collection)
    man = root / "manifest.csv"
    if not man.exists():
        raise SystemExit(f"no manifest.csv in {root}")

    rows = [r for r in csv.DictReader(man.open()) if r.get("sharpness")]
    for r in rows:
        r["sharpness"] = float(r["sharpness"])
    if not rows:
        raise SystemExit("manifest has no sharpness data")

    session_median = st.median(r["sharpness"] for r in rows)
    floor = session_median * args.frac

    drop = [r for r in rows if r["sharpness"] < floor]
    by_take = defaultdict(int)
    for r in drop:
        by_take[r["take"]] += 1

    print(f"{root.name}")
    print(f"  session median sharpness {session_median:.0f}")
    print(f"  floor at {args.frac:.0%}  ->  {floor:.0f}")
    print(f"  {len(drop)} of {len(rows)} frames below it\n")
    for take, n in sorted(by_take.items(), key=lambda kv: -kv[1]):
        total = sum(1 for r in rows if r["take"] == take)
        flag = "   <- take may need reshooting" if n > total * 0.4 else ""
        print(f"    {take:<16} {n:>3} / {total:<3}{flag}")

    if not args.apply:
        print("\n(dry run -- pass --apply to move them to rejected/)")
        return

    rej = root / "rejected"
    rej.mkdir(exist_ok=True)
    moved = 0
    for r in drop:
        src = root / "frames" / r["file"]
        if src.exists():
            shutil.move(str(src), str(rej / r["file"]))
            moved += 1
        # Labels follow the frame, wherever they live.
        for d in (root / "labels", root / "needs-labels"):
            for f in (d / (Path(r["file"]).stem + ".txt"), d / r["file"]):
                if f.exists():
                    f.unlink()
    print(f"\nmoved {moved} frames to {rej}")
    print("kept in rejected/ rather than deleted, in case a call looks wrong later")


if __name__ == "__main__":
    main()
