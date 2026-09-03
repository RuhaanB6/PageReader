#!/usr/bin/env bash
# Pulls PageReader session recordings off the device and summarises the newest one.
#
#   ./tools/pull-session.sh            # pull all, analyse newest
#   ./tools/pull-session.sh --list     # just list what is on the device
set -euo pipefail

PKG=com.pagereader.android
REMOTE="/sdcard/Android/data/$PKG/files/sessions"
LOCAL="$(cd "$(dirname "$0")/.." && pwd)/sessions"

if ! adb get-state >/dev/null 2>&1; then
  echo "No device. Plug in, unlock, and allow USB debugging." >&2
  exit 1
fi

if [[ "${1:-}" == "--list" ]]; then
  adb shell ls -1 "$REMOTE" 2>/dev/null || echo "(no sessions recorded yet)"
  exit 0
fi

mkdir -p "$LOCAL"
echo "Pulling from $REMOTE ..."
adb pull -a "$REMOTE" "$LOCAL/.." >/dev/null 2>&1 || {
  echo "Nothing to pull. Run the app first, then background it (the log flushes on stop)." >&2
  exit 1
}

NEWEST="$(ls -1d "$LOCAL"/*/ 2>/dev/null | sort | tail -1)"
if [[ -z "$NEWEST" ]]; then
  echo "No sessions found locally." >&2
  exit 1
fi

echo "Newest session: $NEWEST"
python3 "$(dirname "$0")/analyse-session.py" "$NEWEST"
