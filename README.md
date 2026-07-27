# ShakeyTrollApk

A small, self-contained Android app that controls a **Sleepytroll** Bluetooth-LE baby rocker,
built by reverse-engineering the official app, the device firmware, and a sniffed BLE session.
It speaks the device's AT-command protocol directly: start/stop rocking, set speed, mode, sleep
program, and sensitivities, and shows live status (battery, running/standby state, speed,
sensor sensitivities, run timer, serial/version, usage counters).

> **Unofficial & experimental.** Not affiliated with or endorsed by Sleepytroll. Reverse-engineered
> for interoperability with a device I own. Use at your own risk — this drives a motor near a
> sleeping infant. It does **not** remove the device's built-in safety limits (see below).

## Why?
* Official APK has a 3 second unskippable intro video play before the app is usable.
* App often died when backgrounded / idle so that intro video played quite often.
* UI Colour theme was bright white colours not ideal at night when connecting to it.
* Wanted a way to work around some of the runtime limits imposed in the firmware.
* Official app talks to external servers over plaintext HTTP (see below); this one holds no
  `INTERNET` permission at all.

## What the official app talks to

From the decompiled `com.sleepytroll.slt` **1.6.7** — a baby rocker that only ever needs a BLE
link nonetheless ships `INTERNET` + `ACCESS_NETWORK_STATE` and `android:usesCleartextTraffic="true"`:

- **Firmware metadata**, via Retrofit — `NetworkManager.BASE_URL = "http://101.34.80.93:8093/"`.
  Plaintext **HTTP to a bare IP**: no TLS, no hostname, no pinning. The call is
  `@POST("sys/file/getByFileName")` with a `fileName` query (`FirmwareApiService`), answering with
  `FirmwareResponse`/`FirmwareInfo { id, filename, fileurl, fileversion, createTime, remark, etc }`.
- **It can fire as part of connecting**, not just when you go looking for an update:
  `BleDataHandler.sendBabyOKCommand` sends the `AT+OK` handshake and then calls
  `requestFirmwareInfo()` if `GlobalMsg.newFW` is set — so an ordinary connect reaches out to that
  host before you've touched anything.
- **Firmware images** come from Tencent Cloud COS — `GlobalMsg.firmwareUrl` defaults to
  `https://yyservice-1327483774.cos.ap-guangzhou.myqcloud.com/service/BinSLT_BT_V2_1_test.bin`
  (note the `_test` image), and `UpdateViewModel.startOTAUpdate` looks up `BinSTL_BT_V2_1`, then
  hands the response's `fileurl` to `BabyRockerCommandManager` to stream at the device.
- The upshot: **the URL of the firmware that gets written to the rocker arrives over
  unauthenticated cleartext HTTP**, so anyone on the path can answer that request. Nothing else
  authenticates the image — the device just pulls whatever bytes it's handed (OTA and endpoint
  details in [`APK_ANALYSIS.md`](docs/APK_ANALYSIS.md) §5 and §6a).
- Beyond that it loads sleepytroll.com marketing/tutorial pages in WebViews (`assets/HomeUrl*.json`)
  and uses Google Play in-app updates. No analytics or crash-reporting SDK is configured.

This app implements **no OTA and no networking at all**: its merged manifest has no `INTERNET`
permission, so it can't reach any of the above even if it wanted to.

## Features

- Scan for `Sleepytroll_…` devices in a bottom sheet, connect, and auto-reconnect (3 attempts, 3 s
  apart) to the ISSC/Microchip transparent-UART GATT service. The client runs in a
  `connectedDevice` foreground service, so it keeps the link while the app is backgrounded.
- Controls: **start/stop** (`AT+BH`) on a big tap target, **speed** 0–100 % (`AT+FR`), **mode** —
  continuous / sensor / baby monitor (`AT+MODE`), **sleep program** S/M/L (`AT+SP`), **sound** and
  **movement** sensitivity 0–4 (`AT+SH` / `AT+AU`), **run timer** 0–180 min in 5-minute steps
  (`AT+ST`), and a confirm-guarded **reset** (`AT+RESET`).
- Live status from the device's channel-tagged telemetry: battery, running/standby, speed,
  sensitivities, time left on the run timer, serial + firmware version, and usage counters (motor
  minutes of 180, device total, battery cycles). Controls follow what the device reports, with a
  1.5 s window after you touch one so a stale status frame can't yank it back.
- Warning banners for **low battery** (<6 %) and the device's **3-hour "needs to rest"** notice;
  standby is shown on the main control itself. The theme follows the system light/dark setting.
- Optional **keep-alive** switch: every 2 h 45 m it re-sends the last run-timer command and
  `AT+BH=01;` to re-arm before the firmware's 3-hour runtime cap (continuous/manual mode only —
  see the protocol notes).

## How it works (protocol in brief)

The phone writes ASCII AT commands terminated with `;` to the transparent-UART write
characteristic (`49535343-8841-…`, write-without-response); the device streams back
`\r\n`-separated, channel-tagged lines on the notify characteristic (`49535343-1e4d-…`):

| Channel | Meaning |
|---------|---------|
| `1,` | identity — serial + firmware version |
| `2,` | live status — battery, run state, speed, sound/move sensitivity, run timer |
| `3,` | usage counters — mode/stage, battery cycles, total runtime, motor minutes |
| `4,` | command ack (`OK`), status codes, and the 3-hour warning |

On connect the app mirrors the official app's handshake: discover services → negotiate MTU 128 →
enable notifications → send `AT+OK;`.

