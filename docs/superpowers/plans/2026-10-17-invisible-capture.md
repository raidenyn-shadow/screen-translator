# Invisible Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Screen Translator's ColorOS floating-window frame from the captured screenshot by closing the Sidebar task before the capture runs, with the service owning capture and the Lens handoff.

**Architecture:** The capture-mode root sends a new `ACTION_TRANSLATE` command to `ScreenCaptureService` and immediately removes its own task. The service runs the capture pipeline on a new settled-frame taker (`takeSettled`: drain, then newest frame after a 750 ms settle window), and on success starts the existing `TranslateRelayActivity` exactly as `CaptureActivity` did. `CaptureActivity` and the old `take()` are deleted.

**Tech Stack:** Kotlin, AndroidX/Compose (unchanged), Robolectric 4.14, kotlinx-coroutines-test. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-17-invisible-capture-design.md`

## Global Constraints

- `SETTLE_WINDOW_MS = 750L` (new, in `ProjectionFrameSource.kt`); `FRAME_TIMEOUT_MS = 5_000L` unchanged; service pipeline delay `delayMs = 0`.
- The Lens request and relay contract from `docs/lens-handoff.md` are unchanged: relay intent keeps `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`, fullscreen options, URI extra; Lens request is untouched.
- Robolectric tests use `@Config(sdk = [34])`, existing fakes (`FakeEnvironment`, `ScriptedSessions`), and the real `ProjectionFrameSource` over fakes.
- Gradle commands run with `JAVA_HOME=/home/dev/.jdk/zulu21`.
- Version ends at `versionCode = 20`, `versionName = "0.8.1"`.
- No new dependencies; no manifest permission changes.

## Review Focus

1. **Frame delivered exactly at the settle deadline** — the newest-arrival rule must not lose the candidate or leak a bitmap; `SettledHolder` serializes arm/offer/finish under one lock (Task 1's recycle assertions pin this).
2. **Session stop or projection revocation during the settle window** — capture fails with `IllegalStateException`, candidate recycled, no relay (Task 1 `stopDuringSettle...`; Task 2 relies on existing `ServiceConnectionTest.stopActionCancelsInFlightWorkAndReleasesTheSession`, which still passes with the new taker).
3. **Geometry change mid-settle** — old reader's frames close; the replacement reader's newest frame wins (Task 4 conversion of `resizeReusesDisplayAndClosesOldReader`).
4. **Duplicate `ACTION_TRANSLATE` while one capture is in flight** — pipeline guard rejects; exactly one relay ever starts (Task 2 test).
5. **Icon (non-sidebar) launch superseding a pending sidebar capture** — returns to settings, no command sent (Task 3 keeps `ReusedLaunchTest.iconIntentCancelsAnOlderPendingSidebarCaptureAcrossRecreation` green).

---

### Task 1: Settled-frame capture in ProjectionFrameSource

**Files:**
- Modify: `app/src/main/kotlin/com/raidenyn/screentranslator/capture/ProjectionFrameSource.kt`
- Test: `app/src/test/kotlin/com/raidenyn/screentranslator/capture/ProjectionFrameSourceTest.kt`

**Interfaces:**
- Consumes: existing `PendingCapture`/`arm`/`cancelSlot`/`onFrame` machinery.
- Produces: `suspend fun takeSettled(): Bitmap` on `ProjectionFrameSource`; `internal const val SETTLE_WINDOW_MS: Long = 750`; constructor param `settleWindowMs: Long = SETTLE_WINDOW_MS` inserted after `timeoutMs`. `take()` stays until Task 4 (the class keeps implementing `ScreenshotTaker` until then).

- [ ] **Step 1: Write failing tests**

Add `settleWindowMs` to `newSource` (default `25` for these tests; keep existing `timeoutMs` default `2_000`), then:

```kotlin
@Test fun takeSettledDiscardsQueuedFramesAndReturnsNewestAfterSettleWindow() {
    val source = newSource()
    val reader = environment.readers.single()
    val stale = FakeFrame(0xFF111111.toInt())
    reader.offer(stale)

    val bitmap = runBlocking {
        val taken = async(start = CoroutineStart.UNDISPATCHED) { source.takeSettled() }
        assertEquals(1, stale.closes); assertEquals(0, stale.reads)
        reader.deliver(FakeFrame(0xFF222222.toInt()))
        reader.deliver(FakeFrame(0xFF333333.toInt()))
        reader.deliver(FakeFrame(0xFF444444.toInt()))
        taken.await()   // real time; returns after settleWindowMs
    }
    assertEquals(0xFF444444.toInt(), bitmap.getPixel(0, 0))
    source.close()
}

