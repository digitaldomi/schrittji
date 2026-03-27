# Schrittji

Android app that **generates realistic step patterns** and **writes them to [Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect)**. Use it to seed history, keep data topped up, and preview how charts look with **recorded vs projected** activity.

**Application ID:** `dev.sudominus.schrittji` (installs as a separate app from older `dev.digitaldomi.schrittji` builds).

## Features

- **Day & week charts** — Minute-level steps (Health Connect + simulated projection). **Today** splits at a **now** line: past = recorded, future = projection only.
- **Workouts** — Running, cycling, and mindfulness blocks on the timeline; optional **write** to Health Connect **after each session ends** (not mid-workout).
- **Settings** — Daily step range, backfill window, optional background sync (~15 min), toggles for steps and each workout type (including mindfulness session count/duration).
- **Permissions** — Steps and **exercise** read/write (exercise read is required for loading sessions on charts). Written records use generic titles and normal device-style metadata.

## Quick start

1. Install the APK and open Schrittji.
2. Grant Health Connect permissions when prompted (steps + exercise as needed).
3. Set your step range and backfill options, then **Save**.
4. Run **Backfill** to seed past days; enable **background service** if you want ongoing updates.

## Build

```bash
./gradlew assembleRelease
```

A test signing key is included so release APKs install locally. Replace with your own key for real distribution.

## CI & releases

- **Every push** — Workflow builds a release APK (see Actions artifacts).
- **Tags `vX.X.X`** — Workflow creates a GitHub Release and attaches the APK.

## Project layout

| Path | Purpose |
|------|---------|
| `app/` | Application code |
| `.github/workflows/` | Build and release automation |
| `signing/schrittji-release.jks` | Local test keystore |

## Credits

The in-app **running** workout icon uses Google’s **Material Symbols** glyph *directions_run* (Apache 2.0), scaled to the app’s 24dp grid.

## License / disclaimer

This tool is for **development and testing** of Health Connect consumers. Respect platform policies and user consent when handling health data.
