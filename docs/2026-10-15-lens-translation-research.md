# Opening Google Lens in translation mode

Research date: 2026-10-15.

## Result

**Use Google Translate's camera deep link to enter translation-only Lens:**

```text
googletranslate:///open/camera?sl=auto&tl=en
```

Address the intent to `com.google.android.apps.translate`, not to the Google app.
Translate constructs its own translation-specific Lens SDK request and launches
Lens as the actual calling app. This avoids depending on Lens interpreting
invented extras such as `mode=translate` on an image-share intent.

**Verification level:** confirmed in the manifest and code of a current,
signature-verified Google Translate APK, then confirmed by the user on the target
phone using I1 in release `v0.3.5-probe`: “Opens the camera with translation already
selected.” The agent has no ADB connection; this runtime evidence is the user's
manual test, not an agent-operated device test.

### Confirmed device configuration

| Item | Reported value |
| --- | --- |
| Phone | OnePlus PYB110 |
| Android | 17, API 37 |
| Google app | `17.64.15.ve.arm64` (`301817218`) |
| Google Translate | `10.38.67.991942559.4-release` (`701034682`) |
| Standalone Lens | `1.18.250731009` (`250731009`) |

The user also reported that H2 returned to our app, H4 did nothing, and the other
H routes opened Lens but needed another tap to select translation. No H2 numeric
result code was supplied, so caller eligibility remains undetermined. The package
dump was partial; the configuration above was present in the supplied portion.
The user subsequently confirmed that selecting a screenshot from the gallery
inside I1 immediately translates it and produces exactly the desired view.
This validates the manual translation-camera → gallery → screenshot path on this
configuration. Automatic screenshot handoff and repeated/cold launches remain
unverified.

**Important scope:** this opens the translation **camera**, not a supplied
screenshot. It answers how to open Lens focused on translation. It does not yet
solve the project's stronger goal of automatically translating a captured screen
with no further interaction.

## Exact launch

From an Android context:

```kotlin
val intent = Intent(
    Intent.ACTION_VIEW,
    Uri.parse("googletranslate:///open/camera?sl=auto&tl=en")
).setPackage("com.google.android.apps.translate")
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

try {
    context.startActivity(intent)
} catch (e: ActivityNotFoundException) {
    // Google Translate is missing or this installed version lacks the route.
}
```

`sl=auto` requests source-language detection; `tl=en` requests English. Replace
`en` with the desired supported target-language code. The diagnostic uses English
as a fixed reference, not as a new production language preference.

Device-side reproduction:

```bash
adb shell am start -W \
  -a android.intent.action.VIEW \
  -d 'googletranslate:///open/camera?sl=auto&tl=en' \
  -p com.google.android.apps.translate
```

Google Translate and the Google app must be installed and set up. First-use
onboarding or camera permissions can intervene. Translate itself checks Lens
availability and may request a Google app update.

### Observable acceptance criteria

1. Finish Translate/Google app setup once.
2. Run the command or use **I1** in the app's diagnostic screen.
3. Verify that the visible UI is the translation camera, with language controls,
   rather than general visual search or descriptions.
4. Point it at legible foreign text and verify that translated text appears.
5. Repeat after closing Lens. `am start` reporting success is not evidence that
   translation was selected or completed.

