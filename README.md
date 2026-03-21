# Schrittji

Schrittji is a small Android app for generating realistic-looking step activity and publishing it to Health Connect so you can test how another Android app reacts to walking history and ongoing updates.

## What it does

- creates daily step plans with a mix of structure and randomness
- varies behavior by profile, weekday/weekend, and rolling multi-day patterns
- writes minute-level `StepsRecord` data to Health Connect
- can backfill recent history and keep topping up data in the background with WorkManager
- always uses the same generation logic in normal app code instead of switching behavior behind a debug-only flag

## Project layout

- `app/` - Android application
- `.github/workflows/build-release-apk.yml` - CI job that builds and uploads a release APK on every push / PR
- `.github/workflows/create-tag-release.yml` - CI job that creates a GitHub Release for `vX.X.X` tags and attaches the APK
- `signing/schrittji-release.jks` - portable test keystore used to make the release APK directly installable

## Using the app

1. Install the APK on your Android device.
2. Open Schrittji.
3. Open Health Connect and grant Schrittji step read/write permission.
4. Set Schrittji-only daily min/max steps and a backfill window.
5. Use **Backfill selected history window** to seed prior days.
6. Turn on continuous publishing and save settings if you want ongoing updates every ~15 minutes.

## Downloading a release APK from GitHub Actions

Every push triggers the `Build release APK` workflow.

1. Open the workflow run in GitHub.
2. Download the uploaded artifact named `schrittji-release-<commit-sha>`.
3. Extract the artifact archive.
4. Install the contained release APK on your device.

## Creating a GitHub release from a tag

Pushing a semantic version tag in the form `vX.X.X` triggers the `Create tagged release` workflow.

Example:

```bash
git tag v1.2.3
git push origin v1.2.3
```

That workflow will:

1. build the release APK
2. create a GitHub Release for the tag
3. attach the APK as a release asset

This works for any commit you tag, as long as the tag name matches `vX.X.X`.

## Local build

```bash
./gradlew assembleRelease
```

The repository includes a dedicated test signing key so the generated release APK is installable without extra secrets. Do not reuse that key for production distribution.
