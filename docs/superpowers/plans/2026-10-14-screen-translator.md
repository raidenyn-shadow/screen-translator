# Screen Translator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A one-tap ColorOS Smart Sidebar tool that screenshots the screen and opens Google Lens with the image pre-loaded.

**Architecture:** The app's launcher activity is a transparent `TrampolineActivity` (sidebar taps launch it like a normal icon). It posts a capture to `ScreenCaptureService` (an AccessibilityService, `canRetrieveWindowContent="false"`) and finishes. The service's `CaptureOrchestrator` waits 400 ms, takes the screenshot, saves it to private cache via `CaptureStore`, and hands a FileProvider URI to `LensLauncher`, which fires a three-step fallback chain (direct Lens components → package-restricted `ACTION_SEND` → system chooser). A Compose `SetupActivity` handles one-time enablement and guidance.

**Tech Stack:** Kotlin, Jetpack Compose (setup screen only), `AccessibilityService.takeScreenshot` (API 30), JUnit4 + Robolectric + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-10-14-screen-translator-design.md`

## Global Constraints

- Every gradle command runs with the prefix `JAVA_HOME=$JAVA_ROOT/zulu21`.
- applicationId/namespace: `com.raidenyn.screentranslator`; all code in that package; sources in `app/src/main/kotlin` and `app/src/test/kotlin`.
- minSdk 30, compileSdk 36, targetSdk 36. Single module `app`; Kotlin DSL; version catalog `gradle/libs.versions.toml`.
- If a pinned artifact version fails to resolve, substitute the nearest released version and record it in the catalog.
- Production dependencies allowed: androidx core-ktx, activity-compose, Compose BOM (ui, material3), kotlinx-coroutines-android. Test dependencies: junit4, robolectric, androidx-test core-ktx, kotlinx-coroutines-test. Nothing else.
- Accessibility service config (exact): `canTakeScreenshot="true"`, `canRetrieveWindowContent="false"`, `accessibilityEventTypes="typeWindowStateChanged"`, `accessibilityFeedbackType="feedbackGeneric"`, `notificationTimeout="100"`.
- `const val CAPTURE_DELAY_MS = 400L` (top-level, in capture package).
- Captures live only in `cacheDir/captures/`; FileProvider authority `${applicationId}.files`; every file in that dir is purged in `App.onCreate`; never write to MediaStore.
- No `INTERNET` permission, no analytics.
- Battery-optimization guidance opens `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission — Play-safer variant of the spec's "exemption prompt"; deviation is noted in README).
- All tests: `@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])`, Robolectric 4.14.1.
- Test/verification command per task: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew <task>` run from the repo root.

## Review Focus

1. **Service disabled/killed by ColorOS** → a sidebar tap must open `SetupActivity`, never crash or silently do nothing — tested in Task 5.
2. **Lens components missing/renamed** → degrade to package fallback then chooser; nothing handles image share → `Failed` + toast, no crash — tested in Task 3.
3. **`takeScreenshot` rate-limit/transient failure** → error surfaces via toast, no crash — tested in Task 4.
4. **Lens re-reads the URI after launch** → the capture file must survive after launch (purge only at next app start) — tested in Task 2.
5. **Overlapping/double sidebar taps** → no duplicate Lens launches, second request dropped cleanly — tested in Task 4.

---

### Task 1: Gradle scaffold + test harness

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `.gitignore`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/raidenyn/screentranslator/App.kt`
- Test: `app/src/test/kotlin/com/raidenyn/screentranslator/AppTest.kt`

**Interfaces:**
- Produces: buildable module `:app` with Robolectric tests; `App` (Application subclass) used by Tasks 2–6; catalog aliases used by all tasks.

- [ ] **Step 1: Bootstrap the Gradle wrapper** (no `gradle` CLI on PATH)

