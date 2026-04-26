# F-Droid submission

This folder holds the metadata recipe used to submit iSpindle Plotter to
[F-Droid](https://f-droid.org/). F-Droid splits the listing in two places:

1. **Store listing strings** (title, short/full description, changelogs,
   screenshots) live in the Fastlane tree at `fastlane/metadata/android/`
   in the app's own repo. F-Droid's build tooling pulls them on each new
   versionCode automatically — they don't need to be re-submitted.

2. **Build recipe** (`metadata/<applicationId>.yml`) lives in the F-Droid
   data repo at <https://gitlab.com/fdroid/fdroiddata>. That file tells
   the F-Droid builder which commits and tags to build, and where to
   pull the source from. It needs a one-time merge request to land, then
   updates for new versions when `AutoUpdateMode: Version` doesn't pick
   them up.

## Submission checklist

* [x] FOSS license (MIT, in `LICENSE`).
* [x] Source code public on GitHub.
* [x] No proprietary build dependencies — all libraries on Maven Central
  (AndroidX, Material, Ktor, kotlinx, all Apache-2.0 licensed).
* [x] No tracking or telemetry; no Google Play Services; no analytics.
* [x] Network usage limited to a local-only HTTP listener and direct
  writes to the iSpindel's IP — no third-party endpoints.
* [x] versionCode monotonic; release tagged (`v0.2.0`).
* [x] Fastlane metadata in `fastlane/metadata/android/en-US/`:
  `title.txt`, `short_description.txt`, `full_description.txt`,
  `changelogs/2.txt`, four phone screenshots in `images/phoneScreenshots/`.

## How to open the merge request

1. Fork <https://gitlab.com/fdroid/fdroiddata>.
2. Add `metadata/com.ispindle.plotter.yml` from this folder.
3. (Optional) Run `fdroid lint com.ispindle.plotter` and
   `fdroid build --on-server --no-tarball com.ispindle.plotter` locally
   if you have the [fdroidserver](https://gitlab.com/fdroid/fdroidserver)
   tool installed.
4. Open a merge request titled `New app: iSpindle Plotter`.

The Fastlane metadata in the app repo doesn't need to be referenced
explicitly — F-Droid auto-detects `fastlane/metadata/android/<locale>/`
once the recipe builds successfully.
