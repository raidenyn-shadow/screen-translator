# MediaProjection Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Accessibility capture with a consented foreground MediaProjection session and preserve Sidebar-to-Lens screenshot sharing.

**Architecture:** A projection-frame source owns one VirtualDisplay and ImageReader per session. A request coordinator captures and saves one fresh frame at a time, while a normal foreground service owns their lifetime. Activities own consent and Lens launches; no service inspects or controls Lens UI.

**Tech Stack:** Kotlin, Android MediaProjection/ImageReader/VirtualDisplay, AndroidX Activity Result and ServiceCompat, Compose, coroutines/StateFlow, Robolectric/JUnit.

**Spec:** `docs/superpowers/specs/2026-10-16-mediaprojection-capture-design.md`

## Global Constraints

- Keep minSdk 30 and compile/target SDK 36.
- Remove all Accessibility service registration, screenshot calls, and Lens control automation.
- Preserve ColorOS `from_package=com.coloros.smartsidebar` routing and singleTask/onNewIntent behavior.
- One MediaProjection and one VirtualDisplay per consent token; use resize/setSurface for geometry changes.
- Consent comes from a visible Activity before foreground service startup; foreground promotion precedes getMediaProjection, and callback registration precedes display creation.
- Use START_NOT_STICKY; no saved consent token, automatic restart, reboot receiver, or Accessibility fallback.
- Preserve private CaptureStore/FileProvider sharing; Lens Translate selection is manual.
- Keep the existing 400 ms UI-dismissal delay; choose a 5,000 ms frame timeout and ImageReader maxImages=2.
- Do not add dependencies unless the existing AndroidX/coroutines stack cannot implement a required behavior.
- Commits require user authorization. Suggested commit boundaries below do not authorize committing this new refactor.

## Review Focus

1. Consent delivered after an icon intent supersedes a Sidebar request must not capture or launch Lens (Task 3).
2. Reader callbacks racing resize/shutdown must close obsolete frames without completing the wrong request (Task 1).
3. Timeout followed by a late frame must not satisfy the next capture with stale work (Tasks 1–2).
4. Activity recreation or pause between saving and handoff must not lose or double-launch a screenshot (Tasks 2–3).
5. Foreground promotion or projection initialization throwing must leave no ready singleton, leaked reader, or hung pending Activity (Task 3).

## File Structure and Interfaces

All Kotlin paths below are relative to `app/src/main/kotlin/com/raidenyn/screentranslator/`; tests mirror them under `app/src/test/kotlin/com/raidenyn/screentranslator/`.

- `capture/RgbaFrameConverter.kt`: buffer/stride conversion only.
- `capture/ProjectionFrameSource.kt`: projection callback, reader thread, display geometry, fresh-frame await and cleanup.
- `capture/CaptureRequests.kt`: request IDs, pipeline execution, cancellation, correlated results and bitmap ownership.
- `service/ScreenCaptureService.kt`: foreground startup/notification/session ownership; replaces Accessibility service atomically with Activity migration.
- `ui/SetupActivity.kt`: settings, consent launcher, source routing and pending-request lifecycle.
- `ui/CaptureActivity.kt`: non-exported translucent Activity awaiting capture and launching Lens while resumed.
- `ui/SetupScreen.kt`: sharing status and explicit start/stop actions, retaining battery-settings link.
- Existing `ui/TrampolineActivity.kt`, `ui/LaunchSource.kt`, `capture/CaptureStore.kt`, `lens/LensLauncher.kt`: preserve their single responsibilities.
- Existing `capture/CaptureOrchestrator.kt`: remove after replacing it with CaptureRequests; keep ScreenshotTaker and the reduced error types in CaptureRequests.
- `res/drawable/ic_capture_notification.xml`: monochrome notification small icon.
- Manifest, strings, README and affected tests: migrate together with service/UI wiring.

### Task 1: Fresh MediaProjection frames and deterministic cleanup

**Files:**
- Create `capture/RgbaFrameConverter.kt`, `capture/ProjectionFrameSource.kt`.
- Create tests `capture/RgbaFrameConverterTest.kt`, `capture/ProjectionFrameSourceTest.kt`.

