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

## v0.7.4 device failure — eligibility gate

The follow-up report `bugreport-PYB110-CP2A.260605.016-2026-10-17-08-36-28.txt`
records the failed direct request on the user's PYB110 phone:

- Lines 41277 and 41376: Android accepts both the relay launch and the inner
  `ACTION_VIEW google://lens/...` launch, each with activity-start result code 0.
  This is an Android launch result, not a Lens eligibility/result code.
- Line 41385: the relay's configuration reports `mWindowingMode=fullscreen`.
- Line 44040: the installed Google app is reported as versionCode **301817218**.
- Lines 44156–44161, at `08:36:22.577–.578`: Google logs `Checking for device
  compliance...`, then `(REDACTED) Lens eligibility error: %s` from `dsyy`, and
  immediately finishes `LensExportedActivity` through `dsyx.a`.

This establishes failure in Google's eligibility path after a successful Android
launch. The detail substituted for `%s` is redacted. The report does not establish
the numeric Lens result or distinguish an unapproved caller from other
eligibility conditions. In particular, the adjacent device-compliance message
alone does not establish a device-compliance failure.

An independent newer-build investigation describes the eligibility decision as
checking the activity's framework calling package, Google configuration/package
sets, and OEM camera/image integration features/properties. It also describes
locale, incompatible-device, and locked-device outcomes. Source:

<https://github.com/TakeKazeX/HyperTweak/blob/0d4caf33d356dc0203bd4d2f576b327837b7c0f6/app/src/main/kotlin/com/takekazex/hypertweak/hook/rules/googleapp/GoogleAppLensEntryHooker.kt>

That source discusses other Google versions, so its result mapping is supporting
evidence, not a verified mapping for versionCode 301817218. Its workaround uses
in-process Xposed hooks; it is not an ordinary-app intent capability.

The locally available `velvet.apk` is Google **13.17.16.23.x86_64**, versionCode
**301194889**, as reported by Android SDK `aapt dump badging`. It is not the
installed build and must not be used to claim the exact `dsyy`/`dsyx` decision has
been traced. Obtain the Google app's base APK and, preferably, its split APKs from
the failing phone before continuing exact-build analysis. The exported APK and
raw bug report belong outside the repository.

No supported intent extra or manifest permission that satisfies this eligibility
gate has been established. Caller allowlisting is the leading hypothesis, not a
confirmed exact subcondition. There is no evidence here for another intent-flag
release fixing v0.7.4.

## Exact Google build analysis — 17.64.15.ve.arm64

The subsequent user-supplied `Googlev17.64.15.ve.arm64.apks` contains `base.apk`
and six splits, including the Lens on-device and TranslateKit modules. Android
SDK `aapt dump badging` identifies the base as
`com.google.android.googlequicksearchbox`, versionName **17.64.15.ve.arm64**,
versionCode **301817218**, matching the failing report. Base APK SHA-256:
`3a20044f40f1b85f788a70674081fb9f316028d40e5faa799fcbc33a5e983c03`.
The older local APK limitation above is therefore resolved for this analysis.

JADX single-class analysis recovered the relevant classes from the supplied base:

- `dsyy.a(String)`: an empty caller returns activity result **3**. Otherwise it
  invokes `dsyc.b(caller)`. Ready returns **-1**; unsupported locale,
  incompatible device, and locked device have separate result paths. The
  fallback logs the exact `Lens eligibility error: %s` string seen in the report
  and returns **1**.
- `dsyc.b(String)`: the actual decision used here returns only `LENS_READY` or
  `LENS_UNAVAILABLE`. It accepts a Google-supplied global boolean override;
  otherwise it rejects an empty caller or membership in its `f` package set,
  accepts membership in its `e` package set, and otherwise checks OEM admission.
  These are respectively a denylist and allowlist, not two allowlists.
