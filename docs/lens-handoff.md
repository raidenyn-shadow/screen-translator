# Native Lens translation handoff

## Confirmed behavior

The user confirmed v0.7.7 works end to end: one Smart Sidebar tap captures the
screen and opens its translation in native Google Lens. The tested configuration
is OnePlus PYB110, Android 17, Google app `17.64.15.ve.arm64` (versionCode
301817218). Version 0.8 retains that request and task/grant lifecycle.

Google Translate preference and downloaded offline-pack reuse have not been
separately verified.

## Request contract

`lens/LensRequest.kt` builds an explicit intent to:

```text
com.google.android.googlequicksearchbox/
com.google.android.apps.search.lens.LensExportedActivity
```

| Field | Value | Purpose |
| --- | --- | --- |
| Data URI | Unset | Keeps the request on the extras-based image/filter path. |
| `injected_image_uri` | Screenshot content URI | Supplies the captured image. |
| `open_filter` | `translate` | Selects translation without a manual tap. |
| `caller_package` | Screen Translator's package | Identifies the actual app. |
| ClipData | The same screenshot URI | Exposes the URI to Android's grant machinery. |
| Flags | `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` and `FLAG_GRANT_READ_URI_PERMISSION` | Selects the accepted entry mode and grants image access. |

Language overrides are unset. The intent has no `NEW_TASK` flag and is started
for a result from `TranslateRelayActivity`. This is a private, device-tested
contract; it is not Google's public image-translation API.

## Task and grant ownership

1. The transparent capture-mode `SetupActivity` submits `ACTION_TRANSLATE` to
   `ScreenCaptureService` and removes its entire Sidebar task.
2. The service waits for a fresh frame, retaining the newest image over a 750 ms
   settling window, saves it, and starts the transparent relay with fullscreen
   options, `NEW_TASK`, and `EXCLUDE_FROM_RECENTS`. Results and failure toasts are
   handled on main. This v0.8.1 ordering is pending device validation.
3. The relay grants read access to the Google app and starts the Lens intent for
   a result. Intent flags and ClipData carry the grant through Android's activity
   launch path; the explicit grant alone was insufficient on the tested device.
4. Saved instance state prevents a duplicate Lens launch after recreation. The
   relay retains access across configuration changes and revokes it on result,
   launch failure, or terminal destruction.
5. If starting the Lens intent throws, the relay releases the grant, toasts that the
   Google app with Lens is required, and opens its store page (`market://`, then the
   Play web URL). A non-OK Lens result does not open the store.

## Regression-sensitive details

- Removing the history bit causes Google's shortcut-only guard to throw before
  image parsing. `0x00100000` is the history bit, not `NEW_TASK` (`0x10000000`).
- Resolving `google://lens` through the `MainActivity` alias selects a different
  path that rejected this app with an eligibility error.
- Omitting the intent grant/ClipData caused screenshot provider read denials.
- Removing the capture task before the handoff prevents ColorOS from inheriting
  the Sidebar's floating-window source task.

The detailed investigation and failed requests are preserved in
[`2026-10-16-oxygenos-native-translation-research.md`](2026-10-16-oxygenos-native-translation-research.md).
