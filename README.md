# Screen Translator

One tap from the ColorOS **Smart Sidebar** captures the visible screen, opens the
screenshot in **Google Lens**, and selects **Translate** automatically.

## Requirements

- Android 13 (API 33) or newer for screen capture.
- The Google app installed with Google Lens available.
- An English Lens UI exposing the **Translate** control.
- Screen Translator's accessibility service enabled.

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

1. Open the app with the service disabled. Use **Battery settings** to exempt it
   from battery optimization. Pin/lock it in recents if ColorOS stops the service.
2. Tap **Enable capture service**, then enable Screen Translator in Android's
   Accessibility settings. After an update, turn the service off and on if Android
   has not refreshed its capabilities.
3. Add Screen Translator to **Smart Sidebar** in Settings → Smart Sidebar →
   customize.

## Launch behavior

- A **Smart Sidebar** tap captures the screen and translates it. If the tap
  arrives while the service is off or reconnecting, setup opens to wait for the
  service, then closes and the pending capture proceeds automatically.
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

### Keeping accessibility access enabled

Android saves the accessibility-service selection; a normal process restart or
device reboot should not require granting access again. A disconnected service
is not necessarily a revoked grant: setup waits for Android to reconnect it.

**Force stop is different.** Android deliberately removes a force-stopped app's
services from the saved enabled-accessibility-services list. Opening the app
again cannot restore that selection through ordinary app APIs; enable Screen
Translator again in Accessibility settings. Uninstalling and reinstalling also
requires enabling the service again. For updates, install over the existing app
with the same package name and signing key rather than uninstalling first.

For everyday use on ColorOS/OnePlus:

- Avoid **Force stop** and do not turn off the service in Accessibility settings
  unless you intend to disable it.
- Allow background activity and auto-launch where those options are available in
  the phone's app/battery settings, and exempt the app from battery optimization.
  These can reduce vendor background shutdowns; they do not override Force stop.
- If accessibility is still enabled but Android reports the service as not
  working, try toggling it off and on once to reconnect it.

There is no ordinary app permission that makes accessibility access permanent
across Force stop, explicit disabling, or uninstall. Screen Translator uses
accessibility for both screenshots and Lens interaction, so it does not need
storage, overlay, camera, or per-session MediaProjection permission. The battery
settings link is an optional reliability measure, not a capture permission.

Platform reference: Android's
[`AccessibilityManagerService.onPackagesForceStoppedLocked`](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java)
removes the affected services and persists the updated enabled-services list.

## Usage

Tap Screen Translator in Smart Sidebar and let Lens open. The app automatically
activates Translate once; no gallery selection or extra Translate tap is needed.
Opening the app's icon shows the setup/settings screen instead.

The accessibility service:

1. Waits 400 ms for the sidebar to collapse, then captures the screen.
2. Saves the image in private cache and shares a temporary read-only FileProvider
   URI with the Google app's Lens image-share activity.
3. Looks for the visible, enabled Translate button/filter for up to 10 seconds.
4. Selects it once and stops. If Translate is already selected, it leaves it selected.

Selection uses accessibility controls, not fixed screen coordinates. Ambiguous
controls are not clicked. Overlapping taps are ignored until the current capture
and selection finish. Leaving the Google app cancels a pending selection.

Screenshots are not saved to the gallery. Cached captures are purged at the next
app process start. The app does not keep UI-control dumps or diagnostic reports.

Capture, image-save, Lens-launch, and Translate-selection failures are shown as
short toasts. If the service is disabled, a tap opens setup instead.

## Development checks

```bash
JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Unit tests cover screenshot storage, pipeline errors and duplicate requests,
Lens sharing, one-shot translation selection, cancellation, and timeout behavior.
