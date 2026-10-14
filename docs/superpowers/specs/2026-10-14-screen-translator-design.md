# Screen Translator — Design Spec

Date: 2026-10-14
Status: Approved in design review (chat), pending spec review

## Overview

An Android app that recreates the removed OxygenOS/ColorOS built-in screen-translation
tool. Primary target is a OnePlus 16 running ColorOS 17 (global, Google app/Lens
installed), with an eventual Play Store release in mind.

One tap from the ColorOS Smart Sidebar captures the current screen and opens Google
Lens with the screenshot already loaded, ready to translate. No per-use dialogs, no
extra taps inside our app.

## Requirements

Functional:

- F1: One tap from the ColorOS Smart Sidebar captures the visible screen and opens
  Google Lens with the image pre-loaded.
- F2: Zero additional actions in our app per use — no permission dialogs,
  confirmations, or visible UI.
- F3: A pre-capture delay (default 400 ms, tunable) so the collapsing sidebar
  animation is not caught in the screenshot.
- F4: If the accessibility service is disabled or was killed, the tap routes to the
  setup screen with guidance instead of failing silently.
- F5: Setup screen shows: accessibility service status with a link to settings,
  battery-optimization exemption prompt, Smart Sidebar how-to, and a test-capture
  button.

Non-functional:

- N1: Capture-to-Lens-launch completes in under 1 second typical.
- N2: Screenshots never appear in the gallery. Captures are stored in private cache,
  and stale captures are purged on every app launch.
- N3: The accessibility service never reads screen content
  (`canRetrieveWindowContent="false"`); it only captures pixels via `takeScreenshot()`.
- N4: minSdk 30 (Android 11+), target latest stable SDK.
- N5: No third-party dependencies beyond AndroidX/Compose.

## User Flow

1. User taps the app icon in Smart Sidebar → ColorOS launches `TrampolineActivity`.
2. Trampoline checks the accessibility service state:
   - Not enabled → open `SetupActivity`, finish.
   - Enabled → post a capture request to the service, finish immediately.
3. `ScreenCaptureService` waits `CAPTURE_DELAY_MS` (default 400) for the sidebar
   collapse animation, then calls `takeScreenshot()`.
4. `onScreenshotTaken` delivers the hardware bitmap → saved to
   `cacheDir/captures/<timestamp>.png` off the main thread.
5. `LensLauncher` wraps the file in a FileProvider URI and fires the fallback chain.
6. Lens opens fullscreen with the image loaded; the user translates inside Lens
   (Lens's own UI may still need one tap — accepted limitation, we cannot drive a
   third-party app).

## Components

Single Gradle module, five small units:

1. **TrampolineActivity** — transparent, `noHistory`, `excludeFromRecents`,
   no window animations. No UI. Routes to capture or setup, finishes.
2. **ScreenCaptureService** (AccessibilityService) — declared with
   `canTakeScreenshot="true"`, `canRetrieveWindowContent="false"`. Receives capture
   requests from the trampoline via a shared singleton/flow, runs the delayed
   capture, saves, launches Lens with `FLAG_ACTIVITY_NEW_TASK`, and reports
   failures back for user feedback (toast).
3. **LensLauncher** — ordered fallback chain, each step try/catch
   (ActivityNotFound/SecurityException) → next:
   1. Known Lens entry components (standalone `com.google.android.apps.lens`,
      Google app Lens trampoline activities) with `ACTION_SEND` + `EXTRA_STREAM` +
      `ClipData` + `FLAG_GRANT_READ_URI_PERMISSION` + `FLAG_ACTIVITY_NEW_TASK`.
   2. `ACTION_SEND` with `setPackage` restricted to Google's packages, first that
      resolves.
   3. System share sheet (`ACTION_CHOOSER`) as last resort — image still
      pre-attached.
   The component candidate list is a small constant verified on the target device
   (see Probe) and cheap to update when Google renames things.
4. **CaptureStore** — owns `cacheDir/captures/`: writes bitmaps, exposes
   FileProvider URIs, purges stale files on app start.
5. **SetupActivity** (Compose) — the only real UI: service status card +
   accessibility settings link, battery-optimization exemption prompt (ColorOS
   aggressively kills background services), Smart Sidebar instructions, and a
   "run test capture" button.

## Privacy & Data Handling

- Captures live only in the app's private cache dir.
- Lens receives a content URI with `FLAG_GRANT_READ_URI_PERMISSION` (temporary,
  scope-limited grant), never a world-readable file.
- Stale captures are deleted on every app launch; nothing is ever written to the
  gallery or MediaStore.
- No analytics, no network access.

## Risks & Mitigations

- **Lens intents are undocumented and drift with Google app updates** → ordered
  fallback chain ending in the share sheet; candidate components are verified on the
  physical device during the probe and kept in one constant.
- **Sidebar overlay caught in the screenshot** → 400 ms pre-capture delay, tunable.
- **ColorOS may open sidebar-launched apps in a floating window / kill background
  services** → verified in the probe; setup guides battery exemption and pinning;
  dead service routes to the setup screen (F4).
- **`takeScreenshot()` rate limit / transient failure** → surface a toast, allow
  immediate retry.
- **Play accessibility policy** → minimal capability declaration
  (`canRetrieveWindowContent="false"`, screenshot-only usage disclosed honestly).
  If Play review rejects it, a MediaProjection-based capture flavor (consent dialog
  per use) is the store-distribution escape hatch — out of scope for v1; personal
  sideloading is unaffected.

## Testing Strategy

- **Unit (Robolectric/JUnit):** LensLauncher fallback ordering and error-to-next
  behavior, CaptureStore purge logic, Trampoline routing logic (service
  enabled/disabled), delay configuration.
- **On-device probe (physical OnePlus 16, requires ADB):** the capture→Lens handoff
  cannot be validated in an emulator. Probe tasks:
  - P1: Enumerate resolvable Lens/Google activities handling `ACTION_SEND`
    image/* (via `pm`/`queryIntentActivities`) to build the component candidate list.
  - P2: Verify sidebar tap behavior — floating window vs fullscreen, collapse
    timing — to tune the delay.
  - P3: Verify ColorOS service survival (battery optimization on/off, app pinned).

## Tech Stack

Kotlin, single-module Gradle (Kotlin DSL) project, Jetpack Compose for the setup
screen, AndroidX core/Activity/Compose only. minSdk 30. Version catalog for
dependency management.

## Out of Scope (v1)

- In-app OCR/translation overlay (candidate future mode; would make the flow truly
  zero-tap end to end).
- Saving captures to the gallery.
- Play Store release work (signing, listing, policy submission).
- Language-pair configuration (Lens handles language selection in its own UI).