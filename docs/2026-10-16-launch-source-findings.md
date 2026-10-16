# PYB110 launch-source report findings

Input: `bugreport-PYB110-CP2A.260605.016-2026-10-16-10-44-03 (1).zip`.
Only app-specific findings are recorded here; the system report is not included.

## Confirmed

- The installed app is `0.5.1-launch-probe`, versionCode 7, with DEBUGGABLE set.
- The main bugreport text contains no `ScreenTranslatorLaunch` entries. The
  reason those entries are absent is not established by this report.
- Three relevant ActivityTaskManager START records are present:

| Device local time | Caller | Intent observations |
| --- | --- | --- |
| 10:43:36.824 | `com.microsoft.launcher` | MAIN/LAUNCHER, flags `0x10200000`, icon bounds `[20,749][328,1052]`, result 0 |
| 10:43:41.867 | `com.microsoft.launcher` | MAIN/LAUNCHER, flags `0x10200000`, icon bounds `[0,444][302,807]`, result 0 |
| 10:43:45.131 | `com.coloros.smartsidebar` | MAIN/LAUNCHER, flags `0x10200000`, explicit package, extras present, Oplus flags `oflg=0x800`, result 2 |

These are text lines 53566, 54306, and 55057 in the main bugreport entry.
The two Microsoft Launcher records are not independently labeled as home or
drawer by these START lines.

The sidebar request also enters Oplus floating-window handling. It brings the
existing SetupActivity task forward, rather than showing a fresh trampoline
creation in this sequence. A future probe must account for task reuse rather
than assume every sidebar tap reaches TrampolineActivity.onCreate.

The saved enabled-accessibility-services setting is null at report time, and
the accessibility package monitor records a Force stop at 10:42:51.720556. This
explains why setup, rather than a connected capture service, was involved during
this test.

## What this does not establish

The system can distinguish the callers, but its log output does not prove that
Activity.callingPackage or referrer exposes that identity to the target app.
Ordinary activity launches frequently do not expose callingPackage.

The report says extras exist but does not provide the incoming trampoline
extra keys and values. `oflg` is an Oplus extension, not the ordinary
Intent.flags bitmask: both entry types have the same ordinary flags here.
Icon bounds, explicit package, and floating-window state alone are not a
validated general-purpose source classifier.

## Recommended next probe

Use a temporary on-device launch report page with Copy/Share actions and bounded
local history, rather than depending on system log retention. Record the public
intent fields and extra keys/values on every fresh launch and new-intent
delivery, and avoid immediately finishing the probe activity so task reuse is
observable. Record any setup task reactivation separately; it must not be
misrepresented as a newly received launch intent.

Compare repeated sidebar, home, and drawer launches before selecting a routing
signal. The eventual settings entry must stay open even when accessibility
connects; only an explicit pending translation should auto-dispatch capture.

## Outcome

The v0.5.2 on-device probe confirmed the routing signal: sidebar launches carry
`extra[from_package]=com.coloros.smartsidebar`, while home-screen and
app-drawer launches carry no extras. Caller/referrer fields are unavailable on
this device. v0.6.0 routes launches carrying the marker to capture and
translation, and all other launches to a persistent settings page.

## v0.6.0 task-reuse regression

The user reported that sidebar taps still opened settings. v0.6.0 had restored
the old finish-immediately launcher and separate SetupActivity, dropping the
retained singleTask/onNewIntent topology used by the successful probe. Earlier
system-report evidence already showed ColorOS bringing an existing settings
task forward without creating a new launcher instance.

v0.6.1 keeps the stable TrampolineActivity component as a singleTask root and
hosts settings in that activity. It routes both cold launches and onNewIntent
deliveries, replaces or cancels pending capture state for each incoming launch,
and defers restored pending captures until resume so newer icon intents can
cancel them. Regression tests cover these lifecycle paths; OEM-device validation
of the updated release remains necessary.
