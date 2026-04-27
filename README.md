# iSpindle Plotter

[![build](https://github.com/GlassOnTin/iSpindlePlotter/actions/workflows/build.yml/badge.svg)](https://github.com/GlassOnTin/iSpindlePlotter/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/GlassOnTin/iSpindlePlotter?include_prereleases&sort=semver)](https://github.com/GlassOnTin/iSpindlePlotter/releases)
[![license](https://img.shields.io/github/license/GlassOnTin/iSpindlePlotter.svg)](LICENSE)

Android app (Kotlin / Jetpack Compose) that receives, stores, plots and
calibrates readings from an [iSpindel](https://github.com/universam1/iSpindel)
hydrometer — including the **MTB iSpindel PCB 4.0**.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png"
       alt="SG chart with Bayesian logistic fit, 95% credible band, lag and mid-ferment plateau detection"
       width="320">
</p>

> **Protocol note.** The MTB PCB 4.0 uses an ESP8266 and therefore has no
> Bluetooth. "Pairing" with it is really a WiFi / HTTP handshake: the phone
> runs an HTTP server, the iSpindle wakes on its interval and POSTs JSON,
> then deep-sleeps. This app is built around that flow.

## Download

Grab the latest `app-debug.apk` (or signed release APK) from the
[releases page](https://github.com/GlassOnTin/iSpindlePlotter/releases),
or install from source below.

## Features

- HTTP server in a foreground service — listens for the iSpindel "Generic
  HTTP" JSON payload (`name`, `ID`, `angle`, `temperature`, `battery`,
  `RSSI`, etc.) on port **9501**.
- Automatic device discovery — each unique firmware `ID` becomes a device.
- Time-series graphs of tilt angle, temperature, specific gravity and
  battery voltage, with 24 h / 7 d / 30 d / all windows.
- Calibration: add `(angle, known SG)` points, fit a polynomial of degree
  1–3 by ordinary least squares, see R², apply the fit to live readings.
- Bayesian fermentation model — 4-parameter logistic SG curve fitted by
  Levenberg–Marquardt with a Gaussian prior on attenuation (MAP), Laplace
  approximation of the posterior, and a 95 % credible band on both the
  predicted SG curve and the time-to-FG. See [`ROADMAP.md`](ROADMAP.md)
  for what the fitter does today and where it's going.
- Plateau detection — flags lag plateaus, mid-ferment diauxic shifts
  (where yeast pauses between sugar populations), and asymptote tails
  on the SG chart with a shaded band and inline label.
- Room-backed storage, so readings persist across app restarts.

## Build from source

Prerequisites: **Android SDK 34+** and **JDK 17+** (project targets JVM 17;
JDK 21 works fine).

```
git clone https://github.com/GlassOnTin/iSpindlePlotter.git
cd iSpindlePlotter
./gradlew :app:assembleDebug
./gradlew :app:installDebug    # with a connected device or emulator
```

Open in Android Studio / IntelliJ and the wrapper + SDK bootstrap happens
automatically.

## Continuous builds

- `build` workflow runs on every push and PR — produces an `app-debug.apk`
  as a workflow artifact.
- `release` workflow runs on a `v*` tag — creates a GitHub Release and
  attaches an APK. By default the APK is **debug-signed** so it installs
  without a vendor keystore. To ship a properly-signed release, add these
  repository secrets and push a tag:
  - `RELEASE_KEYSTORE_BASE64` — base64-encoded JKS keystore
  - `RELEASE_KEYSTORE_PASSWORD`
  - `RELEASE_KEY_ALIAS`
  - `RELEASE_KEY_PASSWORD`
  When those secrets are present the workflow switches to
  `./gradlew :app:assembleRelease`.

## Pair with the iSpindle

1. On the **Home** tab, tap **Start**. A persistent notification confirms
   the server is listening; the tab shows the phone's current IP.
2. Put the iSpindle in config mode: hold it horizontally at power-on for
   ~20 s. It raises an AP named `iSpindel`.
3. Connect the phone to that AP, open `http://192.168.4.1/` in a browser.
4. In the iSpindle config portal set:
   - **WiFi SSID / password** of your home network
   - **Service Type** = HTTP
   - **Server Address** = the phone IP shown on Home
   - **Server Port** = `9501`
   - **URI** = `/` (or leave default — any path is accepted)
   - **Sample Interval** = `60` seconds for initial testing
5. Save. The iSpindle reboots, joins WiFi, wakes, POSTs, sleeps.
6. Reconnect the phone to the same home WiFi. Within one interval the
   first reading appears on **Home**.

Phone and iSpindle must share a subnet. If your router isolates guest
WiFi or uses AP isolation, turn it off for the iSpindle's SSID. DHCP lease
changes on the phone will break reception — reserve the phone's MAC in
the router for a static address.

## Calibrate

The iSpindle measures **tilt angle**, not gravity. You fit a polynomial
`SG = a + b·angle + c·angle² + d·angle³` per device from reference
solutions of known SG:

1. Float the iSpindle in distilled water at 20 °C. Let it settle through
   one reading cycle, read the angle from the Home tab, tap the device
   on the **Devices** tab → **Calibrate**, tap **Use this** to pre-fill
   the angle, enter `1.000` as the SG, Add.
2. Mix sugar + water, measure SG with a refractometer or hydrometer,
   float, record the iSpindle's angle, add another point.
3. Repeat across the SG range you care about (1.000 → 1.080 for typical
   beer / cider). Three points is the minimum for a quadratic fit; four
   or five give R² > 0.999 in practice.
4. Tap **Fit & save**. The polynomial is applied to every future reading
   — the Home tab and Graph tab will now show SG alongside angle.

Reference-solution table (approx., 20 °C):

| table sugar g / 100 g water | SG     |
| --------------------------- | ------ |
| 0                           | 1.000  |
| 5                           | 1.020  |
| 10                          | 1.040  |
| 15                          | 1.060  |
| 20                          | 1.080  |

Always verify with a hydrometer; sugar purity varies.

## What is **not** verified

- This has been written from spec only — it has **not** been built or
  tested against a physical iSpindle on this machine. Treat the first
  run as a smoke test.
- Ktor 3.0 server API on Android (CIO engine) is known to work but the
  first run on very old Android devices may surface Netty / SLF4J
  classloader warnings — these are benign.
- BLE is **not** supported. If you later upgrade the board to an ESP32
  variant with BLE support in firmware, this app would need a new
  transport module.

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── kotlin/com/ispindle/plotter/
│   ├── IspindleApp.kt            # Application + manual DI
│   ├── MainActivity.kt
│   ├── calibration/              # Polynomial + least-squares fitter
│   ├── data/                     # Room entities, DAOs, Repository, DTO
│   ├── network/                  # Ktor HTTP server + foreground service
│   └── ui/                       # Compose screens (Home / Devices / Graph / Calibrate / Setup)
└── res/                          # Themes, icon, manifest XML
```

## License

MIT — see [LICENSE](LICENSE).
