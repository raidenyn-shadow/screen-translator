# Invisible capture handoff design (v0.8.1)

## Problem

A Sidebar tap opens Screen Translator's transparent capture window in a ColorOS
floating-window frame. The frame stays visible for roughly a second — the 400 ms
capture delay, frame wait, and PNG save all run while `CaptureActivity` is
on-screen — and the screenshot itself contains the frame, because
MediaProjection mirrors the whole display while our task is visible.

Evidence: `CaptureActivity.launchTranslation` removes the capture task only
after the saved result arrives (`CaptureActivity.kt:73-90`); the capture pipeline
delays 400 ms before reading the frame (`CaptureRequests.kt:16,125`); the user
confirmed on device (PYB110, Android 17) that the frame is visible and appears
in the screenshot.

## Goals

- The saved screenshot contains no Screen Translator window and no ColorOS
  floating-window frame.
- The visible frame flash is reduced to the short interval our task exists.
- Unchanged: one-tap UX, first-tap consent flow, the device-verified Lens
  request (`docs/lens-handoff.md`), fullscreen Lens presentation, failure
  toasts, one-capture-at-a-time, and cancellation safety.

## Non-goals

- Eliminating the brief frame flash entirely: ColorOS frames any task the
  Sidebar opens; while our task exists at all, the frame can be drawn. This
  design removes it from the screenshot and shortens its life to the task-open
  interval (~300 ms).
- Changing the Lens request, grant, or relay contract.
- Verifying consent, rotation, stop/restart, or offline behavior (device
  checklist stays authoritative).

## Design

### 1. Service-owned translation command

`ScreenCaptureService` gains `ACTION_TRANSLATE`. The command carries no data;
the service owns the whole capture.

```
handleCommand(ACTION_TRANSLATE) -> translate():
    pipeline ?: return                      // no ready session: no-op
    id = UUID.randomUUID()
    if (!pipeline.request(id)) return       // one capture at a time
    sessionScope().launch {
        result = pipeline.results.first { it.containsKey(id) }[id]
        pipeline.acknowledge(id)
        when (result) {
            Saved  -> startTranslationRelay(result.uri)
            Failed -> toast(result.error)
        }
    }
```

`startTranslationRelay` moves unchanged from `CaptureActivity.startRelay`:
`startActivity(TranslateRelayActivity.intent(uri) + NEW_TASK +
EXCLUDE_FROM_RECENTS, fullscreenOptions())`, catching launch failure and
toasting `error_launch_failed`.

Background-activity-launch note: today's relay launch succeeds while
`CaptureActivity` is still visible (`BAL_ALLOW_VISIBLE_WINDOW` plus a grace
period in the bug reports). In the new flow the app has no visible window at
relay time, so the launch relies on the same uid's BAL grace period (10 s on
AOSP; our interval is well under 2 s). This is the main device-test risk.

### 2. Task closes before capture

The capture-mode root (`SetupActivity` in capture mode) no longer launches a
child. Its `launchCapture()` is replaced by `submitTranslation()`:

1. `ScreenCaptureService.translate(context)` — `startService` with
   `ACTION_TRANSLATE`; on any throw, fall back to `returnToSettings()`.
2. `finishAndRemoveTask()` on its own task (the same AppTask lookup
   `CaptureActivity.removeCaptureTask()` uses today).

The command is sent first so it survives the task's death.

### 3. Settled-frame capture

The pipeline's delay changes from "let our window settle" (400 ms) to 0: the
service arms immediately, while the task is closing. `ProjectionFrameSource`
replaces `take()` with `takeSettled()`:

1. Drain queued frames (discard pre-close compositions, unstall the reader).
2. Wait for the first post-arm frame.
3. Keep replacing a candidate bitmap with each newer frame.
4. Return the newest candidate 750 ms after the first frame arrived
   (`SETTLE_WINDOW_MS`): by then the task-close animation (≈300 ms) and the
   underlying app's resume redraw have finished, so the newest frame is the
   settled, frame-free screen. Continuous churn (video) is handled by the same
   fixed window instead of waiting for quiet that never comes.
5. Hard timeout `FRAME_TIMEOUT_MS` (5 s) with no candidate →
   `ScreenshotFailed`, as today.

Cancellation recycles the candidate. `take()` is deleted with its callers.

### 4. Activities

- `CaptureActivity` and its manifest entry are deleted.
- `SetupActivity` keeps the capture-mode transparent recreation (so the
  flash stays invisible; consent path also lands there), but capture mode now
  only submits and closes: the `capture` result launcher, `childLaunched`, and
  `CaptureActivity.cancelActiveCapture()` are removed. A non-sidebar launch
  while a service capture is in flight (<1 s window) no longer cancels it.
- `TranslateRelayActivity`, the Lens request, and the relay task's
  fullscreen/new-task options are unchanged.

### 5. Failure matrix

| Condition | Behavior |
| --- | --- |
| No ready session when the command lands | No-op (matches today's silent abandon on session death). |
| Another capture in flight | `pipeline.request` false → no-op. |
| No frame within 5 s | `ScreenshotFailed` toast, no relay. |
| Save failure | `SaveFailed` toast, no relay. |
| Relay start rejected | `error_launch_failed` toast. |
| Command send throws | Root returns to settings. |

## Testing

- `ProjectionFrameSource`: takeSettled returns the newest frame after the
  window; discards pre-arm frames; returns at first+window under continuous
  frames; fails after timeout with none; cancellation recycles the candidate;
  resize mid-settle keeps the newest-arrival rule.
- `ScreenCaptureService`: ACTION_TRANSLATE against a ready pipeline requests
  once, acknowledges, and starts the relay with the exact intent flags and
  fullscreen options; each failure row toasts without launching; no ready
  pipeline and duplicate commands are no-ops; stop mid-capture cancels cleanly.
- `SetupActivity`: capture mode submits the command then removes its own task;
  the consent path reaches the same submission; settings launches do not.
- `CaptureActivity` and its tests are deleted; `CaptureRequests` keeps its
  delay parameter (now 0) so its guard semantics stay covered by existing
  tests.

## Device validation

1. Warm Sidebar tap on a static screen: no frame in the saved PNG, Lens opens
   fullscreen in Translate mode (capture the PNG from cache or check the Lens
   image).
2. Cold tap with consent: same result after approval.
3. Underlying video/live-content screen: capture completes within ~1 s and the
   PNG shows current content.
4. Repeated taps: one capture at a time, no duplicate Lens.
5. If the relay start is blocked (BAL), the log will show the denial — fall
   back to discussing alternatives before changing code further.