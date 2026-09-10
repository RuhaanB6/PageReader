#!/usr/bin/env python3
"""Summarise a PageReader session recording.

Prints the things that decide what to change next:
  - detector latency, so we know the frame budget is real
  - the confidence/coverage distribution, so we can tune the colour thresholds
  - how much the app actually TALKED, which is the whole point of the rewrite
  - which snapshots to look at first when something went wrong
"""
import json
import sys
from collections import Counter
from pathlib import Path


def pct(values, p):
    if not values:
        return 0
    s = sorted(values)
    return s[min(int(p / 100 * len(s)), len(s) - 1)]


def bar(n, total, width=28):
    if total == 0:
        return ""
    return "#" * max(0, round(width * n / total))


def main(path):
    root = Path(path)
    events_file = root / "events.jsonl"
    if not events_file.exists():
        sys.exit(f"no events.jsonl in {root}")

    frames, notes = [], []
    for line in events_file.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            e = json.loads(line)
        except json.JSONDecodeError:
            continue
        (notes if "note" in e else frames).append(e)

    meta_file = root / "meta.json"
    if meta_file.exists():
        meta = json.loads(meta_file.read_text())
        print(f"device : {meta.get('device')}  (sdk {meta.get('androidSdk')})")
        print(f"started: {meta.get('startedAt')}")

    if not frames:
        print("\nNo frames recorded.")
        return

    duration_s = frames[-1]["tMs"] / 1000.0
    print(f"frames : {len(frames)} over {duration_s:.1f}s "
          f"({len(frames)/max(duration_s,0.001):.1f}/s)")

    # ---- latency: is the frame budget real? --------------------------------
    lat = [f["latencyMs"] for f in frames]
    print(f"\nDETECTOR LATENCY  p50={pct(lat,50)}ms  p95={pct(lat,95)}ms  max={max(lat)}ms")
    if pct(lat, 95) > 200:
        print("  !! over the 200ms guidance budget")

    # ---- did it find anything? ---------------------------------------------
    with_quad = [f for f in frames if f.get("quad")]
    print(f"\nDETECTION  quad found in {len(with_quad)}/{len(frames)} frames "
          f"({100*len(with_quad)/len(frames):.0f}%)")

    conf = [f["confidence"] for f in frames]
    cov = [f["coverage"] for f in frames]
    print(f"  confidence  p05={pct(conf,5):.2f} p50={pct(conf,50):.2f} p95={pct(conf,95):.2f}")
    print(f"  coverage    p05={pct(cov,5):.2f} p50={pct(cov,50):.2f} p95={pct(cov,95):.2f}")

    print("\n  confidence histogram (threshold for action is 0.45)")
    buckets = Counter(min(int(c * 10), 9) for c in conf)
    for b in range(10):
        lo = b / 10
        mark = " <- minConfidence" if b == 4 else ""
        print(f"    {lo:.1f}-{lo+0.1:.1f} {bar(buckets[b], len(conf))} {buckets[b]}{mark}")

    # ---- how much did it talk? ---------------------------------------------
    utts = [f for f in frames if f.get("utterance")]
    print(f"\nSPEECH  {len(utts)} utterances in {duration_s:.1f}s "
          f"({len(utts)/max(duration_s/60,0.001):.1f}/min)")
    for name, n in Counter(f["utterance"] for f in utts).most_common():
        print(f"    {name:<14} {n}")
    if len(utts) / max(duration_s, 0.001) > 0.5:
        print("  !! more than one utterance every 2s -- still too chatty")

    gaps = [utts[i]["tMs"] - utts[i-1]["tMs"] for i in range(1, len(utts))]
    if gaps:
        print(f"  gap between utterances: min={min(gaps)}ms median={pct(gaps,50)}ms")
        if min(gaps) < 1200:
            print("  !! utterances closer than 1.2s will talk over each other")

    print("\nSTATE")
    for name, n in Counter(f["state"] for f in frames).most_common():
        print(f"    {name:<12} {bar(n, len(frames))} {100*n/len(frames):.0f}%")

    clip = Counter(c for f in frames for c in f.get("clipped", []))
    if clip:
        print("\nCLIPPED BORDERS")
        for name, n in clip.most_common():
            print(f"    {name:<8} {n} frames")

    captures = [f for f in frames if f.get("captured")]
    print(f"\nCAPTURES  {len(captures)}")

    # ---- what should I look at? --------------------------------------------
    print("\nFRAMES WORTH LOOKING AT")
    shots = [f for f in frames if f.get("image")]
    if not shots:
        print("    (no snapshots saved)")
    else:
        misses = [f for f in shots if not f.get("quad")]
        weak = sorted((f for f in shots if f.get("quad")), key=lambda f: f["confidence"])[:3]
        picks = []
        for f in misses[:4]:
            picks.append((f, "no page found"))
        for f in weak:
            picks.append((f, f"weakest with quad, confidence {f['confidence']:.2f}"))
        if not picks:
            picks = [(shots[len(shots)//2], "representative")]
        seen = set()
        for f, why in picks:
            if f["image"] in seen:
                continue
            seen.add(f["image"])
            print(f"    {root/f['image']}")
            print(f"        t={f['tMs']/1000:.1f}s  {why}  cov={f['coverage']:.2f} "
                  f"state={f['state']} said={f.get('utterance')}")

    if notes:
        print("\nNOTES")
        for n in notes:
            print(f"    {n['tMs']/1000:6.1f}s  {n['note']}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
