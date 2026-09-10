#!/usr/bin/env bash
# Fetches the Tesseract language data the app needs.
#
# Mirrors tools/fetch-model.sh: the traineddata is gitignored, because it is a
# 4 MB binary that never changes and does not belong in history.
#
# tessdata_fast, not tessdata_best: `fast` is the integer-quantised LSTM model
# and is several times quicker on a phone. Accuracy was measured on ten real
# pages at mean confidence 0.82-0.89, which is well clear of the 0.60 the
# pipeline treats as usable.
#
# osd.traineddata is deliberately NOT fetched. It is 10 MB and only used by
# PSM_AUTO_OSD (1); we run PSM_AUTO (3) and handle rotation with the
# confidence-triggered 90-degree retry instead.
set -euo pipefail

DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/tessdata"
URL="https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata"

mkdir -p "$DEST"
if [ -f "$DEST/eng.traineddata" ]; then
  echo "eng.traineddata already present at $DEST"
  exit 0
fi

echo "fetching eng.traineddata -> $DEST"
curl -fsSL -o "$DEST/eng.traineddata.tmp" "$URL"
mv "$DEST/eng.traineddata.tmp" "$DEST/eng.traineddata"
ls -la "$DEST/eng.traineddata"
