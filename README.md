# Screen Translator

One tap from the ColorOS **Smart Sidebar** captures the visible screen and opens it in
**Google Lens** for translation. No permission dialogs, no confirmation screens, no gallery
traces: the tap lands in a transparent trampoline activity, an accessibility service
takes the screenshot after a short delay (letting the sidebar collapse animation finish),
and the image is handed to Lens through a `FileProvider` URI.

How it works, in order:

1. `TrampolineActivity` (the app's launcher entry point) receives the sidebar tap.
2. If the capture service is not running, it opens the setup screen instead — never fails
   silently.
3. `ScreenCaptureService` (accessibility service, screenshot-only: it never reads window
   content) waits `CAPTURE_DELAY_MS` (400 ms) and takes the screenshot.
4. The image is saved to the app's private cache and purged on the next app start.
5. `LensLauncher` fires a three-step fallback chain: known Lens components →
   package-restricted `ACTION_SEND` → system share sheet, so the user always ends up with
   the screenshot in Lens.

## Requirements

Capture requires Android 13 (API 33) or newer. The app installs on Android 11–12 but
screen capture is unsupported there.

## Install

Build the debug APK, then install it:

```bash
JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Setup (one time)

1. **Enable the capture service.** Open the app once and tap *Enable capture service*,
   or go directly to system Settings → Accessibility → Screen Translator and turn it on.
   The accessibility service only takes screenshots; it never reads screen content.
2. **Add the app to Smart Sidebar.** Settings → Smart Sidebar → customize, then drag
   Screen Translator into the sidebar.
3. **Battery settings.** Tap *Battery settings* on the setup screen (it opens the system
   battery-optimization list) and set Screen Translator to *Don't optimize*, so ColorOS
   does not kill the background service. Optionally pin/lock the app in recents as well.

## Usage

Tap the Screen Translator icon in Smart Sidebar. Roughly 400 ms later the screenshot is
taken, and Google Lens opens with the image pre-loaded. Translate inside Lens.

Failures surface as toasts (screenshot failed / save failed / Lens launch failed). If the
service is disabled or was killed, the same tap opens the setup screen instead, with a
live service status chip and a *Run test capture* button.

## Probe checklist (run on the OnePlus 16, update code from results)

`LensCandidates.COMPONENTS` (in
`app/src/main/kotlin/com/raidenyn/screentranslator/lens/LensCandidates.kt`) currently
holds **best-guess** component names. Replace them with what the device actually reports.

### P1 — Lens components

List every activity that can receive a PNG share:

```bash
adb shell pm query-activities --brief -a android.intent.action.SEND -t image/png
```

Dump the two Google packages and grep for Lens:

```bash
adb shell dumpsys package com.google.android.googlequicksearchbox | grep -i lens
adb shell dumpsys package com.google.android.apps.lens | grep -i lens
```

Record every activity that offers Lens. Replace the `LensCandidates.COMPONENTS` guesses
with them. Reinstall, run one tap, and verify Lens opens directly with the image
pre-loaded — no share sheet.

### P2 — sidebar timing

Tap the app from Smart Sidebar. If the collapsed sidebar appears in the screenshot, bump
`CAPTURE_DELAY_MS` (in
`app/src/main/kotlin/com/raidenyn/screentranslator/capture/CaptureOrchestrator.kt`) in
100 ms steps until it does not.

Also note whether the app opens fullscreen or floating from the sidebar tap, and record
the ColorOS setting that controls that behavior (the capture pipeline expects a normal
fullscreen launch).

### P3 — survival

With the battery settings opened as the setup screen guides, reboot the device and
confirm a sidebar tap still captures. If ColorOS still kills the service, note the ColorOS
*allow auto-launch* toggle here and add it to the setup steps above (record the actual
settings path on the device).

## Play-policy note

Battery guidance deliberately opens `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
(plain system settings; the user opts the app out of optimization themselves). The app
does not hold or request the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, whose Play
policy exemption list is narrow. This is the deliberate Play-safer variant of an
exemption prompt.
