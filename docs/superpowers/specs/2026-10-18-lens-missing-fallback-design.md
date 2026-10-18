# Lens-missing fallback design

## Problem

`TranslateRelayActivity` shows "Could not open Google Lens." when the Lens launch
fails and stops there. Lens is hosted by the Google app
(`com.google.android.googlequicksearchbox`); there is no standalone Lens app. The
launch fails when the Google app is missing, disabled, or too old to have
`LensExportedActivity`. The user gets no path to fix it.

## Behavior

When starting the Lens request throws, the relay activity:

1. Releases the screenshot URI grant.
2. Shows the toast `error_lens_unavailable`: "Google app with Lens is required. Opening store…".
3. Tries to open the Google app's store page, trying each store intent in order
   and moving to the next on failure.
4. If no store intent can be started, shows the existing `error_launch_failed` toast.
5. Sets `RESULT_CANCELED` and finishes.

Only a failed launch triggers the store. A non-OK result delivered to
`onActivityResult` keeps today's behavior (toast, no store): Lens ran and the user
may simply have backed out.

No package-visibility check and no manifest `<queries>` entry are used; failure is
detected by the thrown exception (approach B). Consequence: any launch failure,
including a Lens-side rejection, offers the store.

## Components

- `lens/LensRequest.kt`: add `installGoogleAppRequests(): List<Intent>`, returning
  in order:
  1. `ACTION_VIEW` on `market://details?id=com.google.android.googlequicksearchbox`
  2. `ACTION_VIEW` on `https://play.google.com/store/apps/details?id=com.google.android.googlequicksearchbox`

  Each has `FLAG_ACTIVITY_NEW_TASK`, since the relay finishes right after.
- `ui/TranslateRelayActivity.kt`: add an internal `storeLauncher: (Intent) -> Unit`
  seam (default `startActivity`), mirroring `requestLauncher`. The catch block runs
  the sequence above. Each store launch catches `ActivityNotFoundException` and
  `SecurityException`.
- `res/values/strings.xml`: add `error_lens_unavailable`.

## Testing

- `LensRequestTest`: the two intents have the expected action, URI, and flag, in order.
- `TranslateRelayActivityTest`:
  - Lens launch throws: `storeLauncher` receives the market intent; the grant is
    released; the result is `RESULT_CANCELED`.
  - Market intent throws: the https intent is launched.
  - Both throw: the `error_launch_failed` toast is shown; no crash.

## Docs and release

- README: replace "install or enable the Google app and try again" with a note that
  the app opens the store page.
- `docs/lens-handoff.md`: short note on the fallback.
- Commit type `feat(lens)`, a minor release under the repository rules.
