# MediaProjection screen capture refactor

## Intent and agreed scope

Replace Accessibility-based screenshots with Android's MediaProjection API so
Screen Translator does not require an enabled Accessibility service. The user
reports that financial applications refuse to run while Accessibility is active.
The user approved removing automatic Lens Translate-button selection for now;
future translation automation is a separate task.

Preserve the existing distinction between ColorOS Smart Sidebar capture launches
and home-screen/app-drawer settings launches, including `singleTask` reuse and
`onNewIntent` handling. Preserve private screenshot caching and Lens sharing.
Keep minSdk 30 and compile/target SDK 36.

Success means there is no Accessibility service declaration or Accessibility
screenshot API in the app; a user-authorized foreground projection session supplies screenshots;
and captures open in Lens, where the user selects Translate manually.

## Approach and lifecycle

Use a persistent, explicitly consented MediaProjection session owned by a
foreground service. Each Sidebar tap requests one fresh screenshot from that
session. Sharing remains active until the user stops it, Android revokes it, or
the process/service terminates. It is not silently restarted or restored.

Alternatives considered:

- A one-shot service per screenshot is simpler but requires another consent
  prompt for every screenshot on Android 14+. It undermines repeated Sidebar use.
- Keeping an Accessibility service only for Lens automation preserves automatic
  selection but fails the goal of eliminating the active Accessibility dependency.

The persistent session is the selected design. It keeps one MediaProjection and
one VirtualDisplay per consent token; repeated screenshots do not reuse the token
to construct another projection or display.

## Manifest and Accessibility removal

- Declare `android.permission.FOREGROUND_SERVICE` and
  `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION`.
- Declare the capture service as non-exported with
  `android:foregroundServiceType="mediaProjection"`.
- Remove `BIND_ACCESSIBILITY_SERVICE`, the Accessibility intent filter and
  metadata, and `accessibility_service_config.xml`.
- Remove Lens window inspection/Translate-click automation and its obsolete tests.
  Keep LensLauncher and its sharing tests.
- Replace Accessibility setup/status strings and documentation with projection
  consent, session status, stop-sharing controls, and manual Lens instructions.
- A foreground notification is always supplied. Notification runtime permission
  must not gate projection startup; Android allows foreground service operation
  without that grant, with system-provided foreground-service visibility.

## Activity and consent ownership

SetupActivity registers an Activity Result launcher for
`MediaProjectionManager.createScreenCaptureIntent()`. Start sharing is an
explicit setup action; a Sidebar tap without a live session requests consent for
its pending capture. Canceling consent clears the pending request and leaves a
usable setup page without retry loops.

On API 34+, request default-display capture using MediaProjectionConfig, since
the product captures the screen behind the Sidebar rather than a selected app.
On earlier versions use the standard capture intent. Android/OEM sharing UI and
policy remain authoritative.

Only a successful result with non-null consent data is forwarded to the service
via `ContextCompat.startForegroundService`, while the Activity is visible.
Forward the result once. Never persist consent data or retry it after startup
failure, process death, or revocation. Reject overlapping consent/session starts.

Settings opened by an ordinary icon launch remain open and do not request a
screenshot. Preserve the ColorOS launch marker and latest-intent-wins behavior
for pending captures; recreation must not duplicate consent or capture requests.
An icon launch can cancel pending capture intent without replaying a consent
result or stopping an independently authorized sharing session.

## Foreground service and session state

ScreenCaptureService becomes a normal Service. It owns session state and capture
work, exposing observable readiness and request/result state to the in-process
Activity rather than treating service creation as projection readiness.

Startup order:

1. A visible Activity obtains user consent and starts the service.
2. The service promptly creates its notification channel and calls
   ServiceCompat.startForeground with the mediaProjection service type.
3. The service obtains MediaProjection once using the successful consent result.
4. It registers MediaProjection.Callback before creating the VirtualDisplay.
5. It creates the ImageReader and VirtualDisplay, then publishes ready state.

Use START_NOT_STICKY. Missing/invalid result data, stale tokens, denied service
startup, and initialization errors stop the incomplete session and publish a
user-facing failure. Duplicate start intents must not replace a live session or
consume another token. Stop actions require no new foreground-service start.

