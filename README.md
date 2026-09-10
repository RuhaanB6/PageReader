# PageReader

An Android camera app that talks a blind user into framing a printed page, then captures it,
flattens it, reads the text off it, and reads it aloud.

The user cannot see the viewfinder. So the app closes that loop with speech: it looks at every
preview frame, works out what is wrong with the framing, and says one thing at a time — *"move
left"*, *"move closer"*, *"hold still"* — until the page is square in view. Then it takes the shot.

> **Status.** The framing loop is built and working. Capture, dewarp, OCR and the reading UI are
> designed and validated but **not yet implemented** — see [Roadmap](#roadmap). Each section below
> marks which side of that line it falls on.

---

## Design principles

These are constraints, not preferences. They shape every decision in the codebase.

**Speech is the product.** The on-screen overlay exists for sighted developers. Every signal drawn
on screen must also exist as a spoken cue; no feedback is ever visual-only.

**One instruction at a time, spoken on change.** Early versions narrated constantly and were
exhausting to use. `GuidancePolicy` now holds a single active instruction, applies dwell before
speaking and hysteresis before releasing, and re-asserts only after a long silence.

**No control requires precise aiming.** Volume keys, full-screen taps, and shake. A user who cannot
see the screen cannot hit a button.

**No stage fails silently.** Every failure has a spoken message and, where possible, a recovery.
A page that reads imperfectly is worth more than an error.

**No Google Mobile Services.** The target device is a Huawei phone without GMS. That rules out
ML Kit, Play Services, Firebase, and Play Feature Delivery — and it is why the vision and OCR
stacks below are the ones they are.

---

## How it works

### Framing loop — *built*

```
CameraX ImageAnalysis
    │  YUV_420_888 → I420 → BGR Mat, rotated upright
    ▼
PageDetector                      detect/
    │  YOLOv8n via OpenCV cv::dnn, corners refined by CIELAB colour
    ▼
PageObservation                   quad · coverage · clipped edges · tilt · confidence
    │
    ▼
GuidancePolicy                    guidance/
    │  dwell + hysteresis + priority ordering
    ▼
one Instruction  ──►  TtsManager  ──►  "Move left"
```

**Finding the page.** The original detector used Canny plus contours. It could not see a white
sheet on a light desk, because there is no luminance gradient at that border — a ceiling, not a
tuning problem. Two things replaced it:

- **`ColorPageDetector`** scores *paperness* in CIELAB as `L − 2.0 × chroma`: paper is bright **and**
  colourless. Otsu splits the result. This separates page from background when brightness alone
  cannot.
- **`YoloPageDetector`** runs a YOLOv8n detector through OpenCV's `cv::dnn` — **112 ms p50 / 136 ms
  p95 at 256 px** on the target device, with no ONNX Runtime dependency. The box supplies coverage,
  centring and clipping; colour refinement runs *inside* the box to recover true corners for the
  dewarp.

The architecture is **box-first** for a reason: everything guidance needs comes from the bounding
box. A precise quad is only required at the moment of capture.

**Deciding what to say.** `PageObservation.clipped` is a `Set<Side>` — a topological fact that
survives zero contrast, where the old per-side Hough line positions did not. `GuidancePolicy` uses
separate trigger and release thresholds so an instruction cannot oscillate at the boundary, and
emits `capture = true` exactly once per steady-hold episode.

### Reading pipeline — *planned*

```
ImageCapture ──► StillCapture ──► PageDewarper ──► TesseractOcr ──► PagePlayer
   full-res         upright S      warpPerspective     hOCR          speaks it
                                    → flat page G    → TextBlocks
```

**Dewarp.** Corners are **re-detected on the still**, never scaled up from the preview: the two
streams have different crops, are captured at different moments, and the hand moves between them.
A 4-point homography flattens the page, clamped to 3000 px and *upscaled to at least 2000 px* —
measurement showed Tesseract degrades sharply below that.

**OCR and layout in one pass.** Tesseract 5 via
[Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android). `getHOCRText()` returns not
just text and per-word confidence but **block types** — `ocr_header`, `ocr_caption`, `ocr_photo`,
`ocr_textfloat` — so headings, captions, figures and sidebars all come from the same call. One
dependency covers what the Python prototype needed two models for.

**Reading it out.** The app lands on a player and starts reading: volume keys skip paragraphs, a tap
plays or pauses, a shake stops. One swipe away is explore-by-touch — the flattened page with
coloured regions, where dragging a finger announces each region and a double-tap reads it. With
TalkBack on, the same regions are exposed as accessibility nodes so TalkBack's own gestures work
natively; with it off, the app's controls cover the same ground.

**Closing the loop.** OCR failure feeds back into guidance. If an edge was clipped at capture, or
mean confidence comes back low, the app says *why* and offers a retake — rather than reading
half a page and leaving the user to wonder.

---

## Project layout

```
app/                          the accessibility app
  detect/       PageObservation · PageDetector · ColorPageDetector
                MaskToQuad · YoloDnnEngine · YoloPageDetector
  guidance/     Instruction · GuidancePolicy · ShakeDetector
  camera/       CameraManager — CameraX binding, YUV→BGR
  audio/        TtsManager
  telemetry/    SessionRecorder — debug builds only

collector/                    a SEPARATE app for shooting training data
                              silent and phase-gated by design, so it never
                              ships inside the accessibility APK

tools/                        collect → verify → auto-label → gap-fill →
                              prune → split → train → export
```

## Building

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug        # build
./gradlew test                 # JVM unit tests
./gradlew connectedAndroidTest # instrumented — physical device only
./gradlew installDebug
```

**The app does not run on an emulator.** `abiFilters` is `arm64-v8a` + `armeabi-v7a` on purpose:
OpenCV ships full natives per ABI and the x86 slices added ~105 MB. Test on hardware.

Two rules worth knowing before touching the frame path: always `release()` every OpenCV `Mat` and
`close()` every `ImageProxy` — per-frame leaks kill the app in seconds — and detector constants are
empirically tuned against real frames, so never "clean up" one without re-testing on a device.

## Roadmap

| | Milestone | Status |
|---|---|---|
| M0–M2 | Detector runtime gate, colour + neural detectors, guidance policy | **built** |
| — | Training-data collection across surfaces, documents and lighting | in progress |
| M3 | Still capture, manual shutter | planned |
| M4 | Perspective dewarp | planned |
| M5 | Tesseract OCR with hOCR layout | planned |
| M6 | Reading order and block labelling | planned |
| M7 | Reading UI: player, explore-by-touch, persistence | planned |

Deferred: table detection · Hindi · multi-page capture · curved-page dewarp · handwriting · maths.

## Acknowledgements

Ports and extends a Python prototype (DocLayout-YOLO + EasyOCR + a click-to-speak inspector) that
established the region-based reading model this app is built around.
