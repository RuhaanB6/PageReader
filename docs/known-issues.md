# Known issues

Running list of real defects that are **not blocking the MVP**. The rule: when
something small turns up mid-task, write it down here and keep going. Fix these
once the capture → dewarp → OCR → read-aloud path works end to end.

Each entry says what breaks, how bad it is *for a blind user*, and where the fix
goes. Anything that would silently corrupt what gets read aloud is not on this
list — that gets fixed when found.

Status: `open` · `fixed <date>` · `wontfix <reason>`

---

## Guidance and capture

### Double capture — one page fires two shutters
`fixed 2026-09-05` · `GuidancePolicy`

Fixed ahead of M5, since that is where it becomes user-visible. The latch now
needs both a 4 s refractory window and `captureReleaseFrames` consecutive
unframed observations before re-arming, so the hand-wobble that follows a
shutter cannot re-trigger it. Guarded by three tests including the happy path
(a genuinely new page must still capture), all verified to fail without the fix.

Original report:

`captureFired` clears on a single `ADJUSTING` frame, so one page can produce two
shutters about 1.3 s apart. Harmless today. **Becomes user-visible at M5**: once
the page is read aloud, the second capture restarts the reading from the top
mid-sentence, which is confusing and has no obvious cause from the user's side.

Fix: a refractory period, or require a *sustained* loss of framing rather than a
single frame before the latch reopens.

### "Move closer" while the page already fills the frame
`open` · found 2026-09-05 · `GuidancePolicy`

When confidence drops below `minConfidence` the policy holds the previous
instruction through the dropout. Observed on device with all four borders
clipped and coverage 0.99, still saying `MOVE_CLOSER` — the exact opposite of
what the user needs, and they have no way to tell it is wrong.

Fix: all four borders clipped is unambiguous regardless of confidence, and
should short-circuit to "move back" before the hold-previous logic runs.

### No cue for slant
`open` · found 2026-09-05 · `GuidancePolicy` / `GuidanceCue`

`STRAIGHTEN` measures rotation in the image plane only. Captures with 35–55%
taper between the top and bottom edges never trigger anything, because the top
edge is level — the page is tilted *away* from the camera, not rotated.

Deliberately deferred: the dewarp defines what taper is actually tolerable, so
the threshold cannot be chosen until M4 is in and measured. Specify the cue
then.

---

## TTS reliability (Part 1 of the reliability + UI plan)

Four independent causes of "speech cuts off mid-sentence," the top complaint from
the first real-device session. All four are fixed; the fifth cause from the same
review (TalkBack contention in the reading screen) is deferred to the UI pass
that replaces `ReadingScreen`/`ExploreScreen`.

### Screen sleep suspends the process mid-page
`fixed 2026-09-05` · `MainActivity.onCreate`

Nothing set `FLAG_KEEP_SCREEN_ON` and nothing held a wake lock. A page is
minutes of audio with no touch input, so the display slept and HarmonyOS
PowerGenie suspended the process -- the same `Pged-Freezer` mechanism
documented in `CLAUDE.md`. Speech stopped dead and never resumed. Fix:
`window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)`, set once for
the whole activity.

### An interrupted utterance was read as a finished one
`fixed 2026-09-05` · `TtsManager` / `PagePlayer` / `Speaker`

`onStop` was routed into the same callback as `onDone`, so any `QUEUE_FLUSH`
made `PagePlayer` think the interrupted sentence had finished, advance past
it, and silently drop it. Fix: `Speaker` gained `setOnStopped`, distinct from
`setOnDone`; `PagePlayer.onUtteranceStopped` re-speaks the current sentence
(queued, not flushed) instead of advancing, bounded by a `restartCount` that
gives up after 3 consecutive restarts at the same position rather than looping
forever. Covered by `PagePlayerTest`.

### No audio focus request
`fixed 2026-09-05` · `TtsManager` / `PagePlayer` / `Speaker`

`TtsManager` never called `requestAudioFocus`, so any notification, call or
vendor audio stopped accessibility speech with no recovery. Fix:
`Speaker.requestFocus()`/`abandonFocus()`, requested in `PagePlayer.play()` and
abandoned on pause/stop/finish; `TtsManager` exposes focus changes via
`setOnFocusChange` rather than holding a `PagePlayer` reference, and
`MainActivity` wires transient loss to pause+resume and permanent loss to
pause-and-stay-paused.

### Guidance and mode-change announcements flushed the page being read
`fixed 2026-09-05` · `MainActivity`

Several `ttsManager.say(...)` call sites defaulted to `flush = true` while the
player could be mid-sentence: entering explore mode, the per-region label and
exit announcement inside it, the page summary (which could clip the tail of a
previous verdict/failure message on a fast retake), "Ready for the next page"
in `returnToCamera`, and the shake-stop "Stopped." confirmation eating its own
tail. All changed to `flush = false`. `ttsManager.speak(instruction)` (framing
guidance) deliberately keeps `QUEUE_FLUSH` -- a stale correction is worse than
a clipped word, and the player never runs during FRAMING.

### TalkBack contention in the reading screen
`open` · deferred to the UI-rebuild pass · `ReadingScreen` / new `PageScreen`

`ReadingScreen` recomposes on every block change and TalkBack announces it
through the same engine as the page reading. Fix belongs with the
`PageScreen` rebuild (stable `contentDescription`/`traversalIndex`, "current"
expressed only through drawing, `liveRegion = LiveRegionMode.None` on the
status text), not this pass.

