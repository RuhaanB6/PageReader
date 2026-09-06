# UI integration handoff (Part 2)

Everything below lives under `app/src/main/java/com/pagereader/android/ui/`
plus the moved theme files. Nothing here touches `MainActivity.kt`,
`audio/TtsManager.kt`, `reading/PagePlayer.kt`, `reading/Speaker.kt`, or
`docs/known-issues.md` -- those are the orchestrator's / Part 1's.

`./gradlew assembleDebug` and `./gradlew test` both fail right now, **only**
because `MainActivity.kt` still references the deleted `ReadingScreen` /
`ExploreScreen` composables (see "Build status" below). Every other file
compiles clean.

## New files

- `ui/theme/Color.kt`, `Theme.kt`, `Type.kt` -- rewritten in place (not new
  paths, but effectively new content). Purple80/Pink40 template constants are
  gone; `dynamicColor` is gone entirely (the app is single-scheme, always
  dark). `PageReaderTheme` is now called with **no parameters**:
  `PageReaderTheme { content }`.
- `ui/QuadOverlay.kt` -- `FrameTransform` (unchanged math, now public) +
  `QuadOverlay` (replaces `MainActivity.PageOverlay`) + `ClipBars` (the
  per-`Side` red bars, split into their own composable).
- `ui/PageProjection.kt` -- `ViewRect`, `toView`, `fromView`, `hitTest`,
  `colourFor`, lifted from `ExploreScreen` and extended with `zoom`/`pan`
  parameters (default `1f`/`Offset.Zero`, so existing call shapes still work
  at 1x).
- `ui/CaptureScreen.kt` -- FRAMING screen.
- `ui/ProcessingScreen.kt` -- PROCESSING screen. Also defines
  `enum class CaptureStage { CAPTURING, FLATTENING, READING_TEXT, FINISHING }`
  -- import this rather than redefining it.
- `ui/PageScreen.kt` -- READING screen, replaces `ReadingScreen` +
  `ExploreScreen`. Contains `BlockRow` (moved from `ReadingScreen` intact).
- `app/src/test/java/com/pagereader/android/ui/PageProjectionTest.kt` -- new
  host test: `toView`/`fromView` round-trip a bbox at 1x and 3x+pan;
  `hitTest` picks the smallest containing block at 1x and 3x zoom.

## Deleted

- `reading/ExploreScreen.kt`
- `reading/ReadingScreen.kt`

Neither had a dedicated test file (checked `app/src/test` and
`app/src/androidTest` -- no hits), so nothing else needed deleting.

## Dependencies added

`gradle/libs.versions.toml`: `androidx-compose-animation`,
`androidx-compose-foundation` library aliases (versions come from the
existing Compose BOM, no version string needed).

`app/build.gradle.kts`:
- `implementation(libs.androidx.compose.animation)`
- `implementation(libs.androidx.compose.foundation)`
- `buildFeatures { buildConfig = true }` -- **new**. `CaptureScreen` gates its
  debug line on `BuildConfig.DEBUG`; AGP 8+ does not generate that class
  unless this flag is set. Nothing else in the app used `BuildConfig` before
  (`SessionRecorder` deliberately reads installed `ApplicationInfo` instead,
  per its own comment), so this is a net-new build feature, not a revert of
  someone else's opt-out.

## What `MainActivity` must do

### 1. `Mode` enum
Currently: `private enum class Mode { FRAMING, READING, EXPLORING }`
(`MainActivity.kt:671`).

Needs to become: `enum class Mode { FRAMING, PROCESSING, READING }`.
`EXPLORING` is gone -- `PageScreen`'s photo-tap IS touch-explore now.

### 2. New state fields (plan section 2.1)
- `captureStage: MutableState<CaptureStage>` (import
  `com.pagereader.android.ui.CaptureStage`), initialized `CAPTURING`. Set at
  `takeStill` entry, `dewarpOffThread` entry (`FLATTENING`),
  `recogniseOffThread` entry (`READING_TEXT`), and just before the
  `runOnUiThread` block that flips to `Mode.READING` (`FINISHING`).
- `ocrPercent: MutableIntState`, fed from the existing Tesseract progress
  lambda at `MainActivity.kt:492-498` (the one that currently only drives
  spoken "N percent"). Set it there in addition to speaking; do not add a
  second progress path.
