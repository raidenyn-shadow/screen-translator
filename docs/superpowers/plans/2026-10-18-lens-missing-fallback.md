# Lens-missing Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the Lens launch fails, open the Google app's store page so the user can install or update it.

**Architecture:** A pure helper in `lens/LensRequest.kt` builds an ordered list of store intents (`market://`, then `https://play.google.com`). `TranslateRelayActivity`'s existing launch-failure `catch` shows an explanatory toast and walks that list through a test seam (`storeLauncher`), falling back to the old failure toast if nothing can be started.

**Tech Stack:** Kotlin, Android Activity APIs, Robolectric + JUnit4, Gradle.

## Global Constraints

Spec: `docs/superpowers/specs/2026-10-18-lens-missing-fallback-design.md`.

- Only a thrown exception from the Lens launch triggers the store; a non-OK `onActivityResult` keeps today's behavior (toast, no store).
- No `<queries>` manifest entry and no `PackageManager` pre-check (approach B).
- Store intents: `ACTION_VIEW`, `FLAG_ACTIVITY_NEW_TASK`, in order `market://details?id=com.google.android.googlequicksearchbox` then `https://play.google.com/store/apps/details?id=com.google.android.googlequicksearchbox`.
- Each store launch catches `ActivityNotFoundException` and `SecurityException`.
- New toast string `error_lens_unavailable`: "Google app with Lens is required. Opening store…".
- If no store intent starts, show existing `error_launch_failed`. Result is always `RESULT_CANCELED`; the URI grant is released.
- Commit subjects use `type(scope): description`, at most 100 characters. Do not bypass hooks. Do not bump versions.
- Commit messages end with:
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01WrTzucctoRT7N2QMavXW8W`
- Verification (Android change): `npm test`, `python3 -m unittest discover -s .github/scripts -p 'test_*.py'`, and `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease`.

## File Structure

- Modify `app/src/main/kotlin/com/raidenyn/screentranslator/lens/LensRequest.kt`: add `installGoogleAppRequests()`.
- Modify `app/src/main/kotlin/com/raidenyn/screentranslator/ui/TranslateRelayActivity.kt`: add `storeLauncher` seam and store fallback.
- Modify `app/src/main/res/values/strings.xml`: add `error_lens_unavailable`.
- Modify tests: `lens/LensRequestTest.kt`, `ui/TranslateRelayActivityTest.kt`.
- Modify docs: `README.md`, `docs/lens-handoff.md`.

---

### Task 1: Store intent helper

**Files:**
- Modify: `app/src/main/kotlin/com/raidenyn/screentranslator/lens/LensRequest.kt`
- Test: `app/src/test/kotlin/com/raidenyn/screentranslator/lens/LensRequestTest.kt`

**Interfaces:**
- Consumes: `GSA_PACKAGE` (existing, same file).
- Produces: `fun installGoogleAppRequests(): List<Intent>` (public, top-level in package `com.raidenyn.screentranslator.lens`).

- [ ] **Step 1: Write the failing test**

Add inside `LensRequestTest`:

```kotlin
    @Test fun installGoogleAppRequestsTryMarketThenWebStoreInNewTask() {
        val requests = installGoogleAppRequests()
        assertEquals(
            listOf(
                "market://details?id=com.google.android.googlequicksearchbox",
                "https://play.google.com/store/apps/details?id=com.google.android.googlequicksearchbox",
            ),
            requests.map { it.data.toString() },
        )
        requests.forEach {
            assertEquals(Intent.ACTION_VIEW, it.action)
            assertTrue(it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests '*LensRequestTest'`
Expected: FAIL (compilation error, unresolved reference `installGoogleAppRequests`).

- [ ] **Step 3: Write minimal implementation**

Append to `LensRequest.kt`:

```kotlin
/**
 * Store pages for installing or updating the Google app (host of Lens), most specific first:
 * the Play Store app, then the web page for any browser or other store handler.
 */
fun installGoogleAppRequests(): List<Intent> = listOf(
    "market://details?id=$GSA_PACKAGE",
    "https://play.google.com/store/apps/details?id=$GSA_PACKAGE",
).map { Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests '*LensRequestTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/raidenyn/screentranslator/lens/LensRequest.kt app/src/test/kotlin/com/raidenyn/screentranslator/lens/LensRequestTest.kt
git commit -m "feat(lens): add Google app store intents" \
  -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01WrTzucctoRT7N2QMavXW8W"
```

---

### Task 2: Open the store when the Lens launch fails

**Files:**
- Modify: `app/src/main/kotlin/com/raidenyn/screentranslator/ui/TranslateRelayActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/kotlin/com/raidenyn/screentranslator/ui/TranslateRelayActivityTest.kt`
- Modify: `README.md`, `docs/lens-handoff.md`

**Interfaces:**
- Consumes: `installGoogleAppRequests(): List<Intent>` from Task 1.
- Produces: `internal var storeLauncher: (Intent) -> Unit` on `TranslateRelayActivity` (default `startActivity`); string resource `R.string.error_lens_unavailable`.

- [ ] **Step 1: Add the string resource**

In `app/src/main/res/values/strings.xml`, directly after the `error_launch_failed` line:

```xml
    <string name="error_lens_unavailable">Google app with Lens is required. Opening store…</string>
```

- [ ] **Step 2: Write the failing tests**

In `TranslateRelayActivityTest.kt`, replace the existing `missingLensFinishesCanceledWithFailureToast` test with the four below. Add imports `android.content.ActivityNotFoundException` and `org.junit.Assert.assertFalse` (the file currently uses the fully qualified exception name; either is fine).

```kotlin
    private fun failingLensRelay(storeLauncher: (Intent) -> Unit): ActivityController<TranslateRelayActivity> {
        val controller = Robolectric.buildActivity(TranslateRelayActivity::class.java, translateIntent())
        controller.get().requestLauncher = { _, _ -> throw ActivityNotFoundException("missing Lens") }
        controller.get().storeLauncher = storeLauncher
        return controller.create().start().resume().visible()
    }

    @Test fun missingLensOpensTheMarketPageAndFinishesCanceled() {
        val opened = mutableListOf<Intent>()
        val controller = failingLensRelay { opened += it }
        val relayed = shadow(controller.get())
        assertNull(relayed.nextStartedActivityForResult)
        assertEquals(
            listOf("market://details?id=com.google.android.googlequicksearchbox"),
            opened.map { it.data.toString() },
        )
        assertEquals(controller.get().getString(R.string.error_lens_unavailable), ShadowToast.getTextOfLatestToast())
        assertTrue(controller.get().isFinishing)
        assertEquals(Activity.RESULT_CANCELED, relayed.resultCode)
    }

    @Test fun missingLensFallsBackToTheWebStoreWhenMarketIsUnavailable() {
        val opened = mutableListOf<Intent>()
        failingLensRelay {
            if (it.scheme == "market") throw ActivityNotFoundException("no store app")
            opened += it
        }
        assertEquals(
            listOf("https://play.google.com/store/apps/details?id=com.google.android.googlequicksearchbox"),
            opened.map { it.data.toString() },
        )
    }

    @Test fun missingLensWithoutAnyStoreShowsTheFailureToast() {
        val controller = failingLensRelay { throw ActivityNotFoundException("no store") }
        assertTrue(controller.get().isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadow(controller.get()).resultCode)
        assertEquals(controller.get().getString(R.string.error_launch_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test fun lensRejectionAfterLaunchDoesNotOpenTheStore() {
        var storeOpened = false
        val controller = Robolectric.buildActivity(TranslateRelayActivity::class.java, translateIntent())
        controller.get().storeLauncher = { storeOpened = true }
        controller.create().start().resume().visible()
        val relayed = shadow(controller.get()).nextStartedActivityForResult ?: error("no Lens request")
        shadow(controller.get()).receiveResult(relayed.intent, 1, null)
        assertFalse(storeOpened)
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests '*TranslateRelayActivityTest'`
Expected: FAIL (compilation error, unresolved reference `storeLauncher`).

- [ ] **Step 4: Write minimal implementation**

In `TranslateRelayActivity.kt`:

Add imports:

```kotlin
import android.content.ActivityNotFoundException
import com.raidenyn.screentranslator.lens.installGoogleAppRequests
```

Add the seam below `requestLauncher`:

```kotlin
    /** Framework seam scripted by tests; production opens the store page. */
    internal var storeLauncher: (Intent) -> Unit = { startActivity(it) }
```

Replace the `catch` block body in `onCreate`:

```kotlin
        } catch (_: Exception) {
            // Lens missing, unavailable for this caller, or the start was rejected.
            releaseGrant()
            Toast.makeText(this, R.string.error_lens_unavailable, Toast.LENGTH_SHORT).show()
            if (!openStore()) {
                Toast.makeText(this, R.string.error_launch_failed, Toast.LENGTH_SHORT).show()
            }
            setResult(RESULT_CANCELED)
            finish()
        }
```

Add below `releaseGrant()`:

```kotlin
    /** Starts the first store page that resolves; false if none can be started. */
    private fun openStore(): Boolean = installGoogleAppRequests().any { request ->
        try {
            storeLauncher(request)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest --tests '*TranslateRelayActivityTest'`
Expected: PASS (all tests in the class, including the pre-existing ones).

- [ ] **Step 6: Update docs**

`README.md`: replace

```
Capture, image-save, and Lens-launch failures are shown as short toasts. If Lens
is unavailable, install or enable the Google app and try again.
```

with

```
Capture and image-save failures are shown as short toasts. If Lens cannot be
launched (the Google app is missing, disabled, or too old), the app says so and
opens the Google app's store page; install or update it and try again.
```

`docs/lens-handoff.md`: append a fourth sentence to numbered item 4 in "Task and grant ownership", or add item 5:

```
5. If starting the Lens intent throws, the relay releases the grant, toasts that the
   Google app with Lens is required, and opens its store page (`market://`, then the
   Play web URL). A non-OK Lens result does not open the store.
```

- [ ] **Step 7: Run the full verification suite**

Run:

```bash
npm test
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease
```

Expected: all pass. Fix any lint finding before committing.

- [ ] **Step 8: Commit**

```bash
git add app/src/main app/src/test README.md docs/lens-handoff.md
git commit -m "feat(lens): open store when Google app is unavailable" \
  -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" \
  -m "Claude-Session: https://claude.ai/code/session_01WrTzucctoRT7N2QMavXW8W"
```

---

## Self-Review

- **Spec coverage:** helper and intent order/flags (Task 1); `storeLauncher` seam, catch-block sequence, exception handling, toast, result/grant (Task 2 steps 1-5); non-OK result unchanged (test `lensRejectionAfterLaunchDoesNotOpenTheStore`); README and handoff docs (step 6); `feat(lens)` commit (step 8).
- **Placeholders:** none.
- **Type consistency:** `installGoogleAppRequests()`, `storeLauncher`, and `error_lens_unavailable` are spelled the same in every task.