**Interfaces:**
- `internal fun rgbaToBitmap(buffer: ByteBuffer, width: Int, height: Int, pixelStride: Int, rowStride: Int): Bitmap` reads from the buffer's current position without mutating caller position.
- `internal class ProjectionFrameSource(context: Context, projection: MediaProjection, onStopped: () -> Unit) : ScreenshotTaker, AutoCloseable`.
- `override suspend fun take(): Bitmap` yields a newly acquired software bitmap or throws on timeout/stop; `override fun close(): Unit` is idempotent.
- Keep existing ScreenshotTaker temporarily; Task 2 relocates it without changing `suspend fun take(): Bitmap`.
- Introduce a small internal reader/display factory seam in this file for deterministic tests, with production default implementations. Do not mock the whole Android framework or expose test-only service globals.

- [ ] **Step 1: Add failing frame-conversion tests.** Define `paddedRowsPreserveOnlyVisiblePixels`, `nonzeroBufferPositionIsRespected`, and `invalidGeometryFailsBeforeAllocation`. A 2x2 RGBA fixture with 12-byte rows must yield a 2x2 bitmap with all four expected colors, no padding pixels, and unchanged buffer position; zero dimensions, short buffer and stride below visible row length must throw IllegalArgumentException.
- [ ] **Step 2: Run the tests red.** Run `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests '*RgbaFrameConverterTest'`; expect missing converter compilation failure.
- [ ] **Step 3: Implement rgbaToBitmap.** Use explicit RGBA channel conversion or a correctly byte-ordered packed copy; validate dimensions and buffer bounds using Long arithmetic before allocation. Avoid allocating a padding-width bitmap when rows can be copied directly.
- [ ] **Step 4: Verify converter tests green.** Repeat Step 2; expect success.
- [ ] **Step 5: Add failing frame-source tests.** `idleFramesAreClosedWithoutBitmapAllocation`, `takeDiscardsQueuedFramesAndWaitsForFreshImage`, `missingFrameTimesOutAfter5000ms`, `lateFrameAfterTimeoutDoesNotCompleteNextRequest`, `resizeReusesDisplayAndClosesOldReader`, and `staleReaderCallbackAfterCloseOnlyClosesImage`. Assert one display creation per source, callback registration before creation, maxImages=2, all Images closed on success/failure, and close/onStop cleanup only once.
- [ ] **Step 6: Run frame-source tests red.** Run `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests '*ProjectionFrameSourceTest'`; expect absent frame source or assertions failing.
- [ ] **Step 7: Implement ProjectionFrameSource.** Serialize reader/resize/capture state on a HandlerThread; use generation identity for replaced readers and a unique pending capture slot. Wrap acquisition/extraction in finally-close and use cancellable await with timeout, removing only the matching pending slot on cancellation. Drain idle frames. Initialize using maximum display metrics; use onCapturedContentResize on API 34+ and DisplayManager changes on API 30–33. Detach the old surface, resize the existing display and set its replacement surface on geometry changes. Unregister callbacks/listeners and terminate the handler thread on close; signal unexpected stop to the service once.
- [ ] **Step 8: Verify Task 1 green.** Run `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests '*RgbaFrameConverterTest' --tests '*ProjectionFrameSourceTest'`; expect success.

Suggested commit boundary: `feat: add fresh-frame MediaProjection capture source`.

### Task 2: Capture request identity and capture-to-cache pipeline

**Files:**
- Create `capture/CaptureRequests.kt` and `capture/CaptureRequestsTest.kt`.
- Modify `capture/CaptureOrchestrator.kt` only to move shared ScreenshotTaker without changing legacy behavior yet.
- Keep CaptureStore and LensLauncher contracts unchanged.

