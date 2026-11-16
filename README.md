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