The ongoing notification has a setup content intent and a Stop sharing action.
Setup also exposes a stop action. `onStop`, explicit stop, and `onDestroy` converge
on idempotent cleanup: clear readiness, cancel pending captures, detach listeners,
release VirtualDisplay, close ImageReader, unregister the projection callback,
stop MediaProjection when appropriate, terminate the reader thread, cancel the
service scope, and remove the foreground notification. No stale singleton or
saved session state can report readiness after shutdown.

## Screenshot extraction

Use an RGBA_8888 ImageReader with bounded buffering and a VirtualDisplay targeting
its Surface. Initialize dimensions from full-display window metrics and use the
display density, rather than the possibly small Sidebar Activity window bounds.
Handle rotation and capture-size changes by resizing the existing VirtualDisplay
and replacing its Surface/ImageReader; never create a second VirtualDisplay on
the existing projection token. Use onCapturedContentResize on API 34+ and display
change handling for earlier supported APIs.

Consume and close images while idle to prevent a full queue from stalling the
producer, but do not create bitmaps or save unsolicited frames. For a capture
request, wait for the Sidebar/consent UI to disappear, discard queued images,
then accept a newly delivered frame. Serialize capture work and drop overlapping
requests. Use a bounded timeout so an absent frame cannot leave a request pending
forever. Cancellation and shutdown release the pending request.

Read the image plane using its buffer position, pixel stride, row stride, and
actual image dimensions. Convert to a software ARGB_8888 bitmap, excluding row
padding. Close every Image in a finally block and release intermediate bitmaps
after saving. Catch extraction/storage failures at the pipeline boundary and
surface a concise error without leaving the session or duplicate guard stuck.

## Storage, Lens handoff, and Activity visibility

Retain CaptureStore, the private cache location, FileProvider authority, and
temporary URI read grants. Simplify CaptureOrchestrator to capture and save,
returning a correlated URI/error result instead of performing Accessibility
automation or launching Lens from the service.

The translucent capture Activity stays visible with no opaque Compose settings
content while capture runs, exposing the underlying screen. It launches Lens
with the completed URI while resumed, then finishes without animation. This
avoids relying on an Accessibility exemption or on a foreground service being
allowed to launch an Activity from the background. Settings-initiated pending
capture must transition to this transparent capture state as well.

Results are delivered once to the matching request. If the Activity is paused,
defer Lens launch until resumed. Recreation must preserve request identity;
a newer icon intent cancels its old pending handoff. A canceled request must
not launch Lens later. Errors return the Activity to usable settings with a
concise message. The service can remain ready for subsequent requests.

## Platform boundaries

The implementation respects MediaProjection consent and Android's capture
restrictions. Secure/FLAG_SECURE content may be black or unavailable. Removing
this app's Accessibility service addresses its reported dependency; it cannot
guarantee that a particular banking app permits screen sharing or capture.
Locking the device, system revocation, and vendor process termination may end a
session, requiring fresh consent. No reboot receiver, automatic restart, overlay
permission, or Accessibility fallback is introduced.

## Verification

Update meaningful Robolectric/unit coverage for:

- Manifest permissions, foreground service type, and absent Accessibility entry.
- Consent success, cancellation, null data, recreation, and repeated launches.
- Foreground promotion before projection creation, callback-before-display
  ordering, duplicate starts, and failure/shutdown cleanup.
- One projection/display per session, resize behavior, padded image conversion,
  frame timeout/cancellation, and duplicate capture requests.
- Settings versus Sidebar routing, transparent capture state, one-time result
  handoff, and resumed-Activity Lens launch.
- Existing screenshot storage and URI sharing behavior.

Run the following and inspect the merged manifest:

```bash
JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Robolectric
cannot prove actual MediaProjection consent or OEM screen behavior, so document
device checks for API 30/33 and API 34+: consent and denial, repeated screenshots,
rotation, stop from notification/system UI, process death and fresh consent,
Sidebar versus icon launches, and manual Lens translation.

## Sources

- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection

Direct retrieval of these pages was unavailable in this session. Validate exact
API signatures and behavior against the installed Android SDK during implementation.