**Interfaces:**
- Relocate `fun interface ScreenshotTaker { suspend fun take(): Bitmap }` into CaptureRequests.kt.
- `internal sealed interface CaptureResult` with `data class Saved(val uri: Uri)` and `data class Failed(val error: CaptureError)`.
- `internal class CaptureRequests(taker: ScreenshotTaker, store: CaptureStore, scope: CoroutineScope, delayMs: Long = CAPTURE_DELAY_MS)`.
- `val results: StateFlow<Map<String, CaptureResult>>`; `fun request(id: String): Boolean`; `fun acknowledge(id: String): Unit`; `fun cancel(id: String): Unit`; `fun close(): Unit`.
- One request in flight; repeated same IDs return false. Retain at most one unacknowledged result, rejecting new work until it is acknowledged/canceled. No automatic Lens launch.
- Retain legacy CaptureError temporarily; Task 3 removes obsolete Accessibility/translation/version-specific cases and moves remaining errors here.

- [ ] **Step 1: Add failing pipeline tests.** `requestWaits400msThenSavesAndReturnsUri` asserts no take at 399 ms, one take at 400 ms and the exact stored URI result. `duplicateRequestsDoNotCaptureTwice` asserts overlapping IDs are rejected. `captureAndSaveFailuresHaveDistinctResults` asserts ScreenshotFailed versus SaveFailed. `completedResultSurvivesCollectorReplacementUntilAcknowledged` asserts collector recreation does not consume state, and acknowledge removes it. `cancelThenLateCompletionCannotPublishOrBlockNextRequest` asserts no result for the canceled ID and a subsequent request succeeds. `bitmapIsRecycledAfterSaveOrCancellation` checks bitmap ownership on both paths.
- [ ] **Step 2: Run tests red.** Run `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests '*CaptureRequestsTest'`; expect missing pipeline compilation failure.
- [ ] **Step 3: Implement CaptureRequests and relocate ScreenshotTaker.** Use unique per-request job identity, clear active state only for its owner, rethrow CancellationException, map extraction/storage errors separately, and recycle the owned bitmap in finally. Cancel/acknowledge discard only their matching result. Keep results bounded to one; release the guard after cancellation without waiting for unrelated late completion. Carry the existing 400 ms constant until Task 3 removes its old file.
- [ ] **Step 4: Verify pipeline and existing storage tests green.** Run `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests '*CaptureRequestsTest' --tests '*CaptureStoreTest' --tests '*CaptureOrchestratorTest'`; expect success and no change to legacy behavior yet.

Suggested commit boundary: `refactor: add lifecycle-safe screenshot request pipeline`.

### Task 3: Atomic foreground-service and Activity migration

This task migrates service, manifest, Activity clients and their tests together so
no intermediate shipped state mixes Accessibility readiness with projection readiness.

**Files:**
- Replace `service/ScreenCaptureService.kt`.
- Modify `ui/SetupActivity.kt`, `ui/SetupScreen.kt`, `ui/TrampolineActivity.kt`.
- Create `ui/CaptureActivity.kt`, `res/drawable/ic_capture_notification.xml`.
- Modify `app/src/main/AndroidManifest.xml`, `res/values/strings.xml`, and themes only if needed for the new transparent Activity.
- Remove `capture/CaptureOrchestrator.kt`, `lens/LensTranslateAutomation.kt`, `lens/LensTranslationSession.kt`, `res/xml/accessibility_service_config.xml`.
- Replace `service/ScreenCaptureServiceTest.kt`, `service/ServiceConnectionTest.kt`, `ui/SetupActivityTest.kt`, `ui/TrampolineActivityTest.kt`, `ui/ReusedLaunchTest.kt`; create `ui/CaptureActivityTest.kt`.
- Remove obsolete `capture/CaptureOrchestratorTest.kt`, `lens/LensTranslateAutomationTest.kt`, `lens/LensTranslationSessionTest.kt`. Retain LaunchSourceTest, CaptureStoreTest, LensLauncherTest and AppTest.