For an existing screenshot, the supported manual route is Translate → Camera →
gallery/import → select the screenshot. Google documents image import in its
[Translate images help](https://support.google.com/translate/answer/6142483?hl=en&co=GENIE.Platform%3DAndroid).

## Evidence from Google Translate

Analyzed artifact:

- Package: `com.google.android.apps.translate`
- Version: `10.39.34.995726622.4-release`
- Version code advertised by the distributor: `701046284`
- Architecture: x86-64; used for static analysis only
- APK SHA-256:
  `79c4e6086cb180b7ae79a7ce619a8cacad5d0feb784b8fceac67b194a56984cc`
- APK signature verified with Android SDK `apksigner verify --print-certs`.
- Signer certificate SHA-256:
  `3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a`
- [Distributor metadata](https://ws75.aptoide.com/api/7/app/get/package_name=com.google.android.apps.translate)
- [Analyzed APK](https://pool.apk.aptoide.com/appupdater/com-google-android-apps-translate-701046284-77037028-cf731e1f0462ed7b175e7994bd180121.apk)

JADX 1.5.6 exposed these relevant paths (obfuscated symbols are specific to this
artifact):

| Location | Observation |
| --- | --- |
| `AndroidManifest.xml`, `TranslateActivity` | Exported; handles `VIEW` with scheme `googletranslate` and path prefix `/open` or `/with_inputmethod`. |
| `TranslateActivity`, camera shortcuts | The app itself creates `googletranslate:///open/camera`. |
| `defpackage.ojp.X(Uri)` | Reads `inputmethod`, or the second path segment after `open` / `with_inputmethod`. |
| `defpackage.ixg` and `efq.p` | Maps `camera`, `1`, `wordlens`, and `4` to the camera input method. |
| `defpackage.ojp.as(Uri)` | Reads `sl` / `source` and `tl` / `target`; defaults the source to `auto`. |
| `defpackage.ipf` (`CameraLauncher`) | Constructs translation parameters from the two languages, sets entry point `53`, and launches Lens through an Activity Result launcher. |
| `defpackage.qyl.ce` | Creates `ACTION_VIEW google://lens`, attaches a **Bundle** under `lens_activity_params`, and addresses the Google app (or a feature-flag-selected Lens standalone package). |

Inside that SDK bundle, `CameraLauncher` supplies `caller_package`, `entry_point`,
and protobuf bytes under `lens_init_params`, plus timing and UI options. The
translation filter is represented by a structured protobuf oneof, not a string
called `mode`.

This explains why using Translate as the launcher is preferable to trying to
imitate its private SDK from our package: the real Translate activity remains the
caller, and it supplies the version-appropriate parameters.

The camera launcher takes context, source language, target language, and a launch
origin. No image argument is present in that inspected path. Translate's manifest
registers `ACTION_SEND` for **text/plain**, not `image/*`. Attaching our PNG as
`EXTRA_STREAM` is therefore not an established screenshot-import contract.

## Why the existing share probes do not select translation reliably

The repository's earlier probes tried top-level extras including `mode`,
`lens_mode`, `entry_point`, `fullscreen`, and `expanded`. These names do not match
the structured Translate SDK request above. A resolved activity or visible Lens
window alone does not demonstrate that an extra was consumed.

For comparison, a separate, older Google app APK was inspected:

- Package/version: `com.google.android.googlequicksearchbox`,
  `13.17.16.23.x86_64`.
- APK SHA-256:
  `0d5224f7690c674061f2324ec79903874389b238caf378c0057629b1b5619991`.
- [Source of the compressed APK](https://github.com/LeafOS-Blobs/proprietary_vendor_gapps/blob/leaf-4.0/prebuilt/x86_64/Velvet.apk.xz.001).

In that artifact, `LensShareEntryPointActivity.onCreate` checks `ACTION_SEND`, an
`image/` MIME type, and `EXTRA_STREAM`. It then builds a **new** internal Lens
parameter bundle containing the image URI and `GOOGLE_APP_SHARE_SHEET` origin.
That method does not forward arbitrary incoming extras or select a translation
filter. This is direct evidence for that older implementation, not proof that
every current Google app version has identical code.

Current independent projects also use this component for ordinary image search:

- [vamsi3/screen-translator target definition](https://github.com/vamsi3/screen-translator/blob/master/core/model/src/main/java/com/vamsi3/android/screentranslator/core/data/model/TranslateApp.kt)
- [circle-to-claude design](https://github.com/JayDoubleu/circle-to-claude/blob/main/DESIGN.md)

Neither reference establishes a guaranteed translation-mode image-share contract.

## A flaw in the existing caller-eligibility experiment

The previous **H2** probe used an Activity Result launcher but also set
`FLAG_ACTIVITY_NEW_TASK`. Android's
[Intent documentation](https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_NEW_TASK)
states that this flag cannot be used when the caller requests a result. The
[Activity documentation](https://developer.android.com/reference/android/app/Activity#startActivityForResult(android.content.Intent,int))
explains that launching into a new task immediately returns a canceled result.

This invalidates H2 as a clean test of Lens's requirement for a calling package.
The updated probe removes that flag. It also catches launch errors rather than
crashing the diagnostic screen.

Retest H2 before drawing a device-specific conclusion about the partner allowlist:

- `0`: canceled/inconclusive; not proof of caller eligibility.
- `3`: empty caller in the documented decompiled SDK implementation.
- `1`: unavailable; a caller restriction is one possible explanation.
- Other results and visible behavior must be interpreted against the installed
  Google app version.

An independent implementation documents actual caller and partner-eligibility
gates on Google app 17.x:
[HyperTweak's Lens entry investigation](https://github.com/TakeKazeX/HyperTweak/blob/main/app/src/main/kotlin/com/takekazex/hypertweak/hook/rules/googleapp/GoogleAppLensEntryHooker.kt).
It uses in-process Xposed hooks to change those decisions; it is not a normal-app
intent solution. Supplying `caller_package` as an extra does not establish the
framework calling identity.

## Recommendation for this project

1. **To open Lens focused on translation:** use the Translate camera deep link.
   The diagnostic exposes this as **I1**, without requiring screen capture.
2. **To translate the captured screen automatically:** no single ordinary-app
   intent has been verified here to both preload the image and force translation.
   Re-run the corrected H2 on the actual phone and inspect the installed Google
   app's exact entry-point code before adding another private SDK experiment.
3. **For screenshot translation today:** the established flow is an image share
   followed by selecting Translate, or importing the image through Translate's
   translation-only camera. Those still require interaction.

### Approved follow-up: one-shot accessibility prototype

After confirming the manual import result, the user approved sharing the
screenshot and automatically selecting the visible Translate control via
accessibility. The J1 diagnostic implements this experiment, extending the prior
screenshot-only service with UI-retrieval capability. It is not a newly discovered
image-to-translation intent, and runtime success on the phone is still pending.

J1 arms inspection only after its screenshot has been saved and immediately before
launching the known image-share component. It polls the active window every 200 ms
for at most 10 seconds. Only the Google app's controls are searched. Matching
requires an exact English Translate caption or accessibility description and a
button role or tab/chip/filter resource ID. Hidden, disabled, partial-label, plain
OCR TextView, and ambiguous matches are not clicked. An already-selected Translate
control stops the attempt without toggling it.

The attempt disarms before its single ACTION_CLICK, even if the action is rejected
or throws. It also stops when leaving the Google app after observing it, on
service interruption/destruction, or timeout. Passive accessibility events do not
start attempts. The ordinary sidebar capture pipeline does not arm J1.

The service capability change requires re-enabling the service after updating the
APK. Setup and the system accessibility description now disclose UI interaction.
The report persists across activity recreation and is included in the component
dump. It includes outcome and up to 16 visible clickable control summaries (class,
resource ID, bounded caption, selected state), not a full UI-tree dump.

The device acceptance check is the visible translated screenshot. An accepted
accessibility click alone is not proof of translation completion. If J1 times out,
the report distinguishes inaccessible UI/no controls from available but unmatched
or ambiguous controls; the next change should be based on that evidence.

Do not promote the camera route into `LensLauncher.launch(uri)`: it would ignore
the supplied screenshot while appearing to satisfy the capture pipeline.

## Reproducing the static investigation

Keep downloaded APKs and decompiled sources outside the repository. With JDK 21,
Android SDK build tools, and [JADX 1.5.6](https://github.com/skylot/jadx/releases/tag/v1.5.6):

```bash
apksigner verify --print-certs google-translate.apk
sha256sum google-translate.apk
jadx --no-src -d translate-res google-translate.apk
jadx --no-res --show-bad-code -d translate-src google-translate.apk
```

Inspect the named methods and manifest entries above. JADX reported errors in
some classes during the full decompilation; do not trust reconstructed control
flow blindly. The route is additionally corroborated by Translate's own literal
camera shortcuts and manifest, while the bundle construction provides the
translation-specific launch evidence.

The generic web Lens `uploadbyurl` route was also checked, but the direct request
returned HTTP 404 in this environment. Other search fetches produced JavaScript
challenge pages. No web route is presented as an experimentally working fallback.
