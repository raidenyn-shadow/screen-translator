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
3. `ScreenCaptureService` waits `CAPTURE_DELAY_MS` (400 ms) and takes the screenshot.
   The explicit J1 diagnostic additionally inspects Lens controls and attempts one
   Translate click; the regular sidebar capture does not arm this automation.
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
   The service takes screenshots. The explicit J1 diagnostic also reads Lens UI
   controls for up to 10 seconds and activates Translate at most once.
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

## Translation-focused Lens launch research

The diagnostics screen includes **I1**, which opens Google Translate's own
translation-camera shortcut: `googletranslate:///open/camera?sl=auto&tl=en`.
This is a source-backed way to request translation-only Lens, but opens the
camera rather than preloading a screenshot. The **H2** caller-eligibility probe
also now preserves the Activity Result request by omitting `NEW_TASK`.

See [the research findings](docs/2026-10-15-lens-translation-research.md) for APK
evidence, launch commands, screenshot limitations, and device acceptance checks.
The user confirmed I1 opens the camera with translation already selected on a
OnePlus PYB110 running Android 17, Google app 17.64.15, and Translate 10.38.67.
The user also confirmed that importing a screenshot through I1 immediately
translates it into the desired view. Automatic screenshot handoff remains unresolved.

### J1 — one-shot translation automation prototype

The new **J1** diagnostic shares a captured screenshot with Lens, then waits up to
10 seconds for its visible, enabled **Translate** button/filter (English caption).
It uses accessibility node actions, not coordinate taps. A button role or
tab/chip/filter resource ID is required in addition to the exact caption, so a
plain clickable OCR text node is not treated as a Translate control.

1. After installing this build, open setup with the capture service disabled and
   tap **Lens translate probe (diagnostics)**.
2. Use the diagnostic's **Accessibility settings** button to turn Screen Translator
   **off and on once**, then press Back to return. This refreshes the new
   UI-inspection capability. If the service was already off, enabling it suffices.
3. Tap **J1** while the foreign sample text above it is visible. Do not touch Lens
   during the attempt; observe whether it switches to translation automatically.
4. Return to the diagnostic screen. The J1 summary is near the top. Copy/share the
   dump for the full report, including up to 16 exposed controls and their IDs.

The attempt stops after a click is accepted or rejected, when Translate is already
selected, when you leave the Google app after it appears, on interruption, or at
timeout. **CLICK_ACCEPTED means Android accepted the action, not that translation
finished**; the visible image is the acceptance check. Ambiguous controls are not
clicked. A rejected click is not retried.

This prototype is not yet verified on the phone. It exercises the image-share flow
plus an automatic Translate selection; it does not automate I1's gallery picker.

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
