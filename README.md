# Vusion Label Scanner

A standalone Android app that scans for electronic shelf labels over Bluetooth LE, decodes their Link IDs, and works out which aisle and shelf unit ("modular") you're nearest to.

It runs **entirely on the phone**. There's no account, no login and no network access (the app doesn't even request the INTERNET permission).

## What it does

| Feature | How |
|---|---|
| Scan | BLE scan filtered to service UUID `0xFC8C` (the filter can be turned off in Settings to see every advert) |
| Decode | Reads Link IDs from legacy beacon frames and plain frames. Encrypted frames are decoded only if you enter an AES key and IV in Settings; otherwise they're listed as "encrypted (no key set)" with their MAC and raw payload. |
| Smooth | Per-label Kalman filter with noise adapted to each label's recent variance |
| Nearest label | Strongest average RSSI over 5 s, with configurable hysteresis (default 2 dB) |
| Facing modular | Ranks labels by mean RSSI minus 0.7 × stdev. The leader must be at least −80 dBm and ahead of the runner-up by 2 dB (3 dB when strong), and must hold for 2 evaluations. Lost after 3 failed evaluations. |
| Aisle / modular names | From **your own** CSV label map (Settings → Import). Without one, you still get IDs and RSSI. |

What it **can't** do: flash label LEDs, look up products, or fetch a store's label layout. Those need the store's back-end system, which this app deliberately doesn't talk to.

## Build (Fedora WSL)

You need JDK 17 or newer and the Android SDK (platform 34 and build-tools). Gradle itself isn't needed: the committed `./gradlew` wrapper downloads Gradle 8.10.2 on first use.

```bash
# one-time
export ANDROID_HOME=$HOME/android-sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

cd vusion-scanner                            # your clone of this repo
echo "sdk.dir=$ANDROID_HOME" > local.properties

# every time
./gradlew test             # unit tests (decoder, CRC, Link IDs, detectors, CSV)
./gradlew assembleDebug    # -> app/build/outputs/apk/debug/app-debug.apk
```

## Install on a phone

Every push to `main` publishes a GitHub release with the debug APK and a QR code. The QR code always points at the latest build. To build locally instead: WSL can't see USB devices by default, so the easiest options are:

- **Windows adb:** install Android platform-tools on Windows, then from PowerShell run
  `adb install \\wsl.localhost\<distro>\<path-to-clone>\app\build\outputs\apk\debug\app-debug.apk`
- **Wireless debugging** (Android 11+): enable it on the phone, `adb pair` and `adb connect` from WSL, then `adb install app/build/outputs/apk/debug/app-debug.apk`

## Using it

1. Press **Start scan** and grant the Bluetooth and location permissions. Location is required by Android for beacon-type scans; the app never reads your GPS position.
2. Labels appear sorted by filtered RSSI, showing the Link ID (or MAC if undecoded), decode status, raw and filtered RSSI, and the raw advert hex.
3. To get aisle and modular names, import a CSV in Settings. See `sample-label-map.csv`:

   ```csv
   id,aisle,modular
   1H-234567,A3,M2
   AA:BB:CC:DD:EE:01,C7,M4
   ```

   `id` is a Link ID as printed on the label or a MAC address / MAC prefix. Legacy Link IDs are case-sensitive.

## If no labels show up

- Turn off **Labels only** in Settings. If adverts then appear, the labels advertise in a way the `0xFC8C` filter doesn't catch; check the raw hex for the Service Data structure.
- On Android 11 and below, Location services must be on.
- Android allows only 5 scan starts per 30 s per app. If you toggle quickly, the scan fails to register; wait and retry.

## Project layout

```
app/src/main/java/dev/local/labelscanner/
├── MainActivity.kt          Compose UI, permissions, settings dialog
├── ScannerViewModel.kt      Wires scanner -> decoder -> pipeline -> detectors -> UI state
├── SettingsStore.kt         Local preferences and the imported label map
├── ble/LabelBleScanner.kt   BluetoothLeScanner wrapper
├── decode/                  Advert parsing, CRC-16, Link ID base-32 encoding
├── signal/                  Kalman filter, RSSI pipeline, nearest and front-modular detectors
└── mapping/LabelMap.kt      CSV label -> aisle/modular lookup
app/src/test/...             JVM unit tests for decode/, signal/ and mapping/
```
