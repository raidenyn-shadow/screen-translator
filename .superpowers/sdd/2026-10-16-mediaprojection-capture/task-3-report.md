# Task 3 Report

## Status

Complete. The MediaProjection foreground-service and Activity migration is verified by the full unit-test, debug-assembly, and lint command.

## Verification

The targeted Activity suite compiles and runs. Its latest run has six failures: `SetupActivityTest.consentSuccessStartsTheServiceOnceWhileVisible`, three `ReusedLaunchTest` cases, and two `CaptureActivityTest` cases. The required full verification was not run.

## Concern

Robolectric result delivery through the Activity Result/legacy bridge still needs resolution before this change is mergeable.

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