@Test fun takeSettledKeepsFirstFrameWhenNothingNewerArrives() // one delivery; pixel == that frame's color

@Test fun takeSettledTimesOutWhenNoFrameArrives() // newSource(timeoutMs = 100); runCatching { runBlocking { source.takeSettled() } } → IllegalStateException

@Test fun takeSettledRecyclesReplacedCandidates() // recording bitmapConverter like
    // cancellationWinningDuringCompletionRecyclesBitmap...; deliver three frames;
    // await; assert the returned bitmap is the third and the first two are isRecycled

@Test fun takeSettledCancellationDuringSettleRecyclesCandidatesAndAllowsNextCapture() // start takeSettled,
    // deliver first frame, cancel the async, allow executor to drain; assert every created
    // bitmap isRecycled; then a fresh takeSettled + delivery works

@Test fun stopDuringSettleFailsTheCaptureAndRecyclesTheCandidate() // start takeSettled, deliver first
    // frame, environment.registered!!.onStopped(); await → IllegalStateException,
    // stopSignals == 1, every created bitmap isRecycled
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/home/dev/.jdk/zulu21 ./gradlew :app:testDebugUnitTest --tests com.raidenyn.screentranslator.capture.ProjectionFrameSourceTest`
Expected: FAIL — unresolved `takeSettled`.

- [ ] **Step 3: Implement `takeSettled`**

Two phases; keep the phase-1 slot machinery (`arm` drains, `onFrame` completes `pending`) shared with `take()`:

```kotlin
override suspend fun takeSettled(): Bitmap {
    check(!closed.get()) { "frame source is closed" }
    val first = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val slot = PendingCapture(continuation)
            continuation.invokeOnCancellation { executor.execute { cancelSlot(slot) } }
            executor.execute { arm(slot) }
        }
    } ?: throw IllegalStateException("no fresh frame within $timeoutMs ms")
    return settleNewest(first)
}

/** Replaces [first] with each newer frame until [settleWindowMs] after arming. */
private suspend fun settleNewest(first: Bitmap): Bitmap {
    val holder = SettledHolder(first)
    try {
        withTimeoutOrNull(settleWindowMs) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { executor.execute { holder.disarm() } }
                executor.execute { armSettled(holder) }
            }
        }
    } catch (c: CancellationException) {
        executor.execute { holder.release() }
        throw c
    }
    return holder.finish()
}
```

`SettledHolder` (private inner class): fields `candidate: Bitmap?`, `collecting: Boolean`, `continuation: CancellableContinuation<Bitmap>?`, all mutated under `synchronized(lock)` because the deadline runs off the executor. Methods: `arm` (register continuation, `collecting = true`, drain the reader once — `acquireLatestImage` newest frame newer than `first` converts and replaces the candidate), `offer(bitmap)` (if `!collecting` recycle it, else recycle previous candidate and store; a conversion failure just does not offer), `disarm()`, `finish(): Bitmap` (disarm, return-and-clear candidate), `fail(error)` (disarm, recycle candidate, resume continuation exceptionally), `release()` (disarm, recycle candidate). Wire-ups: `settled: SettledHolder?` field alongside `pending`; `onFrame` checks `pending` first, then `settled` (convert, `settled.offer`, close image); `teardown` and `projectionStopped` also call `settled?.fail(IllegalStateException("projection stopped"))` and null it; `armSettled(holder)` sets `settled = holder` then `holder.arm(...)` (mirror `arm`'s closed/`pending` guards: if closed, `holder.fail`).

- [ ] **Step 4: Run the suite**

Run: same command. Expected: PASS (all new and existing tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/raidenyn/screentranslator/capture/ProjectionFrameSource.kt app/src/test/kotlin/com/raidenyn/screentranslator/capture/ProjectionFrameSourceTest.kt
git commit -m "feat: settle-aware frame capture"
```

---

### Task 2: Service-owned translation command

**Files:**
- Modify: `app/src/main/kotlin/com/raidenyn/screentranslator/service/ScreenCaptureService.kt`
- Test: `app/src/test/kotlin/com/raidenyn/screentranslator/service/ScreenCaptureServiceTest.kt` (reuse `bootSession`/`driveCommand`/`advanceUntilPipelineIdle`/`SolidFrame` from `ScriptedSessions.kt`)

