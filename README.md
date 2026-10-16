# Screen Translator

One tap from the ColorOS **Smart Sidebar** captures the visible screen and opens
the screenshot in **Google Lens**. Select **Translate** manually in Lens.

## Requirements

- Android 11 (API 30) or newer.
- The Google app installed with Google Lens available.
- Permission to share the screen when Android asks.

The capture-and-translate approach was verified by the user on a OnePlus PYB110
running Android 17 with Google app `17.64.15.ve.arm64`.

## Install

Download the APK from [GitHub Releases](https://github.com/raidenyn-shadow/screen-translator/releases).

To build locally:

```bash
JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Setup

1. Open the app. Use **Battery settings** to exempt it
   from battery optimization. Pin/lock it in recents if ColorOS stops the service.
2. Add Screen Translator to **Smart Sidebar** in Settings → Smart Sidebar →
   customize.
3. Tap **Start screen sharing**, or tap Screen Translator in Smart Sidebar. The
   first Sidebar capture asks for Android screen-sharing consent. Approve it while
   the app is visible.

## Launch behavior

- A **Smart Sidebar** tap captures the screen while sharing is active and opens
  the image in Lens. If sharing is not active, the first tap requests consent and
  then performs its pending capture after the session starts.
- Opening the app's **icon** from the home screen or app drawer opens the setup
  screen as a settings page. It stays open even when the service is ready.

Sidebar launches are recognized through the `from_package` extra ColorOS adds
(observed as `com.coloros.smartsidebar` on a OnePlus PYB110 running Android 17).
Launches without that marker — other launchers, other OS versions — open
settings instead of translating. If sidebar taps on your phone open settings,
report the phone model and OS version so detection can be extended.

The launcher retains the settings page in its own `singleTask` activity and
handles subsequent launches through `onNewIntent`. This is important on ColorOS:
otherwise a sidebar tap can simply bring an existing settings task forward and
skip the source-routing code.

## Usage

After you approve Android's sharing prompt, screen sharing stays active for that
live session. Each Sidebar tap captures a fresh screen, saves it in private cache,
and opens it in Lens. Select **Translate** manually; the app does not control the
Lens interface.

Stop sharing with **Stop screen sharing** in the app, the **Stop sharing** action
in the ongoing notification, or Android's system sharing controls. Android can
also end sharing when it revokes the projection or the device locks. Sharing is
not restored after a process death, force stop, revocation, or reboot: open the
app or tap Sidebar and approve a fresh consent prompt to start another session.

Allow background activity and auto-launch where those options are available in
the phone's app/battery settings, and exempt the app from battery optimization.
These can reduce vendor background shutdowns but cannot keep a revoked or stopped
sharing session alive.

Screenshots are not saved to the gallery. Cached captures are purged at the next
app process start. The app does not keep UI-control dumps or diagnostic reports.

Capture, image-save, and Lens-launch failures are shown as short toasts. If Lens
is unavailable, install or enable the Google app and try again.

Android and individual apps control what MediaProjection can show. Secure or
protected content can be black or unavailable in the capture. Removing this
app's Accessibility dependency does not guarantee that a bank or other protected
app will allow screen sharing or capture.

## Development checks

```bash
JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Unit tests cover screenshot storage, projection-frame handling, request lifecycle,
Lens sharing, cancellation, timeout behavior, and session lifecycle. Physical
device validation is tracked in
[`docs/mediaprojection-device-checks.md`](docs/mediaprojection-device-checks.md).
