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

1. Open the app and tap **Enable capture service**, then enable Screen Translator
   in Android's Accessibility settings. After an update, turn the service off and
   on if Android has not refreshed its capabilities.
2. Add Screen Translator to **Smart Sidebar** in Settings → Smart Sidebar →
   customize.
3. Use **Battery settings** in the setup screen to exempt the app from battery
   optimization. Pin/lock it in recents if ColorOS stops the service.

The setup screen appears when the accessibility service is off. With the service
running, opening the app's icon performs the same capture action as a sidebar tap.

## Usage

Tap Screen Translator in Smart Sidebar and let Lens open. The app automatically
activates Translate once; no gallery selection or extra Translate tap is needed.

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
