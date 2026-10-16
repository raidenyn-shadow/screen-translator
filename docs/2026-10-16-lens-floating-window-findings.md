# ColorOS Lens floating-window handoff

## Evidence

Recovered the main text entry from the partial upload of
`bugreport-PYB110-CP2A.260605.016-2026-10-16-21-33-02.txt`.
The recovered prefix includes the launch at 21:32:52; the archive and main text
are incomplete. Raw device reports are not included in the repository.

The relevant sequence is:

- `21:32:52.255`: `FlexibleTaskShareManager` reports `source already in flexible
  window`. `findSourceTaskForFreeForm` identifies Screen Translator's
  `CaptureActivity` in task 678 as the source.
- ColorOS supplies flexible bounds and links task 678 to the new
  `LensShareEntryPointActivity` task 679.
- `21:32:52.305`: the share entry starts `LensActivity`, reusing task 663.
  ColorOS identifies task 679 as its source and explicitly preserves the source
  task's position and scale.
- `TaskLaunchParamsModifier` describes the standard window mode as fullscreen
  while also applying `flexiblewindow-activity-options-bounds`. Standard
  `WINDOWING_MODE_FULLSCREEN` does not exclude ColorOS's flexible presentation.

## Targeted change

Launch Lens through `applicationContext.startActivity(intent, options)` while
`CaptureActivity` is still resumed. Android's application-context launch omits
the source activity token; this targets the source-task link seen in the report.
Keep `FLAG_ACTIVITY_NEW_TASK`, the fullscreen options, and URI read grants.
Finish and acknowledge the capture using the existing lifecycle handling.

This is a hypothesis supported by the observed task linkage, not a demonstrated
ColorOS fix. ColorOS could still infer a floating source from process or task
state. The regression test verifies application-context dispatch and the real
capture/image handoff; Robolectric cannot verify OEM task policy.

## v0.7.2 device outcome — failed

The user reported unchanged floating presentation. The complete new archive,
`bugreport-PYB110-CP2A.260605.016-2026-10-16-22-35-03.zip`, includes the
following launch records in its main text:

- Line 65080, `22:34:44.340`: `findSourceTaskForFreeForm` now reports
  `sourceRecord = null`, `realCallingPid:15044`, and Screen Translator task 700
  as `prevTopTask`. The intended activity-token omission is observable.
- Line 65086: ColorOS still applies the flexible bounds with
  `SourceFlexibleTaskId = 700`.
- Lines 65098–65099: ColorOS links source task 700 to the newly created Lens
  share entry task 701. This happens before the internal Lens task is reused,
  so existing Lens-task state is not the sole explanation.
- Lines 65305–65327: Lens's internal launch inherits source task 701's position
  and scale, reuses Lens task 663, and links those tasks.

The application-context workaround did not prevent ColorOS from selecting a
floating source task. The log exposes both the caller PID and previous top task;
it does not establish which fallback branch selected task 700. Do not treat a
null source activity as proof that an OEM launch is detached from floating tasks.

## v0.6.1 working baseline and v0.7.3 handoff

The user identified v0.6.1 as the last version that opened Lens full-screen.
At commit `c863fb3`, `SetupActivity.captureIfReady()` dispatched capture and
immediately finished the Sidebar activity. The Accessibility service later
launched Lens. The Lens component and image-sharing flags were already the same
as those used before the v0.7.1 fullscreen-options change.

v0.7.0 introduced a capture activity that stayed alive through the Lens launch.
The v0.7.3 change restores the relevant ordering: close the source task, then
launch Lens from the service. After the PNG is saved, `CaptureActivity` finds
only its own task by ID and calls `ActivityManager.AppTask.finishAndRemoveTask()`
before calling the running service's `startActivity`. This removes the root as
well as the capture child; finishing only the child would leave the same source
task available for ColorOS's fallback. MediaProjection and request ownership
remain in place until the screenshot has been saved.

The public AppTask operation removes the task without requesting process death
in AOSP's `AppTaskImpl.finishAndRemoveTask`. No arbitrary delay is added between
task removal and the service launch. Missing, disappeared, or inaccessible tasks
produce the existing launch-failure result instead of launching from an intact
floating task. Screenshot URI grants and the fullscreen request are preserved.

Tests verify task removal before dispatch, selection of only the capture task,
image sharing, and failure when the capture task is unavailable. Physical
verification is still pending: the former Accessibility service and the current
MediaProjection service do not have identical background-launch privileges.
The actual task removal, ColorOS fallback behavior, and Android launch admission
must all be checked on the device.

## Further physical verification — v0.7.3 UNRUN

Test cold and warm Sidebar launches, first-consent and active-session captures,
and Lens already present in recents (both floating and maximized). Check that the
screenshot loads and Lens is visibly full-screen. If it still floats, collect the
same launch sequence and check that the capture task was removed before the
Lens START record, whether ColorOS still supplies a source task, and whether
Android admits or blocks the service's launch.