---

## Storage and telemetry

### `SessionRecorder` has no retention cap
`open` · `SessionRecorder`

Six short sessions reached 6.4 MB and nothing ever deletes them. Stays behind
`FLAG_DEBUGGABLE` and must never become user-facing. Wants a cap next time it is
touched.

---

## Test suite

### `PapernessSweepTest` costs ~20 s of the instrumented suite
`open` · found 2026-09-05 · `app/src/androidTest/.../PapernessSweepTest.kt`

It sweeps 35 variants across 3 frames, including a full-resolution σ=51 blur at
249 ms and σ=121 at 829 ms per call. That cost bought the M4-task-zero decision
and the timing evidence behind `ILLUM_DOWNSCALE`, and it is the record of *why*
the cheap estimator is safe — but it is a one-time measurement now living in
every run. Suite went ~35 s → 55 s.

Options: drop the two slow variants and keep the table, or move it behind a
`@LargeTest` / gradle filter. `ShadowedPageRegressionTest` (1.0 s) is the one
that actually guards the fix, so the sweep can go without losing coverage.

### `PapernessDiagnosticTest` aborted the process in teardown
`fixed 2026-09-05`

A σ=241 Gaussian asks OpenCV for a ~2900-tap kernel on a 640×480 image and
aborts natively *after* the test has passed and logged, which reads as a crash.
Variant dropped. Worth remembering as a shape: a native abort in teardown looks
like a failing test but the results are already in logcat.

### A blank page costs two OCR passes
`open` · found 2026-09-05 · `TesseractOcr.recognise`

The rotation retry triggers on low confidence, and a page with no text at all
scores 0.0 — so a blank or unreadable capture is recognised twice before being
reported as unreadable. Correct in general (text can be entirely unrecognised at
0° and fine at 90°) but wasteful in the one case where the first pass found no
words *and* no candidate regions.

Costs a second full pass, a few seconds on the phone. Fix would be to skip the
retry when the first pass returned no blocks at all, not merely no confident
ones. Not worth doing until the phone timings for M5 are known.

---

## Deferred from the pre-device review (2026-09-05)

Seven agents reviewed M3–M7 before the first device test. Everything that could
break or corrupt that test was fixed; these are the P2s that did not justify
holding it up.

### `capturing` releases at the shutter, not at end of processing
`open` · `MainActivity`

The flag clears when the camera callback lands, but dewarp and OCR run for
seconds afterwards. A second capture in that window is safe (the single-thread
executor serialises it, no data race, no double free) but the user hears a
second earcon and "Captured. Reading the page." while the first page is still
being read, then page one loads and is replaced by page two mid-sentence. Fix:
hold `capturing` until `recogniseOffThread` completes.

### `savePosition` writes a file on the main thread, every sentence
`open` · `MainActivity` / `CaptureStore`

Documented as "on every block change"; it actually fires per sentence, for
minutes. The write is dispatched after the TTS call so it is not audible, but
it is more frequent than intended. Fix: debounce to block boundaries, or update
the comment.

### `CaptureStore` writes are not atomic
`open` · `CaptureStore`

`File.writeText` direct, no temp-and-rename, so a kill mid-write can truncate
`ocr.json` or `state.json`. Degrades correctly rather than corrupting — both
readers catch and fall back, and `list()` requires `ocr.json` to exist — so the
worst case is a resumed position resetting to the start of the page.

### `prune()` runs only at startup
`open` · `MainActivity`

A single session capturing more than 20 pages grows unbounded until the next
launch. Same shape as the `SessionRecorder` retention gap above.

### `Sentences` cannot split a run with no whitespace at all
`open` · `Sentences`

The length-based break needs a whitespace character to cut on, so OCR output
that drops every space across a column becomes one unbounded utterance that
cannot be paused part-way. Rare; the sentence-terminator path handles ordinary
text.

### `orderCorners` assumes a convex quad
`open` · `MaskToQuad`

The sum/diff extremes correctly label corners for any convex quadrilateral. A
concave four-point approximation — a finger over a corner, an L-shaped mask —
could produce a self-crossing TL/TR/BR/BL assignment that the area and
edge-length checks would not catch. Theoretical for rectangular paper, and
`MIN_RECTANGULARITY` plus `MIN_REFINE_BOX_COVERAGE` both reduce the odds.
Defence in depth: add a cross-product sign check.

### `ExploreScreen` has no `isTraversalGroup`
`open` · `ExploreScreen`

Unlike `ReadingScreen`. Probably harmless as the region nodes are flat
siblings, but it is unverified whether TalkBack's own touch-exploration
reproduces the "smallest region wins" rule the manual hit-test implements for
the non-TalkBack path. Check on a real page with a caption inside a figure.

### No spoken onboarding for the reading gestures
`open`

The page summary now ends with "Double tap for the next page", but tap,
long-press, swipe-up, the volume keys and shake are undocumented in speech. A
first-run explanation would help; it needs a real user to know what is actually
confusing before writing it.

### `-openmp` Tesseract variant unproven on this hardware
`watch` · first device run

`-openmp` builds have a history of `UnsatisfiedLinkError` on some ARM devices
if `libomp.so` does not resolve. It cannot go silent — `TesseractOcr.create`
degrades to a spoken "I cannot read text on this device" — but watch the
`TesseractOcr` tag on the first run.