```bash
curl -L -o /tmp/opencode/gradle-8.14.3-bin.zip https://services.gradle.org/distributions/gradle-8.14.3-bin.zip
unzip -q /tmp/opencode/gradle-8.14.3-bin.zip -d /tmp/opencode
JAVA_HOME=$JAVA_ROOT/zulu21 /tmp/opencode/gradle-8.14.3/bin/gradle wrapper --gradle-version 8.14.3
```

Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/*` created in repo root.

- [ ] **Step 2: Create build files and manifest**

Catalog pins: AGP 8.13.0, Kotlin 2.2.20 (+ `org.jetbrains.kotlin.plugin.compose` 2.2.20), compose-bom 2025.06.01, core-ktx 1.17.0, activity-compose 1.10.1, kotlinx-coroutines 1.10.2 (android + test), junit 4.13.2, robolectric 4.14.1, androidx-test core-ktx 1.6.1.

`app/build.gradle.kts`: android { namespace `com.raidenyn.screentranslator`; compileSdk 36; defaultConfig minSdk 30 / targetSdk 36; kotlin source dirs via `sourceSets.getByName("main").java.srcDir("src/main/kotlin")` and the same for `test`; `testOptions.unitTests.isIncludeAndroidResources = true`; buildFeatures { compose = true }; compileOptions jvmTarget 17; kotlin compilerOptions jvmTarget 17 }. Plugins: `com.android.application`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`.

Manifest: `<application android:name=".App" android:label="Screen Translator" android:allowBackup="false">` with no activities yet (added in Tasks 4–6).

`.gitignore`: `.gradle/`, `build/`, `local.properties`, `.idea/`, `*.iml`.

- [ ] **Step 3: Write the failing smoke test**

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppTest {
    @Test
    fun applicationIsApp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("com.raidenyn.screentranslator", app.packageName)
        assertTrue(app is App)
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest`
Expected: FAIL — `App` unresolved (file not created yet). First run downloads dependencies; be patient.

- [ ] **Step 5: Create `App.kt`**

`class App : Application()` — nothing else yet (Task 2 adds purge).

- [ ] **Step 6: Run to verify it passes**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "build: gradle scaffold with robolectric test harness"
```

---

### Task 2: CaptureStore

**Files:**
- Create: `app/src/main/kotlin/com/raidenyn/screentranslator/capture/CaptureStore.kt`
- Create: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml` (FileProvider), `app/src/main/kotlin/com/raidenyn/screentranslator/App.kt`
- Test: `app/src/test/kotlin/com/raidenyn/screentranslator/capture/CaptureStoreTest.kt`

**Interfaces:**
- Consumes: `App` from Task 1.
- Produces:
  - `class CaptureStore(capturesDir: File, authority: String)`
  - `suspend fun save(bitmap: Bitmap): Uri` — writes `capturesDir/<currentTimeMillis>.png`, returns `Uri.parse("$authority/captures/<name>")`
  - `fun purge(): Int` — deletes every file under `capturesDir`, returns the count
  - `companion object { fun from(context: Context): CaptureStore }` — `File(context.cacheDir, "captures")` + `context.packageName + ".files"`
  - App.onCreate calls `CaptureStore.from(this).purge()` — used by Tasks 4/5 via `CaptureStore.from(service)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    private val store = CaptureStore(dir, "com.raidenyn.screentranslator.files")
    private val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    @After fun clean() = dir.listFiles()?.forEach { it.delete() }

    @Test fun saveWritesPngAndReturnsContentUri() = runBlocking {
        val uri = store.save(bitmap)
        assertTrue(uri.toString().startsWith("content://com.raidenyn.screentranslator.files/captures/"))
        val written = dir.listFiles()!!.single()
        assertTrue(written.name.endsWith(".png"))
    }

    @Test fun savedFileSurvivesAfterSave() = runBlocking {   // Review Focus 4
        store.save(bitmap)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test fun twoSavesCreateDistinctFiles() = runBlocking {
        store.save(bitmap); store.save(bitmap)
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test fun purgeDeletesEverythingAndReturnsCount() = runBlocking {
        store.save(bitmap); store.save(bitmap)
        assertEquals(2, store.purge())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun fromUsesPackageAuthorityAndCacheDir() {
        val s = CaptureStore.from(context)
        assertEquals("com.raidenyn.screentranslator.files", s.authority)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest`
Expected: FAIL — `CaptureStore` unresolved.

- [ ] **Step 3: Implement `CaptureStore` + provider wiring**

`save`: `withContext(Dispatchers.IO)`, write via `bitmap.compress(Bitmap.CompressFormat.PNG, 100, file.outputStream())`. Keep `authority` as a constructor property for the `from` test.

`file_paths.xml`: `<cache-path name="captures" path="captures/" />`.

Manifest provider: `androidx.core.content.FileProvider`, `android:authorities="${applicationId}.files"`, `android:exported="false"`, `android:grantUriPermissions="true"`, meta-data → `@xml/file_paths`.

`App.onCreate`: add `CaptureStore.from(this).purge()`.

- [ ] **Step 4: Run to verify they pass**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: private-capture store with file provider and startup purge"
```

---

### Task 3: LensLauncher

**Files:**
- Create: `app/src/main/kotlin/com/raidenyn/screentranslator/lens/LensLauncher.kt`
- Create: `app/src/main/kotlin/com/raidenyn/screentranslator/lens/LensCandidates.kt`
- Test: `app/src/test/kotlin/com/raidenyn/screentranslator/lens/LensLauncherTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces (used by Task 4):
  - `sealed interface LensLaunchResult { data class DirectLaunch(val component: ComponentName) : LensLaunchResult; data class PackageFallback(val packageName: String) : LensLaunchResult; data object ChooserFallback : LensLaunchResult; data class Failed(val reason: String) : LensLaunchResult }`
  - `fun interface IntentStarter { fun start(intent: Intent) }`
  - `class LensLauncher(private val resolver: ContentResolver, private val starter: IntentStarter)`
  - `fun launch(uri: Uri): LensLaunchResult`
  - `object LensCandidates { val COMPONENTS: List<ComponentName>; val PACKAGES: List<String> }` — `PACKAGES = listOf("com.google.android.googlequicksearchbox", "com.google.android.apps.lens")`

- [ ] **Step 1: Write the failing tests**

Test infra: a recording `IntentStarter` that stores every intent and is scripted with a set of components/packages that throw `ActivityNotFoundException` (an "unresolvable" set). All tests use `uri = Uri.parse("content://com.raidenyn.screentranslator.files/captures/1.png")` and Robolectric's real `ContentResolver`.

```kotlin
@Test fun firstResolvableCandidateWins()          // starter succeeds on candidate #2 → DirectLaunch(c2);
                                                  // intent had setComponent(c2), EXTRA_STREAM==uri, type "image/png",
                                                  // flags contain FLAG_GRANT_READ_URI_PERMISSION and FLAG_ACTIVITY_NEW_TASK
@Test fun allCandidatesFailPackageFallbackUsed()  // all COMPONENTS throw, PACKAGES[0] throws, PACKAGES[1] ok → PackageFallback(PACKAGES[1]);
                                                  // intent had setPackage, no component
@Test fun nothingResolvesChooserFallback()        // everything throws → ChooserFallback;
                                                  // chooser intent action ACTION_CHOOSER, inner intent is ACTION_SEND with EXTRA_STREAM==uri
@Test fun evenChooserFails()                     // starter throws for chooser too → Failed
```

- [ ] **Step 2: Run to verify they fail**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest`
Expected: FAIL — `LensLauncher` unresolved.

- [ ] **Step 3: Implement the fallback chain**

`launch(uri)`:
1. For each `LensCandidates.COMPONENTS`: build `ACTION_SEND` intent — type `image/png`, `EXTRA_STREAM=uri`, `ClipData.newUri(resolver, "screenshot", uri)`, flags `FLAG_GRANT_READ_URI_PERMISSION or FLAG_ACTIVITY_NEW_TASK`, `setComponent(candidate)`; `starter.start` → return `DirectLaunch(candidate)`; catch `ActivityNotFoundException`/`SecurityException` → continue. This is Review Focus 2's no-crash path.
2. Same intent with `setPackage(pkg)` per `PACKAGES` → `PackageFallback(pkg)`.
3. `ACTION_CHOOSER` wrapping the send intent (`EXTRA_INTENT`), grant flag on both outer and inner intents → `ChooserFallback`.
4. Any throw at step 3 → `Failed(reason = throwable.message ?: "chooser unavailable")`.

`LensCandidates.COMPONENTS` initial values (best-guess placeholders, replaced by probe P1 output on the physical device — see Task 6 README):
`("com.google.android.apps.lens", "com.google.android.apps.lens.lens.LensLauncherActivity")`, `("com.google.android.googlequicksearchbox", "com.google.android.apps.gsa.searchcore.lens.LensExportActivity")`.

- [ ] **Step 4: Run to verify they pass**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: lens launcher with three-step intent fallback chain"
```

---

### Task 4: CaptureOrchestrator + ScreenCaptureService

**Files:**
- Create: `app/src/main/kotlin/com/raidenyn/screentranslator/capture/CaptureOrchestrator.kt` (contains `CAPTURE_DELAY_MS`, `ScreenshotTaker`, `CaptureError`)
- Create: `app/src/main/kotlin/com/raidenyn/screentranslator/service/ScreenCaptureService.kt`
- Create: `app/src/main/res/xml/accessibility_service_config.xml`, `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/raidenyn/screentranslator/capture/CaptureOrchestratorTest.kt`

**Interfaces:**
- Consumes: `CaptureStore` (Task 2: `from(context)`, `save(bitmap): Uri`), `LensLauncher.launch(uri): LensLaunchResult` (Task 3).
- Produces (used by Tasks 5/6):
  - `const val CAPTURE_DELAY_MS = 400L`
  - `fun interface ScreenshotTaker { suspend fun take(): Bitmap }`
  - `sealed interface CaptureError { data object ScreenshotFailed; data object SaveFailed; data object LaunchFailed }`
  - `class CaptureOrchestrator(delayMs: Long = CAPTURE_DELAY_MS, delayer: suspend (Long) -> Unit = { delay(it) }, taker: ScreenshotTaker, store: CaptureStore, launcher: LensLauncher, onError: (CaptureError) -> Unit, scope: CoroutineScope)`
  - `fun tryDispatch(): Boolean` — synchronously sets an in-flight flag and launches on `scope`; `false` if already in flight
  - `ScreenCaptureService.Companion.instance: ScreenCaptureService?` (`@Volatile`), `fun requestCapture(): Boolean` — `instance?.orchestrator()?.tryDispatch() ?: false`
  - `ScreenCaptureService` internal `var orchestratorOverride: CaptureOrchestrator?` (test hook, honored by `fun orchestrator()`)

- [ ] **Step 1: Write the failing tests**

All tests build fakes: recording `ScreenshotTaker`, `CaptureStore` real instance (Robolectric), `LensLauncher` with a recording always-succeeding starter (or scripted `Failed`), recording `onError`, and `TestScope()`. Assert after `testScope.advanceUntilIdle()`.

```kotlin
@Test fun successDelaysSavesLaunches()      // delayer received CAPTURE_DELAY_MS (== 400L);
                                            // store got taker's bitmap; launcher.launch got exactly store's returned uri; true
@Test fun screenshotFailureSurfacesError()  // taker throws → tryDispatch true, onError(ScreenshotFailed), store untouched, no launch (Review Focus 3)
@Test fun launchFailureSurfacesError()      // launcher returns Failed → onError(LaunchFailed)
@Test fun secondInFlightRequestDropped()    // two tryDispatch before advancing → second false, launcher called once (Review Focus 5)
```

- [ ] **Step 2: Run to verify they fail**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest`
Expected: FAIL — `CaptureOrchestrator` unresolved.

- [ ] **Step 3: Implement `CaptureOrchestrator`**

`tryDispatch`: `if (inFlight) return false; inFlight = true; scope.launch { try { captureOnce() } finally { inFlight = false } }; return true`.

`captureOnce` (suspend): `delayer(delayMs)` → `taker.take()` (any exception → `onError(ScreenshotFailed)`, return) → `store.save(bitmap)` (exception → `SaveFailed`) → `launcher.launch(uri)`; `Failed` → `onError(LaunchFailed)`.

- [ ] **Step 4: Implement `ScreenCaptureService` + manifest + config**

Service: `onCreate` sets `instance = this` and creates real scope `CoroutineScope(SupervisorJob() + Dispatchers.Default)`; `onDestroy` clears `instance` and cancels scope. `orchestrator()` returns `orchestratorOverride` or lazily builds the real one: `RealScreenshotTaker(this)`, `CaptureStore.from(this)`, `LensLauncher(contentResolver, ContextStarter(this))`, `onError` posting a toast via `Handler(Looper.getMainLooper())`.

`RealScreenshotTaker`: `suspendCancellableCoroutine` around `takeScreenshot(Runnable::run) { result -> }`; `result.status != ACCESSIBILITY_TAKE_SCREENSHOT_SUCCESS` → throw; else copy `result.hardwareBitmap` to `Bitmap.Config.ARGB_8888` (hardware bitmaps cannot be PNG-compressed), then close the `ScreenshotResult` (it is AutoCloseable). If the continuation is already cancelled when the callback fires, skip the copy and just close the result.

`ContextStarter`: `fun interface` impl calling `context.startActivity(intent)`.

Manifest: `<service android:name=".service.ScreenCaptureService" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" android:exported="false">` + `<intent-filter><action android:name="android.accessibilityservice.AccessibilityService"/></intent-filter>` + `<meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibility_service_config"/>`.

Config XML: exact attributes from Global Constraints.

`strings.xml`: `error_screenshot_failed`, `error_save_failed`, `error_launch_failed`.

- [ ] **Step 5: Run to verify tests pass + module still assembles**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS on both.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: capture orchestrator and screenshot accessibility service"
```

---

### Task 5: TrampolineActivity (launcher) + minimal SetupActivity

**Files:**
- Create: `app/src/main/kotlin/com/raidenyn/screentranslator/ui/TrampolineActivity.kt`
- Create: `app/src/main/kotlin/com/raidenyn/screentranslator/ui/SetupActivity.kt` (minimal placeholder; Task 6 fills UI)
- Create: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/raidenyn/screentranslator/ui/TrampolineActivityTest.kt`

**Interfaces:**
- Consumes: `ScreenCaptureService.requestCapture()`/`instance` (Task 4).
- Produces: `TrampolineActivity` is the app's launcher activity; `SetupActivity` is exported=false, opened only by the trampoline.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun noServiceRoutesToSetup()            // instance=null → next started activity is SetupActivity; trampoline isFinishing (Review Focus 1)
@Test fun servicePresentDispatches()          // Robolectric.buildService + instance=svc + recording orchestratorOverride →
                                             // tryDispatch recorded, no SetupActivity started, isFinishing
@Test fun inFlightTapDoesNothing()            // instance=svc + override whose tryDispatch returns false → no Setup started, isFinishing
```

`@After` resets `ScreenCaptureService.instance = null`.

- [ ] **Step 2: Run to verify they fail**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest`
Expected: FAIL — `TrampolineActivity` unresolved.

- [ ] **Step 3: Implement activities, theme, manifest**

`TrampolineActivity.onCreate`:
```kotlin
when {
    ScreenCaptureService.requestCapture() -> Unit
    ScreenCaptureService.instance == null -> startActivity(Intent(this, SetupActivity::class.java))
    else -> Unit   // duplicate tap while in flight
}
finish()
@Suppress("DEPRECATION") overridePendingTransition(0, 0)
```

`SetupActivity`: minimal `setContent { Text("Setup") }` placeholder in Task 5.

`themes.xml`: `Theme.ScreenTranslator.Trampoline` — parent `android:Theme.Material.NoActionBar`, `windowIsTranslucent=true`, `windowBackground=@android:color/transparent`, `windowDisablePreview=true`, `windowAnimationStyle=@null`... if `windowAnimationStyle=@null` errors, use `android:Animation` empty style.

Manifest: `TrampolineActivity` `exported="true"` + MAIN/LAUNCHER intent-filter + `theme="@style/Theme.ScreenTranslator.Trampoline"` + `noHistory="true"` + `excludeFromRecents="true"`; `SetupActivity` `exported="false"` + same trampoline theme (it is only a routing target here; Task 6 gives it a real theme).

- [ ] **Step 4: Run to verify tests pass**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: transparent trampoline launcher routing to capture or setup"
```

---

### Task 6: Setup screen, final wiring, README + probe checklist

**Files:**
- Create: `app/src/main/kotlin/com/raidenyn/screentranslator/ui/SetupScreen.kt`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` + `app/src/main/res/drawable/ic_launcher_foreground.xml` (simple vector) + `values/ic_launcher_background.xml`? use a plain color
- Create: `README.md`
- Modify: `ui/SetupActivity.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/AndroidManifest.xml` (app icon; SetupActivity theme)

**Interfaces:**
- Consumes: `ScreenCaptureService.requestCapture()`/`instance` (Task 4), all prior wiring.
- Produces: the finished, installable app.

- [ ] **Step 1: Implement `SetupScreen` (Compose) + strings**

Screen sections, top to bottom, all copy in `strings.xml`:
1. Status chip: "Capture service: on/off" from `ScreenCaptureService.instance != null`.
2. Button "Enable capture service" → `startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))`.
3. Button "Battery settings" → `startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))` (no permission needed; Play-safe variant per Global Constraints).
4. Button "Run test capture" → `ScreenCaptureService.requestCapture()`.
5. Static how-to text: add the app to Smart Sidebar (Settings → Smart Sidebar → customize), pin/lock the app in recents, enable the service once.

`SetupActivity.setContent { SetupScreen() }`; `SetupScreen` takes no parameters. Status refresh: implement with a `DefaultLifecycleObserver` on `ON_RESUME` writing service connectivity into a `mutableStateOf` holder observed by the screen.

- [ ] **Step 2: Add app icon + manifest polish**

Adaptive icon: background `#1E293B`, foreground a simple translate glyph (rounded square + "文A" text path not needed — use a generic translate-arrow vector). Manifest `<application android:icon="@mipmap/ic_launcher" ...>`; SetupActivity gets `parent="@android:style/Theme.Material.Light.NoActionBar"`-backed theme with real (non-translucent) window via new `Theme.ScreenTranslator.Setup`.

- [ ] **Step 3: Full verification**

Run: `JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
Expected: all green (lint warnings acceptable, errors not).

- [ ] **Step 4: Write `README.md`**

Sections: what it is; install (`adb install app/build/outputs/apk/debug/app-debug.apk`); setup steps (enable accessibility service, add app to Smart Sidebar, battery settings); usage; **Probe checklist (run on the OnePlus 16, update code from results)**:
- P1 (Lens components): `adb shell pm query-activities --brief -a android.intent.action.SEND -t image/png`, plus `adb shell dumpsys package com.google.android.googlequicksearchbox | grep -i lens` and the same for `com.google.android.apps.lens` — record every activity that offers Lens; replace `LensCandidates.COMPONENTS` guesses with them; reinstall and verify one-tap works with no share sheet.
- P2 (sidebar/timing): tap from Smart Sidebar; if the collapsed sidebar appears in screenshots, bump `CAPTURE_DELAY_MS` in 100 ms steps; note whether the app opens fullscreen or floating and record the ColorOS setting that controls it.
- P3 (survival): with battery settings opened as guided, reboot the device and confirm the tap still captures; if ColorOS still kills the service, note the ColorOS "allow auto-launch" toggle in the README setup steps.
- Also note the Play-policy deviation: battery guidance uses plain settings, no exemption permission.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: setup screen, icon, readme with on-device probe checklist"
```