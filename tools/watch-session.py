#!/usr/bin/env python3
"""Watch a PageReader run live, from the host, while someone uses the phone.

`analyse-session.py` summarises a session after it is pulled. This is the other
half: it streams what is happening *now*, so a change can be judged while the
person holding the phone is still holding it.

Two sources, because the interesting failures show up in different places:

  - the on-device SessionRecorder JSONL, for what the app SAYS and when it
    captures. Only spoken cues and captures are printed. State flapping happens
    several times a second and would bury everything else.
  - logcat, for detector-level warnings that never reach speech -- currently the
    refinement gate rejecting a colour quad. Aggregated, since it can fire on
    every frame.

Usage:
    tools/watch-session.py            # follow the newest session
    tools/watch-session.py --quiet    # captures only, no cues

Requires the app to be running (a new session directory appears on launch) and
`adb` on PATH. Ctrl-C to stop.
"""
import argparse
import json
import re
import subprocess
import sys
import threading
import time

PKG = "com.pagereader.android"
BASE = f"/sdcard/Android/data/{PKG}/files/sessions"
GATE_RE = re.compile(r"covers only ([0-9.]+)")
HEARTBEAT_S = 20


def sh(cmd, timeout=15):
    try:
        return subprocess.run(["adb", "shell", cmd], capture_output=True,
                              text=True, timeout=timeout).stdout
    except Exception:
        return ""


def watch_gate():
    """Aggregate refinement-gate rejections; they can fire every frame."""
    try:
        p = subprocess.Popen(["adb", "logcat", "-s", "YoloPageDetector"],
                             stdout=subprocess.PIPE, text=True, bufsize=1)
    except FileNotFoundError:
        return
    vals, first, last = [], True, time.time()
    for line in p.stdout:
        m = GATE_RE.search(line)
        if m:
            vals.append(float(m.group(1)))
            if first:
                print(f"[gate] first rejection: coverage {vals[-1]:.2f} -> using the box",
                      flush=True)
                first = False
        now = time.time()
        if now - last >= HEARTBEAT_S:
            if vals:
                print(f"[gate] {len(vals)} rejections in {int(now - last)}s | "
                      f"coverage {min(vals):.2f}-{max(vals):.2f} "
                      f"(mean {sum(vals) / len(vals):.2f}) -> box fallback", flush=True)
                vals = []
            last = now


def watch_events(quiet):
    session, seen, last_hb = None, 0, time.time()
    frames, lats, hist, confs = 0, [], {}, []
    print("watching for a session…", flush=True)
    while True:
        newest = sh(f"ls -t {BASE} 2>/dev/null | head -1").strip()
        if newest and newest != session:
            session, seen = newest, 0
            frames, lats, hist, confs = 0, [], {}, []
            print(f"=== session {newest} ===", flush=True)
        if not session:
            time.sleep(1)
            continue

        raw = sh(f"tail -n +{seen + 1} {BASE}/{session}/events.jsonl 2>/dev/null")
        for line in raw.splitlines():
            line = line.strip()
            if not line.startswith("{"):
                continue
            seen += 1
            try:
                e = json.loads(line)
            except ValueError:
                continue
            frames += 1
            if e.get("latencyMs"):
                lats.append(e["latencyMs"])
            st = e.get("state")
            hist[st] = hist.get(st, 0) + 1
            conf, cov = e.get("confidence") or 0, e.get("coverage") or 0
            if conf > 0:
                confs.append(conf)
            t = (e.get("tMs") or 0) / 1000.0
            clip = ",".join(e.get("clipped") or []) or "-"

            if e.get("captured"):
                print(f"[{t:6.1f}s] *** CAPTURED ***  conf={conf:.2f} cov={cov:.2f} "
                      f"src={e.get('source')} clip={clip}", flush=True)
            elif e.get("utterance") and not quiet:
                shk = "  SHAKING" if e.get("shaking") else ""
                print(f'[{t:6.1f}s] says "{e["utterance"]:14s}" conf={conf:.2f} '
                      f"cov={cov:.2f} clip={clip:12s} {e.get('latencyMs')}ms{shk}",
                      flush=True)

        now = time.time()
        if now - last_hb >= HEARTBEAT_S:
            last_hb = now
            if frames:
                med = sorted(lats)[len(lats) // 2] if lats else 0
                top = " ".join(f"{k}={v}" for k, v in
                               sorted(hist.items(), key=lambda kv: -kv[1])[:4])
                mc = sorted(confs)[len(confs) // 2] if confs else 0
                print(f"[hb] {frames} frames | median {med}ms | page seen "
                      f"{int(100 * len(confs) / frames)}% of frames, median conf "
                      f"{mc:.2f} | {top}", flush=True)
                frames, lats, hist, confs = 0, [], {}, []
        time.sleep(0.7)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--quiet", action="store_true", help="captures only, no spoken cues")
    args = ap.parse_args()
    threading.Thread(target=watch_gate, daemon=True).start()
    try:
        watch_events(args.quiet)
    except KeyboardInterrupt:
        sys.exit(0)
