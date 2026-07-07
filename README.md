# GIF Maker (Android, Jetpack Compose)

Converts a trimmed section of a video into an animated GIF — built dark-mode-first,
same structure/workflow as the Video Resizer project.

## What it does
1. Pick a video (`video/*`).
2. Shows duration and original resolution.
3. Drag a range slider to pick the **start/end trim points**.
4. Choose **FPS** (5–24) and **output width** (120–640px) using **sliders**
   — drag to any value, live estimate below shows the resulting frame count
   and warns when FPS will be auto-reduced for long trims.
5. Tap **Buat GIF** — frames are extracted with `MediaMetadataRetriever` and
   encoded into a GIF89a file by a from-scratch encoder (`GifEncoder.kt`):
   median-cut color quantization + variable-code-size LZW compression, both
   implemented directly from the public GIF specification (no third-party
   GIF library dependency).
6. The result is published to **Pictures > GifMaker** in the device gallery
   (via `MediaStore` on Android 10+, or direct file write + media scan on
   Android 9 and below), with a **Share / Buka** button as a secondary path.

To keep things responsive on-device, frame count is capped at 240 — if a
trim range + FPS combination would produce more, FPS is automatically
reduced for that conversion (still covers the same trimmed duration).

## File size
Before you even tap **Buat GIF**, an **accurate size estimate** appears
under the frame-count line. It's not a generic formula — the app actually
extracts and encodes a handful of real sample frames from your specific
video at the chosen width/FPS, measures their real compressed size, then
projects that across the full frame count. This means the estimate reflects
*this clip's* actual color/motion complexity, not a one-size-fits-all guess.
The estimate recalculates (debounced ~350ms) whenever you move a slider or
the trim range.

After the real conversion finishes, the actual file size is also shown for
confirmation. There's no automatic downscaling to hit a size target — you
stay in full control of FPS/width/trim. If the result is **over 14 MB**
(either in the pre-estimate or the final result), a warning is shown in red
— many chat apps and some sharing targets cap GIF size — suggesting you
lower FPS, width, or the trim duration if a smaller file is needed.

## Studio
Tap the gallery icon in the top bar to open **Studio** — a history of every
GIF made in this app, each with:
- A thumbnail (first frame, saved alongside the GIF).
- The FPS / width / frame count / trim duration used.
- **Edit ulang**: reopens the original source video on the main screen with
  the same trim range, FPS, and width already applied, so you can tweak and
  re-render. This requires the source video to still be reachable — the app
  requests a persistable read permission on the video when it's first
  picked, but if the file has since been moved/deleted you'll see a message
  saying it's no longer accessible.
- **Share**: re-opens the share sheet for that GIF.
- **Delete** (trash icon): removes the entry and its backing files.

History and thumbnails are stored in the app's private cache/SharedPreferences
(not the public gallery copy), so clearing the app's storage/cache from
Android's system settings will also clear Studio's history — the GIFs
already saved to Gallery > Pictures > GifMaker are unaffected either way.

## Dark mode
Same approach as Video Resizer: `ui/theme/Color.kt` + `Theme.kt` build a
Material 3 dark palette by default, `values-night/themes.xml` avoids a light
flash before Compose loads, and the moon/sun icon in the top bar lets you
force Dark / Light / Follow system.

## Building
Same GitHub Actions flow as before — push this project to a repo and the
included `.github/workflows/build.yml` builds a debug APK automatically;
download it from the Actions run's **Artifacts** section as
`gif-maker-debug-apk`.

## Notes
- No external GIF-encoding library is used, so there's no risk of a missing
  Maven dependency breaking the build — everything needed is in
  `GifEncoder.kt` plus Android's built-in `MediaMetadataRetriever`.
- Quantization runs per-frame (each frame gets its own best-fit 256-color
  palette), which keeps quality reasonable for simple clips but means very
  high-motion, high-color footage will look best at the smaller width
  presets (180–320px).
- This is a functional starting point, not a polished store-ready app —
  a live GIF preview before saving, and a progress percentage instead of a
  frame counter, are natural next additions.
