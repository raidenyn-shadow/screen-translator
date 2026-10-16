# OxygenOS screenshot-translation handoff

## Required outcome

One Sidebar tap must capture the current screen and show its translation in the
installed native Google software, without gallery selection or further taps.
The user requires existing preferences and downloaded language packs, and has
neither root/LSPosed nor Shizuku. A WebView and manual import are rejected.

## New working reference

Input: the complete user-supplied
`bugreport-CPH2653-BP2A.250605.015-2026-10-17-00-12-39.zip`.
The main text entry is 156,046,628 bytes. Raw reports are kept outside the
repository. Line numbers below refer to that main text entry.

### Observed launch sequence

1. Line 70246, `00:12:28.418`: `com.oplus.screenshot`, UID 10156, starts its own
   `.translate.TranslatorDummyActivity` with extras and flags `0x10008000`.
2. Line 70382, `00:12:28.447`: that activity starts `ACTION_VIEW google://lens/...`
   in `com.google.android.googlequicksearchbox/com.google.android.apps.lens.MainActivity`.
   The launch records the dummy activity as its result recipient (`rr=78062038`).
   The standard new-task flag is absent on this second launch.
3. Lines 70356 and 70493 identify the resolved Google implementation as
   `com.google.android.apps.search.lens.LensExportedActivity`.
4. Line 72971, `00:12:34.209`: the screenshot activity logs
   `google lens return: -1`. This is an observed native result code; it alone does
   not establish that the image was visibly translated. The user supplies the
   built-in feature as the working reference.

The observed handoff targets the Google app's direct Lens API, not
`com.google.android.apps.translate`. It is distinct from Screen Translator's
ordinary `ACTION_SEND` to `LensShareEntryPointActivity`.

### OEM integration evidence

The report includes these properties at lines 290402–290403:

```text
ro.com.google.lens.oem_camera_package=com.oplus.camera
ro.com.google.lens.oem_image_package=com.oneplus.gallery,com.oplus.screenshot
```

These explicitly identify the screenshot application as an OEM image-integration
caller. This is relevant to the partner-eligibility checks documented in
`2026-10-15-lens-translation-research.md`. It is not proof that Screen Translator
is eligible for the same direct API, or that a package-name extra can confer that
eligibility.

The Google app also has installed `lens_ondevice_engine_feature_module`,
`lens_ondevice_engine_play_ml_module`, and `translatekit_feature_module` splits
(for example, line 8625). This does not prove that this route reads Google
Translate's preferences or reuses its downloaded language packs. Those behaviors
remain separate acceptance checks.

## Callable relay investigation

An independently published manifest for Screenshot **15.4.16** declares
`com.oplus.screenshot.translate.TranslatorDummyActivity` as `exported=false`.
It also marks the principal screenshot services as non-exported. Source:

<https://github.com/pepetercha/confund/blob/5beeecc356e7a03bb603c9b7672e4ce603a91b1a/manifests/Screenshot.apk.manifest.txt>

This is reference-version evidence, not a manifest dump of the exact installed
APK. The supplied report identifies the screenshot application as versionCode
160015024 (line 72914), with its APK at
`/product/priv-app/Screenshot/Screenshot.apk` (line 488129).

## Exact APK analysis — Screenshot 16.15.24

The user supplied `Screenshotv16.15.24.apk` from the working phone. Verified
package/version: `com.oplus.screenshot`, versionName `16.15.24`, versionCode
160015024. Android SDK `apksigner verify --print-certs` accepted the APK signature.

- APK SHA-256:
  `6e7b27284b147ca1fa08c0eafe70f717da4b604c42af29fda1a174ead264c2df`
- Signer certificate SHA-256:
  `b0a9bbfc05eee5e7d0a2c97c030586e15bb3301152078f54473bb82df6d8c818`
- Signer DN identifies Oplus, AndroidTeam/OSTeam.

The exact manifest also declares `TranslatorDummyActivity` as non-exported.
Its screenshot, preload, longshot, and cross-profile services and internal
dispatcher providers are likewise non-exported; the exported Epona provider
requires `com.oplus.permission.safe.SECURITY`. No directly callable image
translation relay was established from these components.

JADX single-class analysis of `TranslatorDummyActivity` exposes this request in
its `ActivityResultContract<Uri, Integer>.createIntent` implementation:

```kotlin
grantUriPermission("com.google.android.googlequicksearchbox", uri, 1)
Intent(Intent.ACTION_VIEW)
    .setData(
        Uri.parse("google://lens").buildUpon()
            .appendQueryParameter("image_uri", uri.toString())
            .appendQueryParameter("filter", "translate")
            .build()
    )
    .setPackage("com.google.android.googlequicksearchbox")
```

This is evidence for `image_uri` and `filter=translate` as **URI query parameters**
on the direct API. It is not evidence that top-level extras with those names are
accepted by the ordinary image-share entry point.

The dummy activity reads an incoming Parcelable URI under `uri` and launches
the request with Android's Activity Result API. There is no `NEW_TASK` flag on
the inner Google intent. It reads neither Google Translate's preferences nor
language-pack storage, and does not construct a Lens SDK protobuf or Binder
callback. On result, codes other than `-1` show an unavailable toast. On result
or destruction, it revokes URI permission and releases its temporary image.

JADX reported two errors during single-class processing; the request-builder,
result handler, and lifecycle bodies were emitted without an unimplemented
method marker. The decoded APK manifest and the actual bug-report launch/result
sequence independently corroborate the component, package, and result-launch
topology. Keep the APK and decompiler output outside the repository.

## Implemented native-only experiment

Screen Translator now preserves the user-verified full-screen capture-task
removal before launching a fresh, non-exported native relay activity. Its outer
start uses `NEW_TASK` because it comes from the service and requests fullscreen.
The relay starts the exact image/filter query above for a result, **without**
`NEW_TASK` on that inner intent. It retains the URI grant while waiting for Lens,
including across configuration changes, then revokes it on result or terminal
destruction. A non-success result or rejected launch shows the existing failure
message rather than treating an opened camera or ignored image as success.

This can test the exact OEM request under Screen Translator's genuine calling
identity; it does not impersonate `com.oplus.screenshot` or acquire its OEM
eligibility. The report's OEM image-package declaration means admission still
requires an actual device check. Automatic image loading, translation mode,
language persistence, offline model use, and full-screen presentation must be
verified separately. A resolved activity or accepted launch is not enough to
claim success.
