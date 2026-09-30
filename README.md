# Spoof — mock location for Android app debugging

A small debug tool that feeds a fake location to the rest of the device through
Android's official **mock location** (test provider) API. Use it to test
location-dependent features of your own app without leaving your desk.

It mocks the `gps`, `network` and (Android 12+) `fused` providers, so apps using
`LocationManager` *or* Google Play Services' `FusedLocationProviderClient` see
the fake position.

## Features

- Map picker (no API key needed) — tap to set the location or destination,
  long-press and drag markers to adjust, and watch the live mocked position move
  along the route. Switch between Streets and Satellite (Esri) or OSM (HOT style)
- Fixed location — or paste `lat, lng` straight from Google Maps
- Route simulation — set a destination and a speed (mph) and the location moves
  toward it in a straight line, with bearing and speed populated
- Configurable accuracy and random jitter, in feet, (to test filtering/smoothing code)
- Foreground-service notification with a Stop button
- Scriptable from `adb` for automated tests

## Build & install

Requires JDK 17+ and the Android SDK (API 35).

```sh
./gradlew installDebug
```

Or open the project in Android Studio and run it.

## One-time device setup

1. Enable **Developer options** (Settings → About phone → tap *Build number* 7 times).
2. Developer options → **Select mock location app** → choose **Spoof**.
3. Open Spoof, tap the map (or type coordinates) to pick a location, tap **Start**, and grant the location /
   notification permissions it asks for.

If you forget step 2, the status line will say so.

## Driving it from adb

The app's screen uses imperial units, but adb extras are metric (`speed` in
m/s, `accuracy` and `jitter` in meters), matching Android's location APIs.

The service only accepts commands from the shell (it's protected by the `DUMP`
permission), so other apps on the device can't control it.

```sh
# Fixed location
adb shell am start-foreground-service -n com.example.spoof/.MockLocationService \
  -a com.example.spoof.START --ed lat 37.4220 --ed lng -122.0841

# Walk from A to B at 1.4 m/s (~3 mph) with 5 m (~16 ft) accuracy and 3 m (~10 ft) jitter
adb shell am start-foreground-service -n com.example.spoof/.MockLocationService \
  -a com.example.spoof.START \
  --ed lat 37.4220 --ed lng -122.0841 \
  --ed dest_lat 37.4275 --ed dest_lng -122.0800 \
  --ef speed 1.4 --ef accuracy 5 --ef jitter 3

# Stop
adb shell am start-foreground-service -n com.example.spoof/.MockLocationService \
  -a com.example.spoof.STOP
```

On an emulator you can alternatively use `adb emu geo fix <lng> <lat>`, but that
only affects the GPS provider; Spoof also works on physical devices.

## Notes

- The map needs internet access to load tiles (from Esri's ArcGIS Online or
  OpenStreetMap France, cached locally). Mocking itself works offline. These are
  free public tile services; they're fine for a debug tool but check their terms
  before shipping anything built on them.

- Locations delivered by test providers have `Location.isMock()`
  (`isFromMockProvider()` on older APIs) set to `true`. If your app rejects mock
  locations, allow them in debug builds.
- Stopping the service removes the test providers, restoring real location.
