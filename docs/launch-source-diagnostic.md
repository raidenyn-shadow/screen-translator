# Temporary launch-source diagnostic

**Status: concluded.** The v0.5.2 on-device probe found the ColorOS marker:
sidebar launches carry `extra[from_package]=com.coloros.smartsidebar`, while
home-screen and app-drawer launches carry no extras. See
`docs/2026-10-16-launch-source-findings.md` for the evidence. Production launch
routing shipped in v0.6.0; the diagnostic builds were retired. The sections
below document the retired probes.

## v0.5.2: on-device report (recommended)

Install the `0.5.2-launch-probe` APK from GitHub Releases over the existing app.
This debug build displays a report instead of starting translation; no adb,
accessibility grant, or system bug report is needed to collect launch metadata.

1. Open the app and tap **Clear** to remove earlier probe entries.
2. Go home, open the app from **Smart Sidebar**, and tap **Sidebar** to label the
   latest incoming launch.
3. Go home, open the **home-screen icon**, and tap **Home**.
4. Go home, open the app from the **app drawer**, and tap **Drawer**.
5. Repeat steps 2–4 once, then tap **Share report** or **Copy report** and send
   the text. Source labels are your annotations, not automatic detection.

The report retains the latest 12 incoming launches across activity recreation
and app restarts. Merely resuming the report is not counted as a new launch.
Check that a new `BEGIN launch` entry appears after each test; if it does not,
report that instead of relabeling a previous entry as a new launch.

The debug manifest uses a retained `singleTask` entry activity to capture
`onNewIntent` deliveries. This intentionally differs from the normal transparent,
immediately finishing capture entry. Caller fields can still be null or
unavailable when the source does not share its identity. The raw Intent string
is included to preserve OEM hints as well as the public intent fields. Reports
stay in app-private preferences until cleared or explicitly copied/shared.

The sections below describe the older v0.5.1 logcat-only probe.

Goal: determine whether this phone supplies a consistent distinction between a
Smart Sidebar launch and launches from the home screen or app drawer. Android
does not define a universal sidebar-launch flag. Do not implement routing based
on an assumed flag or caller package before comparing actual device evidence.

The debug APK logs each launch of `TrampolineActivity` using the tag
`ScreenTranslatorLaunch`. It records the intent action, categories, flags,
component, data, MIME type, source bounds, extras, referrer/caller hints, and task
and multi-window state. The release APK does not emit this diagnostic. Existing
capture behavior is preserved. These are temporary logs, not a source detector.

## Capture on a connected phone

1. Build with `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:assembleDebug`.
2. Install `app/build/outputs/apk/debug/app-debug.apk` using
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`. This requires the
   existing installation to have a compatible signature. If a signature mismatch
   requires reinstalling, accessibility will need to be enabled again.
3. Enable USB debugging and confirm the phone is listed by `adb devices`.
4. Start this command on the computer:

   ```bash
   adb logcat -v threadtime -s ScreenTranslatorLaunch:I '*:S'
   ```

   If adb is not on PATH, use `$ANDROID_SDK_ROOT/platform-tools/adb`.

5. Launch the app in this exact order, waiting for each capture/Lens flow to
   finish and returning to the home screen between launches:
   - Smart Sidebar
   - Home-screen icon
   - App drawer
6. Repeat the sequence once. Do not use Force stop between launches.
7. Copy the six `BEGIN launch` through `END launch` groups and send them with
   the phone model and Android/ColorOS version. Labels and timestamps help if the
   order differs. The output can include intent data and extra values; review it
   before sharing.

## Capture without adb

Install the diagnostic APK from the GitHub prerelease. Enable Developer options
on the phone, then perform the sidebar, home-screen, and app-drawer launch
sequence twice as above. Immediately afterward, use **Developer options → Take
bug report** (the wording and availability depend on the phone). Choose an
interactive report if offered and wait for the report-ready notification.

The report ZIP normally contains a bugreport text file with system logs. Extract
only the `ScreenTranslatorLaunch` lines and send those along with the launch
order, phone model, and OS version. Do not post the entire system bug report
publicly: it contains unrelated device and app information. If the phone does
not offer bug reports or omits the app logs, this collection method will not
work; report that rather than assuming the launch sources are identical.

Compare fields that remain stable across repeated launches from the same entry
point and differ between entry points. Referrers and extras are hints supplied by
the launcher, not authenticated identities. Task flags, bounds, and window mode
may vary with task history and phone settings; a difference in one run alone is
not enough to rely on. If all three sources produce indistinguishable launches,
automatic source routing is not established by this probe.
