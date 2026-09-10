#!/usr/bin/env bash
# Pulls collector output off the device and verifies each take.
#
#   ./tools/pull-collection.sh            # pull all, verify newest
#   ./tools/pull-collection.sh --all      # verify every collection
#   ./tools/pull-collection.sh --list     # what is on the device
set -euo pipefail

PKG=com.pagereader.collector
REMOTE="/sdcard/Android/data/$PKG/files/collections"
LOCAL="$(cd "$(dirname "$0")/.." && pwd)/collections"

adb get-state >/dev/null 2>&1 || { echo "No device connected." >&2; exit 1; }

if [[ "${1:-}" == "--list" ]]; then
  adb shell ls -1 "$REMOTE" 2>/dev/null || echo "(nothing collected yet)"
  exit 0
fi

mkdir -p "$LOCAL"
echo "Pulling $REMOTE ..."
adb pull -a "$REMOTE" "$LOCAL/.." >/dev/null 2>&1 || {
  echo "Nothing to pull. Record a take first." >&2; exit 1; }

VERIFY="$(dirname "$0")/verify-collection.py"
PY="$(cd "$(dirname "$0")/.." && pwd)/.venv/bin/python"
[[ -x "$PY" ]] || PY=python3

if [[ "${1:-}" == "--all" ]]; then
  # `|| true`: an empty collection is a normal thing to skip past, not
  # a reason to abandon the remaining folders.
  for d in "$LOCAL"/*/; do echo; "$PY" "$VERIFY" "$d" || true; done
else
  NEWEST="$(ls -1d "$LOCAL"/*/ 2>/dev/null | sort | tail -1)"
  [[ -n "$NEWEST" ]] || { echo "No collections found locally." >&2; exit 1; }
  "$PY" "$VERIFY" "$NEWEST" "${@:2}"
fi
