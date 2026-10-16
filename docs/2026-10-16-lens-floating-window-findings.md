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

## Physical verification — UNRUN

Test cold and warm Sidebar launches, first-consent and active-session captures,
and Lens already present in recents (both floating and maximized). Check that the
screenshot loads and Lens is visibly full-screen. If it still floats, collect the
same launch sequence and check whether `findSourceTaskForFreeForm` still supplies
a source task despite the omitted activity token.
