#!/usr/bin/env bash
# Exports YOLOv8 ONNX models for the M0 runtime gate and drops them where the
# instrumented benchmark looks for them.
#
# Exports BOTH detect and seg on purpose: OpenCV's ONNX importer has open issues
# on YOLOv8 graphs (opencv#24148 Reshape/DFL, opencv#28377 Resize->Concat), and
# the benchmark tells us empirically which of the two our OpenCV 4.9 can run.
#
# Static shapes (no `dynamic`) -- dynamic shapes are what #28377 is about.
set -euo pipefail
cd "$(dirname "$0")/.."

VENV=.venv/bin
ASSETS=app/src/androidTest/assets/models
mkdir -p "$ASSETS" models
cd models

for spec in "yolov8n:det:256" "yolov8n:det:320" "yolov8n-seg:seg:256"; do
  IFS=: read -r model kind size <<< "$spec"
  echo "=== $model @ $size ==="
  "../$VENV/yolo" export model="$model.pt" format=onnx imgsz="$size" opset=12 simplify=True
  out="yolov8n_${kind}_${size}.onnx"
  mv -f "$model.onnx" "$out"
  cp -f "$out" "../$ASSETS/$out"
  echo "  -> $ASSETS/$out ($(du -h "$out" | cut -f1))"
done

echo
echo "Exported. Now run:"
echo "  ./gradlew connectedAndroidTest && adb logcat -d -s PageReaderBench"
