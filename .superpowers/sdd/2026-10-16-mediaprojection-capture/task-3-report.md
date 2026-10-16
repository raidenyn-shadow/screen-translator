# Task 3 Report

## Status

Complete. The MediaProjection foreground-service and Activity migration is verified by the full unit-test, debug-assembly, and lint command.

## Historical Attempt

An earlier attempt had six targeted-test failures before the teardown isolation and lifecycle fixes recorded below. Those failures are historical only; they are not the current state.

## Review Fixes

The preceding partial-status note records the earlier attempt. The current final status above is authoritative.

- A successful `CaptureActivity` result now finishes its transparent launcher root; canceled or failed child results recreate opaque settings.
- Consent has distinct in-flight and awaiting-session states. Cancel/null data returns to settings without retrying, while an accepted grant waits for service readiness rather than reopening consent.
- Results delivered while the root is not resumed are retained until it resumes. Activity Result callback ownership is singular; the legacy callback override was removed.
- Consent creation/launch and service-start calls are guarded. Startup errors return to opaque settings and show the service error.
- Capture ownership is retained across pause and unconsumed requests are canceled from `CaptureActivity.onDestroy`, covering back, destruction, and icon cancellation.

Verification after these fixes:

```text
JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
BUILD SUCCESSFUL in 31s
56 actionable tasks: 11 executed, 45 up-to-date
```

The merged debug manifest again contains both foreground-service permissions, a non-exported `mediaProjection` `ScreenCaptureService`, a non-exported `CaptureActivity`, and the `singleTask` launcher. It contains no Accessibility service. Searching main source/resources for `AccessibilityService`, `takeScreenshot`, `canTakeScreenshot`, `ACTION_ACCESSIBILITY_SETTINGS`, and automatic Translate copy returned no matches.

## Consent Re-review Fixes

Final authoritative status: complete and verified.

Deferred-consent round three delivered stopped results from a posted RESUMED-state callback, preventing re-deferral. The stopped-result test now proves one service start after resume and no queued duplicate. Targeted verification passed in 16s and the required full command passed in 28s.

- Duplicate sidebar intents now honor `awaitingSession` and cannot prompt for a second consent grant while a successful grant is starting its service.
- Deferred consent result code and data are saved/restored and consumed once from a resumed root.
- Consent-intent creation is an overridable narrow seam and all creation/launch/start failures reset to usable settings.

Targeted Activity tests passed:

```text
BUILD SUCCESSFUL in 22s
29 actionable tasks: 5 executed, 24 up-to-date
```

Full verification passed:

```text
JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
BUILD SUCCESSFUL in 27s
56 actionable tasks: 11 executed, 45 up-to-date
```

The merged manifest retains both foreground-service permissions, non-exported `CaptureActivity` and mediaProjection service, and the singleTask launcher. The required forbidden-reference search returned no matches.

## Completion

### RED Evidence

Before the cleanup fix, the required targeted command ran 28 tests and failed four:

```text
JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests '*SetupActivityTest' --tests '*TrampolineActivityTest' --tests '*ReusedLaunchTest' --tests '*CaptureActivityTest'
ReusedLaunchTest.restoredPendingCaptureWaitsForANewerIconIntentBeforeResume: IllegalStateException: No activity at cleanup line 49
ReusedLaunchTest.sidebarIntentToExistingSettingsWaitsForProjectionReadiness: consent did not start the service
ReusedLaunchTest.sidebarIntentToExistingSettingsRequestsConsentOnTheSameActivity: unexpected activity recreation
SetupActivityTest.consentSuccessStartsTheServiceOnceWhileVisible: consent did not start the service
```

The teardown exception left the booted service alive, so its static ready connection polluted the following tests. `activities.remove(original)` immediately after the test's explicit destroy prevents a second lifecycle teardown and lets cleanup destroy the booted service.

### GREEN Evidence

The same targeted command passed after that one-line test-isolation fix:

```text
BUILD SUCCESSFUL in 31s
29 actionable tasks: 2 executed, 27 up-to-date
```

The mandatory verification then passed after changing `SetupActivity` to collect the service `StateFlow` in Compose rather than directly reading `value` during composition:

```text
JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
BUILD SUCCESSFUL in 43s
56 actionable tasks: 16 executed, 40 up-to-date
```

### Manifest And Source Inspection

Merged manifest: `app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`.

- Both `android.permission.FOREGROUND_SERVICE` and `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION` are present.
- `ScreenCaptureService` is non-exported and `android:foregroundServiceType="mediaProjection"`.
- `CaptureActivity` is non-exported.
- `TrampolineActivity` remains `singleTask`.
- No Accessibility service declaration exists.

Searching `app/src/main` for `AccessibilityService`, `takeScreenshot`, `canTakeScreenshot`, `ACTION_ACCESSIBILITY_SETTINGS`, `selects Translate automatically`, `select Translate automatically`, and `automatic Translate` returned no matches.

## Task 3 Fix Round 4

### RED Evidence

The five new `SetupActivityTest` selectors were first run individually against the prior implementation. Each command was red at test compilation because the consent launcher had no narrow injectable boundary:

```text
SetupActivityTest.deferredConsentSurvivesRecreationBeforeResumeAndStartsOnce
SetupActivityTest.consentIntentFailureReturnsToSettingsAndAllowsAnotherStart
SetupActivityTest.consentLauncherFailureReturnsToSettingsAndAllowsAnotherStart
SetupActivityTest.serviceStartFailureReturnsToSettingsAndAllowsAnotherStart
SetupActivityTest.publishedStartupErrorReturnsToSettingsAndAllowsAnotherStart

'launchConsent' overrides nothing
Unresolved reference 'launchConsent'
BUILD FAILED
```

After adding only that protected launcher boundary, the executable deferred-result regression remained red: the recreated root reached `RESUMED` without a service-start dispatch. The saved deferred grant was intact; its posted delivery ran before the lifecycle reached `RESUMED` and was not retried. The failure was `SetupActivityTest.deferredConsentSurvivesRecreationBeforeResumeAndStartsOnce` at the expected start-service assertion.

### Fix And GREEN Evidence

- `SetupActivity` now processes deferred consent inside a `repeatOnLifecycle(RESUMED)` block. It persists the stopped-result grant through saved state, delivers it only after the recreated root is resumed, and consumes it once.
- `consentResult` and consent-launching are protected narrow seams, allowing deterministic tests for a stopped result and a launcher exception without framework-wide mocks.
- New recovery tests independently cover throwing consent-intent creation, throwing launcher dispatch, throwing service dispatch, and a real service-published `startupError`. Each asserts an idle opaque settings root with no queued prompt/start and verifies a later explicit start dispatches the service.

Focused new-regression command passed:

```text
JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests com.raidenyn.screentranslator.ui.SetupActivityTest.deferredConsentSurvivesRecreationBeforeResumeAndStartsOnce --tests com.raidenyn.screentranslator.ui.SetupActivityTest.consentIntentFailureReturnsToSettingsAndAllowsAnotherStart --tests com.raidenyn.screentranslator.ui.SetupActivityTest.consentLauncherFailureReturnsToSettingsAndAllowsAnotherStart --tests com.raidenyn.screentranslator.ui.SetupActivityTest.serviceStartFailureReturnsToSettingsAndAllowsAnotherStart --tests com.raidenyn.screentranslator.ui.SetupActivityTest.publishedStartupErrorReturnsToSettingsAndAllowsAnotherStart
BUILD SUCCESSFUL in 19s
29 actionable tasks: 5 executed, 24 up-to-date
```

Required targeted Activity verification passed:

```text
JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests '*SetupActivityTest' --tests '*TrampolineActivityTest' --tests '*ReusedLaunchTest' --tests '*CaptureActivityTest'
BUILD SUCCESSFUL in 19s
29 actionable tasks: 2 executed, 27 up-to-date
```

Required full verification passed:

```text
JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
BUILD SUCCESSFUL in 28s
56 actionable tasks: 4 executed, 52 up-to-date
```

## Final Status

Complete. The round-four deferred-consent recreation and independent recovery proofs are implemented and verified by the commands above.