**Interfaces:**
- Consumes: `takeSettled()` from Task 1.
- Produces: `ScreenCaptureService.translate(context: Context)` (companion, sends `startService` with the new action); `internal const val ACTION_TRANSLATE = "com.raidenyn.screentranslator.session.TRANSLATE"`; pipeline built with the settled taker and `delayMs = 0`. The translate observer runs on `sessionScope()` and dies with it on service destruction — no extra end-of-life handling.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun translateCommandCapturesAndStartsTheFullscreenRelay() {
    val boot = bootSession(testScope)
    ScreenCaptureService.translate(context)
    driveCommand(boot.controller, Shadows.shadowOf(app).nextStartedService)

    boot.environment.deliver()
    testScope.advanceUntilPipelineIdle()

    val relay = Shadows.shadowOf(app).nextStartedActivity ?: error("no relay started")
    assertEquals(ComponentName(context, TranslateRelayActivity::class.java), relay.component)
    val file = File(context.cacheDir, "captures").listFiles()!!.single()
    assertEquals(
        Uri.parse("content://com.raidenyn.screentranslator.files/captures/${file.name}"),
        IntentCompat.getParcelableExtra(relay, TranslateRelayActivity.EXTRA_IMAGE_URI, Uri::class.java),
    )
    assertTrue(relay.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    assertTrue(relay.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
    assertEquals(emptyMap<String, CaptureResult>(), boot.service.requests.results.value)
}

@Test fun translateCommandWithoutFrameFailsWithScreenshotToastAndNoRelay() {
    val boot = bootSession(testScope)
    ScreenCaptureService.translate(context)
    driveCommand(boot.controller, Shadows.shadowOf(app).nextStartedService)

    testScope.advanceUntilPipelineIdle()   // 5 s frame timeout, virtual

    assertEquals(context.getString(R.string.error_screenshot_failed), ShadowToast.getTextOfLatestToast())
    assertNull(Shadows.shadowOf(app).peekNextStartedActivity())
    assertEquals(emptyMap<String, CaptureResult>(), boot.service.requests.results.value)
}

@Test fun translateWithoutAReadySessionIsANoop() {
    val controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
    ScreenCaptureService.translate(context)
    controller.withIntent(Shadows.shadowOf(app).nextStartedService).startCommand(0, 0)
    assertNull(Shadows.shadowOf(app).peekNextStartedActivity())
}

@Test fun secondTranslateWhileOneIsInFlightStartsOnlyOneRelay() {
    val boot = bootSession(testScope)
    ScreenCaptureService.translate(context); driveCommand(boot.controller, Shadows.shadowOf(app).nextStartedService)
    ScreenCaptureService.translate(context); driveCommand(boot.controller, Shadows.shadowOf(app).nextStartedService)

    boot.environment.deliver()
    testScope.advanceUntilPipelineIdle()

    assertNotNull(Shadows.shadowOf(app).nextStartedActivity)
    assertNull(Shadows.shadowOf(app).peekNextStartedActivity())
}
```

Add imports (`ComponentName`, `Intent`, `Uri`, `IntentCompat`, `File`, `ShadowToast`, `CaptureResult`, `TranslateRelayActivity`, `assertNotNull`). Add `@After` cleanup of `booted`/`testScope.cancel()` if not already present in this class (it cleans `controller` only — extend the existing `clean`).

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/home/dev/.jdk/zulu21 ./gradlew :app:testDebugUnitTest --tests com.raidenyn.screentranslator.service.ScreenCaptureServiceTest`
Expected: FAIL — unresolved `translate`/`ACTION_TRANSLATE`.

- [ ] **Step 3: Implement the command**

In `ScreenCaptureService`:
- Companion: `internal const val ACTION_TRANSLATE = "com.raidenyn.screentranslator.session.TRANSLATE"` and `fun translate(context: Context) { context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_TRANSLATE)) }` (plain `startService`, mirroring `stop`).
- `handleCommand`: add `ACTION_TRANSLATE -> translateCommand()` branch before the `else`.
- `publishIfLive`: `pipeline = CaptureRequests(ScreenshotTaker { started.takeSettled() }, CaptureStore.from(this), sessionScope(), delayMs = 0)`.
- `translateCommand()` per the spec: `pipeline ?: return`; `UUID.randomUUID().toString()` id; `if (!pipeline.request(id)) return`; `sessionScope().launch { val result = pipeline.results.first { it.containsKey(id) }[id]!!; pipeline.acknowledge(id); when (result) { is CaptureResult.Saved -> startTranslationRelay(result.uri); is CaptureResult.Failed -> Toast.makeText(this, result.error.labelRes, Toast.LENGTH_SHORT).show() } }`.
- `startTranslationRelay(uri)`: `TranslateRelayActivity.intent(this, uri).addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)`; `try { startActivity(relay, fullscreenOptions()) } catch (_: Exception) { Toast.makeText(this, R.string.error_launch_failed, Toast.LENGTH_SHORT).show() }`. Import `fullscreenOptions` from `com.raidenyn.screentranslator.lens`.

- [ ] **Step 4: Run the suite**

Run: `JAVA_HOME=/home/dev/.jdk/zulu21 ./gradlew :app:testDebugUnitTest --tests com.raidenyn.screentranslator.service.*`
Expected: PASS — new tests plus existing `ServiceConnectionTest` (its `parkCapture` still parks: the `delayMs = 0` delay completes inside `advanceTimeBy(CAPTURE_DELAY_MS)`; no frame arrives, so phase 1 parks; `CaptureActivityTest` also stays green because `completePendingCapture`'s `advanceUntilPipelineIdle` covers the virtual settle window).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/raidenyn/screentranslator/service/ScreenCaptureService.kt app/src/test/kotlin/com/raidenyn/screentranslator/service/ScreenCaptureServiceTest.kt
git commit -m "feat: service-owned translation command"
```

---

### Task 3: Capture-mode root submits and closes the task

**Files:**
- Modify: `app/src/main/kotlin/com/raidenyn/screentranslator/ui/SetupActivity.kt`
- Test: `app/src/test/kotlin/com/raidenyn/screentranslator/ui/TrampolineActivityTest.kt`, `app/src/test/kotlin/com/raidenyn/screentranslator/ui/ReusedLaunchTest.kt`

**Interfaces:**
- Consumes: `ScreenCaptureService.translate(context)` and `ACTION_TRANSLATE` from Task 2.
- Produces: none externally. Removes `SetupActivity.EXTRA_REQUEST_ID`/`STATE_REQUEST_ID`/`childLaunched` and the `capture` result launcher; adds `STATE_SUBMITTED`.

- [ ] **Step 1: Rewrite the child-launch tests (RED)**

In `TrampolineActivityTest`, replace `sidebarTapWithLiveSessionOpensCaptureWithoutConsent` with:

```kotlin
@Test fun sidebarTapWithLiveSessionSubmitsTranslationAndClosesItsTask() {
    boot = bootSession(testScope)
    val root = Robolectric.buildActivity(TrampolineActivity::class.java, sidebarIntent()).setup()
        .also { activities.add(it) }
    val task = appTask(root.get().taskId)   // helper copied from CaptureActivityTest:
        // ShadowAppTask.newInstance() + Shadows.shadowOf(it).setTaskInfo(RecentTaskInfo(){ taskId })
    Shadows.shadowOf(root.get().getSystemService(ActivityManager::class.java)).setAppTasks(listOf(task))
    ShadowLooper.idleMainLooper()

    val captureRoot = root.get()
    assertTrue(isTranslucent(captureRoot))
    assertEquals(ScreenCaptureService.ACTION_TRANSLATE, Shadows.shadowOf(app).nextStartedService?.action)
    assertTrue(Shadow.extract<ShadowAppTask>(task).isFinishedAndRemoved)
    assertNull(shadow(captureRoot).peekNextStartedActivityForResult())
}
```

In `ReusedLaunchTest.sidebarIntentToExistingSettingsWaitsForProjectionReadiness`, replace the child assertions (old lines 112–114) with: capture root is translucent and has zero content children; `Shadows.shadowOf(app).nextStartedService?.action == ScreenCaptureService.ACTION_TRANSLATE`; `peekNextStartedActivityForResult()` null. Leave `iconIntentCancelsAnOlderPendingSidebarCaptureAcrossRecreation` untouched.

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/home/dev/.jdk/zulu21 ./gradlew :app:testDebugUnitTest --tests com.raidenyn.screentranslator.ui.TrampolineActivityTest --tests com.raidenyn.screentranslator.ui.ReusedLaunchTest`
Expected: FAIL — no `ACTION_TRANSLATE` service intent is sent (production still launches `CaptureActivity`).

- [ ] **Step 3: Implement `submitTranslation` in `SetupActivity`**

- Remove: the `capture` `registerForActivityResult` block, `childLaunched`, `STATE_CHILD_LAUNCHED`, `requestId`, `STATE_REQUEST_ID`, `EXTRA_REQUEST_ID`, the `UUID` import, `CaptureActivity.cancelActiveCapture()` call in `onNewIntent`, and `launchCapture()`.
- Add `private var submitted = false` with `STATE_SUBMITTED = "submitted_state"` (persisted in `onSaveInstanceState`, restored in `onCreate`).
- `onResume`: `captureMode -> submitTranslation()` replaces `captureMode && !childLaunched -> launchCapture()`.
- `beginCapture()`: drop the `requestId` line; keep the connection check, `captureMode = true`, `intent.putExtra(EXTRA_CAPTURE_MODE, true)`, `recreate()`.
- `returnToSettings()`: also reset `submitted = false` and drop the `EXTRA_REQUEST_ID` removal.
- New methods:

```kotlin
private fun submitTranslation() {
    if (!submitted) {
        if (ScreenCaptureService.connection.value == null) { returnToSettings(); return }
        submitted = true
        try { ScreenCaptureService.translate(this) }
        catch (_: Throwable) { failToSettings(getString(R.string.error_session_failed)); return }
    }
    removeOwnTask()
}

private fun removeOwnTask() {
    try {
        getSystemService(ActivityManager::class.java).appTasks
            .firstOrNull { it.taskInfo.taskId == taskId }?.finishAndRemoveTask()
    } catch (_: IllegalArgumentException) {
    } catch (_: SecurityException) {
    }
}
```

- [ ] **Step 4: Run the suite**

Run: `JAVA_HOME=/home/dev/.jdk/zulu21 ./gradlew :app:testDebugUnitTest`
Expected: PASS — all classes, including `SetupActivityTest` (consent paths unchanged) and `CaptureActivityTest` (still builds the activity directly).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/raidenyn/screentranslator/ui/SetupActivity.kt app/src/test/kotlin/com/raidenyn/screentranslator/ui/TrampolineActivityTest.kt app/src/test/kotlin/com/raidenyn/screentranslator/ui/ReusedLaunchTest.kt
git commit -m "feat: submit translation from the closing capture root"
```

---

### Task 4: Remove CaptureActivity and the old take()

**Files:**
- Delete: `app/src/main/kotlin/com/raidenyn/screentranslator/ui/CaptureActivity.kt`, `app/src/test/kotlin/com/raidenyn/screentranslator/ui/CaptureActivityTest.kt`
- Modify: `app/src/main/AndroidManifest.xml` (remove the `CaptureActivity` entry), `app/src/main/kotlin/com/raidenyn/screentranslator/capture/ProjectionFrameSource.kt`
- Test: `app/src/test/kotlin/com/raidenyn/screentranslator/capture/ProjectionFrameSourceTest.kt`

**Interfaces:**
- Consumes: Task 1's `takeSettled` (phase-1 machinery stays: `arm`, `cancelSlot`, `PendingCapture`).
- Produces: `ProjectionFrameSource` no longer implements `ScreenshotTaker`; `take()` is gone. `CaptureActivity` is gone.

- [ ] **Step 1: Convert the remaining `take()` tests**

In `ProjectionFrameSourceTest`, replace every `source.take()` with `source.takeSettled()` (tests construct sources via `newSource`, whose default `settleWindowMs` from Task 1 is small). Where a test awaits completion right after a delivery, the await itself spans the settle window (real time in `runBlocking`), so keep the same assertions; conversion list: `outerTimeoutCancelsTakeInsteadOfFailingIt`, `lateFrameAfterTimeoutDoesNotCompleteNextRequest`, `failingFrameFailsOnlyItsRequestAndClosesOnce`, `resizeReusesDisplayAndClosesOldReader`, `geometryEventBeforeStartSizesOnlyTheStartupReader`, `startupFailureClosesItsPartiallyCreatedReader`, `unexpectedStopFailsPendingCaptureAndSignalsServiceOnce`, `cancelledCaptureReleasesItsPendingSlot`, `cancellationWinningDuringCompletionRecyclesBitmapAndLeavesNextRequestUsable`. Delete tests whose behavior moved to Task 1's `takeSettled` suite: `takeDiscardsQueuedFramesAndWaitsForFreshImage`, `missingFrameTimesOutAfter5000ms` (the constant assertion moves into Task 1's timeout test). Rename converted tests from `take...` to `takeSettled...` describing the same behavior.

- [ ] **Step 2: Run the converted tests**

Run: `JAVA_HOME=/home/dev/.jdk/zulu21 ./gradlew :app:testDebugUnitTest --tests com.raidenyn.screentranslator.capture.ProjectionFrameSourceTest`
Expected: PASS — `takeSettled` exists since Task 1; this step proves the conversion preserved each behavior before the deletion below.

- [ ] **Step 3: Delete the old path**

- In `ProjectionFrameSource.kt`: delete `override suspend fun take()` and the `ScreenshotTaker` supertype (keep `AutoCloseable`); `awaitFreshFrame` logic remains inside `takeSettled` as implemented in Task 1.
- Delete `CaptureActivity.kt` and `CaptureActivityTest.kt`; remove the `CaptureActivity` entry from `app/src/main/AndroidManifest.xml`.
- `grep -rn "CaptureActivity\|EXTRA_REQUEST_ID\|cancelActiveCapture" app/src` must return nothing.

- [ ] **Step 4: Run the full suite**

Run: `JAVA_HOME=/home/dev/.jdk/zulu21 ./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main app/src/test
git commit -m "refactor: remove the visible capture activity"
```

---

### Task 5: Documentation, version 0.8.1, verification, and release

**Files:**
- Modify: `README.md`, `docs/lens-handoff.md`, `docs/mediaprojection-device-checks.md`, `docs/2026-10-16-lens-floating-window-findings.md`, `app/build.gradle.kts`

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Update documentation**

- `README.md` — Launch behavior: a Sidebar tap now closes the app's window first, captures the settled screen, and opens Lens; note the frame can flash briefly while the task exists but is no longer captured; mention `ACTION_TRANSLATE` service ownership.
- `docs/lens-handoff.md` — Task and grant ownership: root submits `ACTION_TRANSLATE` and removes its task; service captures with the settled taker and starts the relay; relay/Lens request unchanged.
- `docs/mediaprojection-device-checks.md` — replace the presentation check wording: "Verify the saved PNG contains no Screen Translator window or ColorOS floating frame and Lens opens fullscreen"; note v0.8.1 flow pending device validation.
- `docs/2026-10-16-lens-floating-window-findings.md` — add a "v0.8.1 change" section: task closes before capture; relay start now relies on the uid's background-activity-launch grace period (~10 s AOSP) rather than a visible window; device log check listed.
- `app/build.gradle.kts` — `versionCode = 20`, `versionName = "0.8.1"`.

- [ ] **Step 2: Verify everything**

Run: `JAVA_HOME=/home/dev/.jdk/zulu21 ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleRelease :app:lintRelease && git diff --check`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Sign and verify the release APK**

```bash
"$ANDROID_SDK_ROOT/build-tools/36.0.0/zipalign" -P 16 -f 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/opencode/release/screen-translator-0.8.1-aligned.apk
PATH="/home/dev/.jdk/zulu21/bin:$PATH" "$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" sign --ks /home/dev/.android/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out /tmp/opencode/release/screen-translator-0.8.1.apk /tmp/opencode/release/screen-translator-0.8.1-aligned.apk
PATH="/home/dev/.jdk/zulu21/bin:$PATH" "$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" verify --verbose --print-certs /tmp/opencode/release/screen-translator-0.8.1.apk
PATH="/home/dev/.jdk/zulu21/bin:$PATH" "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/apkanalyzer" manifest debuggable /tmp/opencode/release/screen-translator-0.8.1.apk   # false
sha256sum /tmp/opencode/release/screen-translator-0.8.1.apk
```

Expected: verifies with the same `31bb5a59…` certificate as prior releases; version 20 / `0.8.1` (`apkanalyzer apk summary`); debuggable false.

- [ ] **Step 4: Commit, push, and publish**

```bash
git add README.md docs app/build.gradle.kts
git commit -m "chore: release 0.8.1 invisible capture"
git push origin main
```

Write release notes to `/tmp/opencode/release/v0.8.1-notes.md` (changes: frame-free screenshot, service-owned capture; validation: unit/release tests/lint passed, device checks pending — saved PNG must be frame-free, Lens fullscreen translate, and watch for a background-launch denial in the log), plus `/tmp/opencode/release/screen-translator-0.8.1.apk.sha256`, then:

```bash
gh release create v0.8.1 /tmp/opencode/release/screen-translator-0.8.1.apk /tmp/opencode/release/screen-translator-0.8.1.apk.sha256 --title "v0.8.1 - Frame-free capture" --notes-file /tmp/opencode/release/v0.8.1-notes.md --latest
```

Expected: release URL; `git status` clean afterwards.