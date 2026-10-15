# Screen Translate: Android icon set

## Install

1. Copy the `res/` folder into `app/src/main/res/`, merging with what is there.
2. In `AndroidManifest.xml`, on the `<application>` element:

```xml
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher_round"
```

3. Upload `ic_launcher-playstore.png` (512 x 512) as the Play Console app icon.

## What is in it

| File | Used by |
| --- | --- |
| `res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` | Adaptive icon, Android 8.0+ (background, foreground, monochrome) |
| `res/drawable/ic_launcher_foreground.xml` | Foreground layer, vector, transparent |
| `res/drawable/ic_launcher_monochrome.xml` | Themed icon, Android 13+ |
| `res/values/ic_launcher_background.xml` | Background color `#1F1F21` |
| `res/mipmap-*/ic_launcher.png`, `ic_launcher_round.png` | Fallback for Android 7.1 and older (48 to 192 px) |
| `ic_launcher-playstore.png` | Play Store listing icon |

## Notes

- The foreground artwork stays inside the 66 dp safe zone, so no launcher mask crops it.
- Colors: background `#1F1F21`, corners `#E2E2E5`, front card `#F5F5F5`, back card `#A8A8AD`.
- The monochrome layer uses the same shapes in one color. The "A" and the translated glyph are real cutouts in both layers.
- Android Studio may warn that the monochrome drawable is unused below API 33. That is expected.
