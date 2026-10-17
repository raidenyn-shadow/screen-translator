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

1. `CaptureActivity` receives a saved screenshot while resumed.
2. It removes its entire Sidebar capture task, then starts the transparent relay
   from `ScreenCaptureService` with a fullscreen request and `NEW_TASK`.
3. The relay grants read access to the Google app and starts the Lens intent for
   a result. Intent flags and ClipData carry the grant through Android's activity
   launch path; the explicit grant alone was insufficient on the tested device.
4. Saved instance state prevents a duplicate Lens launch after recreation. The
   relay retains access across configuration changes and revokes it on result,
   launch failure, or terminal destruction.

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
