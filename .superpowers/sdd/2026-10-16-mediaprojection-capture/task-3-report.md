# Task 3 Report

## Status

Partial. The MediaProjection foreground service, manifest migration, capture request lifecycle, transparent capture activity, and Accessibility removal are committed.

## Verification

The targeted Activity suite compiles and runs. Its latest run has six failures: `SetupActivityTest.consentSuccessStartsTheServiceOnceWhileVisible`, three `ReusedLaunchTest` cases, and two `CaptureActivityTest` cases. The required full verification was not run.

## Concern

Robolectric result delivery through the Activity Result/legacy bridge still needs resolution before this change is mergeable.

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