**Interfaces:**
- Service companion: `val connection: StateFlow<ScreenCaptureService?>`, publishing only fully initialized sessions; `val startupError: StateFlow<String?>` for failed session startup; `fun start(context: Context, resultCode: Int, data: Intent): Unit`; `fun stop(context: Context): Unit`.
- Service instance: `val requests: CaptureRequests`; callers must obtain it only from a ready connection. Use explicit start and stop actions; no static capture request routed to an arbitrary instance.
- SetupScreen: `@Composable fun SetupScreen(sharing: Boolean, onStartSharing: () -> Unit, onStopSharing: () -> Unit)`; status is supplied by Activity.
- CaptureActivity companion: `fun intent(context: Context, requestId: String): Intent`. `requestId` is a UUID generated once by SetupActivity and retained across recreation.
- CaptureActivity is translucent, non-exported, has no settings Compose content, waits until RESUMED to request or hand off, and acknowledges/cancels its request when consumed/abandoned. Recreation is not abandonment.
- Before opening CaptureActivity, the launcher root must also be transparent and empty. A warm opaque settings root is recreated into capture mode with its pending UUID saved; changing only the child theme would capture settings behind it. Returning to settings recreates the root with its opaque setup theme.
- After Lens is successfully launched, finish both capture-mode Activities. Keep the root alive while capture is pending so a new icon intent can still cancel its child through singleTask routing.
- Reduced CaptureError: ScreenshotFailed, SaveFailed, LaunchFailed. Keep error-to-string mapping close to UI/service notification error handling; no AccessibilityUnavailable, TranslationFailed or unsupported-version exception.

