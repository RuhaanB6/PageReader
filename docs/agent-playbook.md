# Working on PageReader with Claude Code

Written 2026-09-05, after a session that fixed six real bugs and burned most of
its time on one that was never in our code. Everything here is a lesson that
cost something.

`CLAUDE.md` holds the architecture and the hard rules. This is about *how to
work* — the loop, the traps, and what to delegate.

---

## 1. The device loop is the job

This app cannot be evaluated without hardware. `abiFilters` excludes x86, so
there is no emulator, and the failures that matter — a page half out of frame, a
shadow across the sheet, a cue firing late — do not reproduce on synthetic
input. Plan on the phone being plugged in for any detector or guidance work.

The loop that works:

```
watch telemetry  ->  notice something odd  ->  diagnose  ->  patch  ->  install  ->  watch again
```

Four of the six fixes in that session started as *something looked wrong in a
feed I was already reading*, not as a reported bug. Keep the feed running.

**Watch it live** while someone uses the phone:

```bash
tools/watch-session.py          # spoken cues, captures, gate rejections, heartbeat
```

**Summarise afterwards**, from a pulled session:

```bash
tools/pull-session.sh && tools/analyse-session.py <session-dir>
```

### Iterate with `am instrument`, not Gradle

Once both APKs are installed, run instrumented tests directly:

```bash
adb shell am instrument -w -e class 'com.pagereader.android.detect.QuadFitDiagnosticTest' \
  com.pagereader.android.test/androidx.test.runner.AndroidJUnitRunner
```

That starts in about a second. `./gradlew connectedAndroidTest` rebuilds,
repackages and reinstalls two APKs first — about 35 seconds before a single test
runs. When bisecting, that difference is the difference between ten experiments
and two. Use Gradle when the code changed; use `am instrument` when it did not.

### Installs need a human

This device shows a confirmation dialog on every `adb install`. An unanswered
dialog fails with an empty error message, and has twice looked like a broken
build. If an install hangs, ask the person to look at the phone.

---

## 2. The PowerGenie trap — read before debugging any hang

**A hanging instrumented test on this phone is almost certainly not your code.**

HarmonyOS freezes processes with no foreground window a few seconds after they
start, and an instrumentation run never has one. The frozen process sits at 0%
CPU with every thread in `D` state and stops answering binder, so
`dumpsys meminfo <pid>` returns nothing. It is indistinguishable from a native
deadlock by inspection. AGP passes `testTimeoutSeconds 31536000` — one year — so
the run never fails. It waits.

Check logcat first:

```
Pged-Freezer: Freeze process: <pid>
HiberManagerService::DoReclaim ok, pid=<pid>, reclaimMode=hiber_anon
```

That `DoReclaim` hibernates the process's memory and drives `VmSwap` past 150 MB,
which looks exactly like a runaway native leak. It is not one. `dumpsys
deviceidle whitelist` does **not** help — PowerGenie ignores the AOSP list.

Fix on the phone: **Settings → Battery → App launch → PageReader → Manage
manually**, with all three toggles enabled.

Cost of not knowing this: several hours, a leak hypothesis that was wrong, and a
subagent audit to disprove it.

---

## 3. Diagnose before fixing

The most expensive mistakes were confident fixes for misdiagnosed problems.

A worked example. Quads were coming back with one corner mid-page, cutting off a
third of the sheet. Two causes look identical from outside: the colour mask is
cut, or the mask is fine and corner fitting collapsed a vertex. They want
completely different fixes.

The wrong move is to pick one. The right move was `QuadFitDiagnosticTest` — run
the production path over saved frames, dump the mask, the contour and the quad,
and look. The mask had a bite out of it. Thirty minutes of instrumentation
replaced a guess.

Two habits worth keeping:

- **Prefer a measurement over an argument.** "0% CPU across two samples" ended a
  debate about whether the phone was slow. `top -n 1` reports 0.0 on its first
  sample — take two.
- **Save the artefact.** Frames that reproduce a failure belong in
  `app/src/androidTest/assets/frames/`. They are 39 KB and they are the only
  reproduction that does not need the same carpet, sheet and lighting.

---

## 4. Constants are measurements, not preferences

Every detector constant carries a comment saying what was measured and when.
Keep that up. When one changes, the comment changes with it, including the
number that justified the new value.

Two that exist because of a wrong first guess:

- `MIN_REFINE_BOX_COVERAGE = 0.80` — bad quads measured 0.68 and 0.71.
- `BORDER_TOUCH_FRACTION = 0.06` — 3 px was 0.5% of the frame and never fired.

And one warning worth repeating: a symptom that differs between axes is
geometry, not tolerance. Left/right needing 40% overhang while top/bottom were
correct was `FILL_CENTER` cropping exactly 20% per side. Widening a threshold
would have masked it.

---

## 5. What to delegate

Subagents are for **bounded, read-heavy, parallel** work that returns a
conclusion. They earned their place once in that session: a Mat-lifecycle audit
across four files that disproved the leak hypothesis with arithmetic, while the
device work continued. None of those file reads entered the main context.

Good subagent tasks:
- "Audit these files for X; report file:line, confirmed or not, and the fix."
- "Find every caller of Y and say whether each releases the returned Mat."
- Researching an approach before committing to it.

Bad subagent tasks:
- The device loop. One phone, and the value is in tight iteration by whoever is
  watching the feed.
- Anything where a cold reader has to re-derive the context you already hold.

Give them the constraint that matters ("CLAUDE.md says per-frame Mat leaks are
fatal") and ask them to confirm *or refute* a stated hypothesis. The audit that
refuted mine was more useful than one that agreed.

---

## 6. Verification

- JVM tests for logic: `./gradlew test`. `GuidancePolicyTest` is the model —
  synthetic observations, no device.
- Instrumented for anything touching OpenCV natives, CameraX or TTS.
- **A regression test for a gating fix must also assert the happy path still
  works.** The failure mode of "stop capturing when clipped" is "never capture."
- Report what actually happened. If a test was skipped because the device
  dropped, say so.

---

## 7. Storage on the phone

Settled with the user: keep the captured page and its reading position; do not
accumulate the framing phase. `SessionRecorder` stays behind `FLAG_DEBUGGABLE`
and must never become user-facing. It needs a retention cap next time it is
touched — six short sessions reached 6.4 MB and nothing deletes them. Anything
new that records the framing phase needs a reason and defaults to off.

---

## 8. Which model

Use the strongest available model for detector, guidance and pipeline work.
Every expensive mistake in that session was a *reasoning* failure — misreading
`D` state as a deadlock, assuming a leak, mistaking a preview crop for a
detector bug — not a coding failure. The code changes were mostly a few lines
each; finding the right few lines was the hard part.

Delegate the mechanical parts to a cheaper model via subagents (audits, sweeps,
fixture generation) and keep the diagnosis in the main session.