- `pagePreview: MutableState<ImageBitmap?>`, set from the dewarped `Mat` once
  it exists -- convert with `org.opencv.android.Utils.matToBitmap` after an
  `INTER_AREA` downscale to a ~1400px long side (see plan section 2.4 for the
  memory-size rationale: a 3000px page as ARGB is ~24MB, 1400px is ~5MB).
  This is the same `ImageBitmap` `PageScreen.pageImage` takes.

### 3. Screen parameter lists

**`CaptureScreen`** (`ui/CaptureScreen.kt`):
```kotlin
CaptureScreen(
    observation: PageObservation?,      // latestObservation.value
    instruction: Instruction?,          // decision.instruction, kept in a state field
    state: FramingState,                // decision.state, kept in a state field
    debugLine: String,                  // debugLine.value, unchanged
    onShutter: () -> Unit,              // { takeStill(auto = false) }
    onPreviewView: (PreviewView) -> Unit, // cameraManager::bindCamera
    modifier: Modifier = Modifier,
)
```
Owns the double-tap-anywhere shutter gesture internally -- delete
`MainActivity`'s root `.then(if (mode.value == Mode.FRAMING) ...)` block
(`MainActivity.kt:175-185`).

You will need a new state field for `instruction`/`state` (or just recompute
from a stored `Decision` each frame) since `handleFrame` currently only
stores `latestObservation` and `debugLine`; the card needs
`decision.instruction` and `decision.state` live, not just baked into the
debug string.

**`ProcessingScreen`** (`ui/ProcessingScreen.kt`):
```kotlin
ProcessingScreen(
    stage: CaptureStage,
    percent: Int,
    pagePreview: ImageBitmap?,
    modifier: Modifier = Modifier,
)
```
No camera-related parameters at all -- verified by inspection that it never
touches `PreviewView`, `CameraManager`, or `AndroidView`.

**`PageScreen`** (`ui/PageScreen.kt`), replaces both the `ReadingScreen` and
`ExploreScreen` call sites:
```kotlin
PageScreen(
    page: OcrPage,                      // readingPage.value!!
    pageImage: ImageBitmap?,             // pagePreview.value, or convert lastPage.page freshly
    currentBlockId: Int?,                // currentBlockId.value
    isPlaying: Boolean,                  // player.isPlaying
    progressFraction: Float,             // player.position.blockIndex / player.blockCount.toFloat()
    talkBackEnabled: Boolean,            // isTalkBackEnabled()
    onTogglePlay: () -> Unit,            // { player.toggle() }
    onPreviousBlock: () -> Unit,         // { player.previousBlock() }
    onNextBlock: () -> Unit,             // { player.nextBlock() }
    onReadFrom: (TextBlock) -> Unit,     // { block -> player.jumpToBlockId(block.id); player.play() }
    onNewPage: () -> Unit,               // { returnToCamera() }
    modifier: Modifier = Modifier,
)
```
Note there is no `onRepeatBlock` parameter -- the old long-press-anywhere
"repeat block" gesture has no equivalent here; `previousBlock()` already
restarts the current block when not at sentence 0 (see `PagePlayer.kt:167`),
and the new bottom control bar's "Previous" button covers the same need with
a visible target. If you want long-press-repeat preserved as a gesture too,
that would need to be added inside `PageScreen`, not wired from outside.

The old vertical-swipe-into-EXPLORING gesture at `MainActivity.kt:193-209`
should simply be deleted, not moved -- there is no EXPLORING mode to swipe
into anymore.

### 4. `when(mode.value)` block
Becomes:
```kotlin
when (mode.value) {
    Mode.FRAMING -> if (hasCameraPermission.value) CaptureScreen(...)
    Mode.PROCESSING -> ProcessingScreen(...)
    Mode.READING -> readingPage.value?.let { page -> PageScreen(page = page, ...) }
}
```
Set `mode.value = Mode.PROCESSING` in `takeStill` (`MainActivity.kt:399-401`
area) alongside `capturing = true`. On the failure/retake paths inside
`recogniseOffThread` (the `ocrUnavailable` early return, the `engine == null`
branch, the `result == null` branch, and the `!verdict.usable` branch) set
`mode.value = Mode.FRAMING` before returning, since those currently leave
`mode` wherever it was (which used to be fine because FRAMING was never left
until success, but now `takeStill` leaves it early).

