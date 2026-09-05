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
`open` · found 2026-09-05 · `MainActivity` / capture latch

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