- [ ] **Step 1: Replace Accessibility-specific tests with failing service/manifest tests.** Assert foreground-service permissions and type, absent Accessibility registration, START_NOT_STICKY for missing/null start data, no projection for unsuccessful resultCode, foreground-before-projection and callback-before-display ordering, only-ready connection publication, duplicate starts preserving the existing session, stop action notification PendingIntent, and cleanup after stop/destroy/revocation. Script foreground promotion and projection acquisition throwing; assert null connection, terminal startup error, no leaked resources, and no pending restart.
- [ ] **Step 2: Run service tests red.** Run `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests '*ScreenCaptureServiceTest' --tests '*ServiceConnectionTest'`; expect failure against the old Accessibility implementation.
- [ ] **Step 3: Implement the service and manifest migration.** Call ServiceCompat.startForeground using FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION before getMediaProjection. Provide a low-importance notification channel with settings and Stop sharing PendingIntents and a real small icon. Build ProjectionFrameSource and CaptureRequests, publish connection last, and clean up idempotently. A normal stopService/stop action must not call startForegroundService anew. Handle startup exceptions from both Activity and service; never retry consumed consent. Add API-gated permission/type behavior for supported SDKs.
- [ ] **Step 4: Add failing Activity lifecycle tests.** Assert icon launch opens settings without consent; Sidebar without session launches one consent intent; cancellation/null results leave usable settings; API 34 uses default-display capture config; success starts the service once while visible; recreation does not relaunch consent. `iconIntentSupersedesSidebarBeforeConsentReturns` asserts no screenshot/Lens launch after that callback. Existing Sidebar task-reuse tests must still pass using projection readiness instead of onServiceConnected.
- [ ] **Step 5: Add failing CaptureActivity tests.** `captureWindowIsTransparentWithoutSettingsContent` checks its theme/content; `warmSettingsRootBecomesTransparentBeforeCapture` checks root recreation, no opaque root or Compose settings behind the child, and unchanged request UUID; `pausedActivityDefersLensUntilResume` checks no background start; `recreationDeliversSavedResultExactlyOnce` checks request ID continuity and one URI handoff; `newIconLaunchCancelsPendingCapture` checks no late Lens launch. Also assert duplicate taps do not create another capture, missing/stopped session returns to setup, Lens missing shows LaunchFailed, and readiness loss does not hang the Activity.
- [ ] **Step 6: Run Activity tests red.** Run `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests '*SetupActivityTest' --tests '*TrampolineActivityTest' --tests '*ReusedLaunchTest' --tests '*CaptureActivityTest'`; expect failure against old routing/absent CaptureActivity.
- [ ] **Step 7: Implement consent, settings and transparent handoff.** Register StartActivityForResult in SetupActivity, using MediaProjectionConfig.createConfigForDefaultDisplay on API 34+. Track consent-in-flight and pending launch source across recreation; an icon intent clears pending capture. After session readiness, recreate an opaque root into transparent capture mode before opening CaptureActivity with its retained UUID; render no settings content in capture mode. Keep the transparent launcher as singleTask root and close it on successful handoff so it cannot obscure the next target. An icon onNewIntent removes/cancels its child CaptureActivity; stop/revocation invalidates its pending request. CaptureActivity requests only once, observes the matching result, launches LensLauncher from its resumed Activity context, acknowledges and finishes without animation. On error/session loss recreate the root into opaque usable settings rather than leaving a transparent screen. Do not consume result data until the Activity is in a valid visible startup state.
- [ ] **Step 8: Remove legacy Accessibility implementation and update strings.** Move CAPTURE_DELAY_MS and reduced CaptureError into CaptureRequests, remove old orchestrator/automation/config/tests, and update callers/tests so no obsolete constructor or onServiceConnected reflection remains. Replace setup action labels with Start screen sharing/Stop screen sharing and status with sharing active/inactive; explain consent per session and manual Lens Translate. Retain battery settings as optional setup help.
- [ ] **Step 9: Verify integrated behavior green.** Run `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; expect success. Inspect the merged manifest for both FGS permissions, mediaProjection type, non-exported CaptureActivity and service, preserved singleTask launcher, and no Accessibility service. Search main source/resources for AccessibilityService, takeScreenshot, canTakeScreenshot, ACTION_ACCESSIBILITY_SETTINGS and automatic Translate copy; expect none.

Suggested commit boundary: `feat: replace accessibility capture with foreground MediaProjection sessions`.

### Task 4: User documentation and final verification

**Files:**
- Modify `README.md`.
- Create `docs/mediaprojection-device-checks.md`.

**Interfaces:**
- Documents Task 3's shipped user-visible start/stop actions and manual Lens selection; no new runtime APIs.

- [ ] **Step 1: Update README setup and usage.** Requirements become Android 11/API 30+, Google app/Lens and projection consent. Explain first Sidebar consent, repeated captures during a live session, stopping via app/system/notification, fresh consent after process death or revocation, and manual Translate selection. Preserve the verified ColorOS launch-source details. Remove claims that Accessibility must be enabled or no projection permission is needed. Describe protected-content capture limits without promising bank-app compatibility.
- [ ] **Step 2: Write device checks.** Specify API 30/33 and API 34+ checks for consent approve/deny, full-screen versus OEM capture selection, repeated static-screen captures, notification stop, system sharing stop, lock/revoke, rotation, killing/relaunching the process, missing Lens, and Sidebar launches while settings already exists. Include the critical check that the underlying target screen, not settings/consent UI, is in the saved PNG. Mark checks unrun until actual device evidence exists.
- [ ] **Step 3: Run final checks and inspect scope.** Run `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`, `git diff --check`, `git diff --stat` and `git status --short`. Expect all automated checks to pass; inspect intended deletions and ensure no user work was lost. Record APK location and which device checks remain unrun.
- [ ] **Step 4: Obtain final code review.** Review the whole diff against the approved spec, focusing on startup ordering, token reuse, frame-resource ownership, state races and background launch restrictions. If native execution is selected, dispatch one reviewer as described by executing-plans; fix material issues and rerun only affected checks plus required build/lint as warranted.

Suggested commit boundary: `docs: explain projection sharing and device validation`.

## Self-Review

- Spec coverage: Tasks 1–2 cover extraction, geometry, timeout, storage and correlated ownership; Task 3 covers manifest, service/consent lifecycle, UI, launch routing, and Accessibility removal; Task 4 covers migration documentation and device checks.
- Interface consistency: ScreenshotTaker.take is preserved; CaptureRequests owns all new pipeline state, and the service exposes it only through ready connections. CaptureActivity consumes results by UUID, with no service-to-Lens background launch.
- Race coverage: the five Review Focus conditions have named owning tests above. ImageReader/native SDK behavior and OEM translucent-window behavior require the documented device checks rather than a claim that Robolectric proves them.
- Migration scope: service/UI changes are deliberately atomic; the frame source and new request pipeline remain independently testable before that migration.
- No implementation or commits are authorized by this document alone; obtain plan approval and execution-method selection next.