### 5. Superseded lines in `MainActivity.kt`
- `PageOverlay` (private composable, `MainActivity.kt:768-802`) -- delete,
  replaced by `ui.QuadOverlay` + `ui.ClipBars`.
- `FrameTransform` (private class, `MainActivity.kt:755-762`) -- delete,
  replaced by the public `ui.FrameTransform` (identical math, doc comment
  carried over verbatim).
- The root `Box`'s two `.then(if (mode.value == ...))` gesture modifiers
  (`MainActivity.kt:175-209`) -- delete both; framing's double-tap moved
  into `CaptureScreen`, and the EXPLORING swipe has nothing to enter anymore.
- The `Text(debugLine.value, ...)` at the bottom of `setContent`
  (`MainActivity.kt:262-272`) -- delete; `CaptureScreen` draws its own
  `BuildConfig.DEBUG`-gated debug line, and `ProcessingScreen`/`PageScreen`
  don't need one.
- `import com.pagereader.android.reading.ExploreScreen` and
  `import com.pagereader.android.reading.ReadingScreen` -- delete; replace
  with `import com.pagereader.android.ui.*` (or explicit imports of
  `CaptureScreen`, `ProcessingScreen`, `PageScreen`, `CaptureStage`).

## Design decisions (palette)

- **Ink** (near-black, `#0B0B0E` background / `#17171B` surface) for every
  screen's ground. No dynamic color, no light theme -- there is no state in
  this app where a bright chrome is correct.
- **Ember** (`#FF6B35`), one warm accent, reserved for the shutter button and
  `FramingState.FRAMED`/active-block emphasis. Used sparingly on purpose.
- **GuidanceAmber** (`#FFC24B`) / **GuidanceGreen** (`#5BD98A`) for
  `SEARCHING`/`ADJUSTING` and `STEADY` respectively, so `QuadOverlay`,
  `CaptureScreen`'s status chip, and the shutter button all agree on what a
  given `FramingState` looks like.
- **Paper** (`#F6F1E7`), used only behind the page projection in
  `PageScreen`/`ProcessingScreen` -- deliberately off-white, since the
  content behind it is a photograph of paper, not a rendered document.
- **Block colours unchanged**: `BlockHeading`/`BlockBody`/etc. in `Color.kt`
  are the exact hex values `ExploreScreen.colourFor` used, per the plan's
  explicit instruction not to touch a mapping that is already WCAG-AA and
  already paired with the spoken labels. Only the *drawing* changed
  (translucent rounded fills instead of a 3dp stroke).

## Build status (verification evidence)

```
$ export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
$ ./gradlew assembleDebug --console=plain 2>&1 | grep -E 'error:|FAILED|BUILD|e: '
> Task :app:compileDebugKotlin FAILED
e: .../MainActivity.kt:47:39 Unresolved reference 'ExploreScreen'.
e: .../MainActivity.kt:49:39 Unresolved reference 'ReadingScreen'.
e: .../MainActivity.kt:291:29 Unresolved reference 'ReadingScreen'.
e: .../MainActivity.kt:297:48 Cannot infer type for this parameter. Specify it explicitly.
e: .../MainActivity.kt:298:64 Unresolved reference 'id'.
e: .../MainActivity.kt:306:29 Unresolved reference 'ExploreScreen'.
e: .../MainActivity.kt:309:53 Cannot infer type for this parameter. Specify it explicitly.
e: .../MainActivity.kt:320:50 Cannot infer type for this parameter. Specify it explicitly.
e: .../MainActivity.kt:321:64 Unresolved reference 'id'.
BUILD FAILED in 2s
```
`./gradlew test` fails identically (same `compileDebugKotlin` dependency).
Every error is in `MainActivity.kt`; verified by inspection that lines 297,
298, 309, 320, 321 are all inside the now-dead `Mode.READING`/`Mode.EXPLORING`
branches that call the deleted composables -- the "cannot infer type" /
"unresolved id" errors are cascades from the unresolved composable calls, not
independent problems.

Once `MainActivity` adopts the new screens per this doc, both commands should
go green; I could not verify that directly since editing `MainActivity.kt` is
out of scope for this pass. As an independent sanity check (since the broken
main sourceSet blocks running `PageProjectionTest` through Gradle), I hand-
verified the `toView`/`fromView` round-trip arithmetic against the exact test
inputs in a standalone script and confirmed exact-pixel round-tripping at 3x
zoom with a nonzero pan offset.