Full details — the command set, telemetry byte maps, firmware findings (including the 3-hour
runtime cap and the sensor-mode duration limits), and how the official app behaves — are in the
reverse-engineering docs under [`docs/`](docs/):
[`SLEEPYTROLL_PROTOCOL.md`](docs/SLEEPYTROLL_PROTOCOL.md),
[`FIRMWARE_ANALYSIS.md`](docs/FIRMWARE_ANALYSIS.md), and
[`APK_ANALYSIS.md`](docs/APK_ANALYSIS.md).

## Building

**Toolchain** (all current as of this writing):

| | Version |
|---|---|
| Android compile/target/**min** SDK | compileSdk = 37; minSdk/targetSdk = 36 (Android 16) |
| Android Gradle Plugin | 9.3.1 |
| Gradle | 9.6.1 (via the committed wrapper) |
| Kotlin / Compose compiler plugin | 2.4.10 (Kotlin ships with AGP 9 — no separate Kotlin plugin) |
| Java bytecode target | **25** (via `jvmToolchain(25)`) |

You need the **Android SDK**. The platform for compileSdk 37 is packaged as `platforms;android-37.0`,
and AGP 9.3.1 builds with **build-tools 36.0.0** (its own default, not the newest published). With
the SDK licences accepted, AGP fetches whatever is missing during the build; to install them
up front instead:

```bash
sdkmanager "platforms;android-37.0" "build-tools;36.0.0"
```

To run Gradle you need **a JDK**; JDK 21 is the tested combination (Temurin 21 in CI, Android
Studio's bundled JBR locally). You do **not** need to install JDK 25 yourself — the Java 25 build
toolchain is auto-provisioned by the [Foojay resolver](https://github.com/gradle/foojay-toolchains)
configured in `settings.gradle`.

```bash
# point Gradle at your SDK (or set ANDROID_HOME); create local.properties once:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :app:assembleDebug     # debug APK  -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease   # release APK -> app/build/outputs/apk/release/
```

The release build runs R8 with code **and** resource shrinking (~2 MB, versus ~48 MB for the
unminified debug APK), so re-verify the BLE flow on-device after changing keep rules.

> `minSdk` is **36**, so the app installs only on **Android 16+** devices. Lower it in
> `app/build.gradle` if you need older-device support.

Debug builds accept a demo flag that fills the UI with fake telemetry — handy for looking at the
connected screen on an emulator with no BLE hardware:

```bash
adb shell am start -n com.example.bleat/.ui.MainActivity --ez demo true
```

## Releases (GitHub Actions)

Two workflows in `.github/workflows/`:

- **`ci.yml`** — builds the debug APK on pushes to `main`, on pull requests, and on demand.
- **`release.yml`** — builds a **release APK** and uploads it as the
  `sleepytroll-connect-release-apk` workflow artifact. Trigger it either by pushing a version tag
  (`git tag v1.0 && git push --tags`) or from the Actions tab ("Run workflow"). Tag builds also
  attach the APK to the matching GitHub Release, creating it with generated notes if it doesn't
  exist yet; a re-run replaces the asset.

### Signing

By default the release APK is signed with the **auto-generated debug key** — zero setup, fine for
sideloading. The catch: each build environment has its own debug key, so an APK from CI won't
update one you built locally in place (you'd **uninstall first**).

For a **stable signing identity** (so every release updates cleanly), configure a keystore once as
repository secrets — the build picks it up automatically, and falls back to the debug key when the
secrets aren't set:

```bash
# 1. Create a keystore (once). Answer the prompts; remember the passwords.
keytool -genkeypair -v -keystore release.jks -alias sleepytroll \
        -keyalg RSA -keysize 2048 -validity 10000

# 2. Add four repository secrets (via `gh`, or Settings → Secrets and variables → Actions):
gh secret set KEYSTORE_BASE64   < <(base64 -w0 release.jks)
gh secret set KEYSTORE_PASSWORD --body 'your-store-password'
gh secret set KEY_ALIAS         --body 'sleepytroll'
gh secret set KEY_PASSWORD      --body 'your-key-password'
```

Keep `release.jks` private (it's git-ignored). With those secrets present, `release.yml` decodes
the keystore and signs with it; without them, it uses the debug key.

## Sideloading

1. Download `app-release.apk` — from the GitHub Release for a tag, or from the
   `sleepytroll-connect-release-apk` artifact on the **Build Release APK** Actions run (or build it
   yourself, see above).
2. On the phone (**Android 16+**), allow installs from your browser/file manager, then open the APK.
   It installs as **Sleepytroll**.
3. Grant **Nearby devices / Location** permissions, tap the connection chip to scan, pick your
   `Sleepytroll_…` device, and control it.

## Project layout

```
app/src/main/java/com/example/bleat/
  ble/BleForegroundService.kt   – GATT client, AT commands, telemetry parsing, keep-alive
  commands/CommandModel.kt      – control definitions (toggle / slider / options / action)
  commands/CommandsViewModel.kt – command building + two-way state sync
  ui/MainActivity.kt            – permissions, BLE scanning, service binding, demo mode
  ui/HomeScreen.kt              – screen composition
  ui/components/                – StatusHeader, HeroRockingControl, ControlSections, ScanSheet
  ui/theme/                     – day/night colour schemes, Nunito type, shapes
docs/                           – protocol, firmware and official-APK reverse-engineering notes
```

## Safety

The device enforces a **3-hour runtime cap** and **sensor-mode duration limits** in firmware.
This app surfaces them but does not defeat them; the keep-alive only re-arms the runtime timer in
continuous/manual mode. There is **no OTA/firmware-flashing** capability in this app. Don't rely
on any of this for unattended operation.