- The OEM branch checks the system feature/property pairs in `dsyg.a`:
  `com.google.lens.feature.CAMERA_INTEGRATION` with
  `ro.com.google.lens.oem_camera_package`, or
  `com.google.lens.feature.IMAGE_INTEGRATION` with
  `ro.com.google.lens.oem_image_package`. `dsyf.a` splits the property on commas,
  trims entries, and compares them with the caller package. A matching caller
  must also pass `PackageManager.getApplicationInfo(caller, 0x100000)`.
- `dsyx.a(...)` passes `dskg.c` (identified as `callerPackage` by its constructor)
  to `dsyy.a`, sets the resulting activity result, and finishes unless it is
  **-1**. This corroborates the report's `dsyx.a` finish stack.

The default structured decompile of `dsyc.b` loses essential loop exits and
produces misleading control flow. Its **fallback instruction output** was checked
to establish the branches: `L26` is unavailable, `Lbb` is ready, a matching OEM
package reaches `Lbb` only after successful package lookup, and exhausted OEM
entries reach `L26`. Conclusions above use that output rather than the broken
structured reconstruction.

This exact-build path establishes caller admission as the failure category and
the resulting Lens activity result as **1**, inferred from the matched code and
observed log. It does not reveal the live Google configuration sets, so explicit
denylisting and absence from the allowlist/OEM declarations cannot be
distinguished. The earlier locale/device/lock alternatives are not outcomes of
the `dsyc.b` implementation used by this build's direct-intent eligibility path.
Neither screenshot URI access nor translation language settings are checked in
that decision method.

No ordinary-app intent parameter or manifest permission controls these admission
inputs. Copying the OEM request format alone therefore cannot make Screen
Translator eligible on the tested configuration. This is not evidence that every
other Google entry point has the same gate; the previously working share entry
point remains a distinct flow and did not establish forced translation mode.
No application behavior change or new APK release was made from this analysis.

## v0.7.5 route — explicit LensExportedActivity request (failed; corrected below)

Further exact-build analysis found a second, un-gated request path in the same
base APK:

- `dufz.a(Intent)` maps the launch by component, flags, and data URI. The
  `google://lens` data URI launched with only `setPackage` resolved in the
  v0.7.4 report to the `com.google.android.apps.lens.MainActivity` alias, which
  unconditionally maps to integration mode **5**. The same request addressed
  explicitly to `com.google.android.apps.search.lens.LensExportedActivity` with
  no data URI and no `FLAG_ACTIVITY_NEW_TASK` maps to integration mode **2**.
- `cthc.<init>`, the delegate installed for `LensExportedActivity`, is the only
  writer of the `ctgy.b` gate boolean (verified by a dex field-write scan). The
  `dsyx.a` eligibility gate requires that boolean **and** mode 5, so mode-2
  launches never reach `dsyy`'s caller-admission decision.
- Mode 2 parses intent extras: `dufz.b` reads the image from
  `injected_image_uri` (or ClipData/`injected_image_uri_string`) and builds the
  external post-capture image, while the mode-2 config block calls
  `ctib.d(extras)`, which recognizes `open_filter=translate` plus optional
  `source_language`/`target_language` and sets the translate filter in the Lens
  initial parameters. `ctib.c(extras)` reads `caller_package`.
- The exported `LensShareEntryPointActivity` is not usable for forced
  translation: its `onCreate` validates `ACTION_SEND`+`EXTRA_STREAM` and builds
  fresh internal parameters; it forwards neither the image extras nor any
  filter.

v0.7.5 therefore changes `translateRequest` to address the exported Lens
component explicitly with `injected_image_uri`, `open_filter=translate`, the
relay's real `caller_package`, no data URI, and no new-task flag, still started
for a result with the temporary URI grant. Language overrides stay unset.
Whether this route visibly translates, reuses Google Translate preferences or
offline packs, and preserves fullscreen presentation still requires
physical-device verification; a server-side or downstream check not visible in
static analysis remains possible.

## v0.7.5 crash — correction to launch-mode analysis

The report `bugreport-PYB110-CP2A.260605.016-2026-10-17-10-30-58.txt`
supersedes the launch-mode claim above. At 10:30:50.274 (line 40183), Android
accepts our explicit Lens request for result and inherits fullscreen. At
10:30:50.357 (lines 40401–40427), Google's `:googleapp` process crashes during
`LensExportedActivity.onCreate`:

