# Geo Tag Camera

Android camera app that stamps a geo-tag card (map thumbnail, address, lat/long,
date/time with GMT offset, wind, pressure, humidity, temperature) onto each photo
and saves it to `Pictures/GeoTagCamera`. GPS coordinates are also written to EXIF.

## Get the APK

**Option A — GitHub Actions (no local setup)**
1. Create a new GitHub repo and push this folder to the `main` branch.
2. Open the repo's *Actions* tab, wait for "Build APK" to finish (~3-5 min).
3. Download the `GeoTagCamera-debug-apk` artifact, unzip, install `app-debug.apk`.

**Option B — Android Studio**
Open this folder, let Gradle sync, then *Build > Build APK(s)*.
Output: `app/build/outputs/apk/debug/app-debug.apk`

**Option C — command line** (needs JDK 17, Android SDK, Gradle 8.9)
`gradle assembleDebug`

## Notes
- minSdk 29 (Android 10+). Needs internet for address, weather and map tile.
- Address: Android Geocoder. Weather: Open-Meteo (no key). Map: OpenStreetMap tiles.

## Google Maps key
1. In Google Cloud Console enable **Maps Static API** and create an API key (billing must be on; restrict the key to this API).
2. GitHub: repo Settings > Secrets and variables > Actions > New secret named `MAPS_API_KEY`.
   Local build: add `MAPS_API_KEY=your_key` to `local.properties`.
3. Without a key the app falls back to OpenStreetMap tiles.

## Resolution
Tap the chip at bottom-left to choose Max, 12/8/5/2/1 MP or VGA. The device uses the closest size it supports.
