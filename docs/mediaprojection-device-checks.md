# MediaProjection Device Checks

Robolectric does not exercise Android's real MediaProjection consent UI, OEM
sharing choices, ImageReader behavior, or translucent-window capture. Run these
checks on physical devices before release.

**Confirmed core flow:** on 2026-10-17, the user reported that v0.7.7 works after
the intent-carried screenshot grant fix: a Sidebar tap captures the screen and
opens its translation in Lens. Device: OnePlus PYB110, Android 17, Google app
`17.64.15.ve.arm64` (versionCode 301817218). Version 0.8 retains that handoff.

The individual lifecycle and cross-version checks below remain **UNRUN** unless
explicitly recorded. The core-flow confirmation does not establish every consent,
rotation, stop/restart, offline, or language-preference case. Record the device,
Android version, build, Google app version, observed result, and evidence for
each additional check.

## API 30 and API 33

Run every check on at least one Android 11/API 30 device and one Android 13/API
33 device.

| Check | Expected result | Status |
| --- | --- | --- |
| Approve consent from a Sidebar launch | Android grants sharing, the session notification appears, and the pending capture opens in Lens. | UNRUN |
| Deny or cancel consent from a Sidebar launch | The app returns to usable settings with no capture or Lens launch. | UNRUN |
| Inspect OEM sharing choices | Document whether the device offers full-screen only or an OEM/app-selection choice; choose the full screen when offered and record the result. | UNRUN |
| Capture a static target screen repeatedly | Several Sidebar taps during one live session create fresh captures without another consent prompt. | UNRUN |
| Verify Lens window presentation | Cold and warm Sidebar captures open the image in visibly full-screen Lens, including when Lens was previously floating in recents. See `2026-10-16-lens-floating-window-findings.md`. | UNRUN |
| Verify saved PNG content | **Critical:** the saved PNG shows the underlying target screen, not Screen Translator settings, the consent UI, or a translucent launcher window. | UNRUN |
| Stop from the notification | Tap **Stop sharing** in the ongoing notification; sharing ends and the next Sidebar capture needs fresh consent. | UNRUN |
| Stop from system sharing controls | Stop sharing through Android's system UI; the session ends and the next capture needs fresh consent. | UNRUN |
| Lock or revoke sharing | Lock the device and, where available, revoke sharing; confirm the session ends and fresh consent is required. | UNRUN |
| Rotate during a live session | Rotate the device, then capture again; the PNG has the current geometry and the session remains usable or reports a concise failure. | UNRUN |
| Kill and relaunch the app process | End the process and relaunch the app. Attempt another Sidebar capture and verify Android shows a fresh consent prompt; no sharing session or consent token is restored. | UNRUN |
| Test without Google Lens | Disable/uninstall the Google app or otherwise make Lens unavailable; the app reports a concise launch failure and stays usable. | UNRUN |
| Tap Sidebar while settings already exists | With an existing settings task, tap Sidebar and verify ColorOS routes the new intent to capture rather than only foregrounding stale settings. | UNRUN |

## API 34 and Later

Run every check above on an Android 14/API 34 or later device, plus the
version-specific checks below.

| Check | Expected result | Status |
| --- | --- | --- |
| Approve default-display consent | The Android 14+ consent flow accepts the request and starts the foreground sharing session. | UNRUN |
| Inspect full-screen versus app/OEM selection | Record the choices Android/OEM UI presents. Select full-screen/default-display capture when offered and confirm the underlying target screen is captured. | UNRUN |
| Repeat captures after selection | A single approved session supports repeated fresh captures without reusing a token to create another session or showing another prompt. | UNRUN |
| Rotate or change captured-content size | Capture after rotation/size change and confirm the PNG reflects the current target screen without stale dimensions. | UNRUN |
| Stop, revoke, lock, and process death | Exercise notification stop, system stop, lock/revocation, and process kill separately; each requires fresh consent before a later capture. | UNRUN |
| Sidebar launch with existing settings | Confirm the `from_package=com.coloros.smartsidebar` launch still reaches capture when the singleTask settings root already exists. | UNRUN |

## Evidence To Attach

- Screenshot or video of the consent flow and sharing notification.
- The saved capture PNG for the critical target-screen check.
- Device model, OEM build, Android API level, Screen Translator build, and Google
  app version.
- Notes on any OEM capture-selection UI, failure, or deviation from the expected
  result.