```text
Caused by: java.lang.IllegalStateException: Launch mode only supported for shortcuts.
    at dufz.a(PG:247)
    at dufz.b(PG:4)
    at ctgj.onCreate(PG:125)
```

The same exception appears repeatedly at 10:29:09, 10:29:16, 10:29:40, and
10:29:44. This is a Google process crash before image/filter parsing, not an
observed screenshot decode failure or another eligibility-result rejection.

JADX's **fallback** output for `dufz.a`, checked against this crash, shows that
the structured decompile used for v0.7.5 reversed the relevant branch:

- Both `LensExportedActivity` and `DummyExportedActivity` enter label `Lb2`.
- The tested bit is **0x00100000**, Android's
  `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`. It is **not** `FLAG_ACTIVITY_NEW_TASK`
  (**0x10000000**). These constants were verified using SDK 36 `javap -constants`.
- If the history bit is absent, label `Ldc` requires an extra named
  `LensHomescreenShortcut` and exactly **one** extra in total; otherwise label
  `Lf2` throws the observed exception. Adding that shortcut extra alongside our
  image and filter would still fail the extras-count check.
- If the history bit is present, no data URI returns mode **2**. A data URI
  with scheme/host `google://lens` or `google://lensient` returns mode **5**;
  other scheme/host combinations return mode **2**.

Therefore the v0.7.5 assertion that an explicit component with no data and no
flags immediately enters mode 2 was incorrect. The actual crash is fully
explained by the missing history bit and the non-shortcut extras. The image and
filter parsers exist, but v0.7.5 never reaches them.

A possible narrowly scoped follow-up experiment is the explicit component with
the existing image/filter extras, no data URI, and the history bit set, still
without `NEW_TASK` on the inner request. This is a private launch convention
normally used for history launches, not a documented general image-translation
API. Whether Android preserves that bit and whether Lens completes the remaining
flow would require physical-device validation.

v0.7.6 implements exactly that experiment: `translateRequest` now adds
`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` to the unchanged v0.7.5 request. Whether
Android preserves the bit across the relay's for-result start, and whether Lens
then completes the image/filter flow, requires physical-device validation.

## v0.7.6 result — Lens opened but was denied the screenshot

The report `bugreport-PYB110-CP2A.260605.016-2026-10-17-11-02-06.txt` records
the v0.7.6 run at 11:01:41:

- Line 39487: the relay's request now carries `flg=0x100000`; Android preserved
  the history bit and accepted the for-result start. The shortcut-mode crash is
  gone.
- Lines 39709–39740: `LensExportedActivity` is created, started, and resumed —
  the frame the user saw.
- Lines 39746–39747, at 11:01:41.547: `ContentProviderHelper: Permission Denial:
  opening provider androidx.core.content.FileProvider
  from ...com.google.android.googlequicksearchbox:googleapp (uid=10466) that is
  not exported from UID 10493`. Lens tried to read the screenshot and the relay's
  context-API `grantUriPermission` to the Google app package did not cover the
  read.
- Lines 57033 and 57048–57051: Lens finishes itself (`app-request`), the relay
  receives the canceled activity result, and the flow ends. This is a read-grant
  failure, not a repeat of the eligibility gate or the shortcut crash.

For comparison, the user-verified working share flow in the 2026-10-16 report
launched with `flg=0x10000001` — `FLAG_GRANT_READ_URI_PERMISSION` plus
`FLAG_ACTIVITY_NEW_TASK` — and a ClipData carrying the screenshot URI
(line 65066). The system delivered that grant with the intent, and Lens read the
provider on the same phone.

v0.7.7 therefore adds `FLAG_GRANT_READ_URI_PERMISSION` and a ClipData holding
the screenshot URI to the translate request, replicating the proven grant
mechanism; the relay's context-API grant remains as a redundant path. Whether
Lens then completes the image and translate-filter flow still requires
physical-device verification.
