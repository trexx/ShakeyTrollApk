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
* Connectivity to external servers.

## Features

- Connect / scan / auto-reconnect to a `Sleepytroll_…` device (ISSC/Microchip transparent-UART GATT).
- Controls: **start/stop** (`AT+BH`), **speed** 0–100 (`AT+FR`), **mode** (`AT+MODE`), **sleep program**
  S/M/L (`AT+SP`), **sound** and **movement** sensitivity 0–4 (`AT+SH` / `AT+AU`), **run timer**
  minutes (`AT+ST`), **reset** (`AT+RESET`).
- Live status panel from the device's channel-tagged telemetry, with the controls two-way synced
  to what the device actually reports.
- Alerts: **low battery** (<6%), **standby**, and the device's **3-hour "needs to rest"** notice.
- Optional **3-hour keep-alive** that re-arms the run timer before the firmware's runtime cap
  (continuous/manual mode only — see the protocol notes).

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
| Android compile/target/**min** SDK | **36** (Android 16) — build-tools 36.1.0 |
| Android Gradle Plugin | 9.2.1 |
| Gradle | 9.6.1 (via the committed wrapper) |
| Kotlin | 2.4.0 (AGP built-in Kotlin) |
| Java bytecode target | **25** (via `jvmToolchain(25)`) |

You need the **Android SDK** (platform 36 + build-tools 36.1.0) and **a JDK to run Gradle**
(17–24; JDK 21 recommended). You do **not** need to install JDK 25 yourself — the Java 25 build
toolchain is auto-provisioned by the [Foojay resolver](https://github.com/gradle/foojay-toolchains)
configured in `settings.gradle`.

```bash
# point Gradle at your SDK (or set ANDROID_HOME); create local.properties once:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :app:assembleDebug     # debug APK  -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease   # release APK -> app/build/outputs/apk/release/
```

> `minSdk` is **36**, so the app installs only on **Android 16+** devices. Lower it in
> `app/build.gradle` if you need older-device support.

## Releases (GitHub Actions)

Two workflows in `.github/workflows/`:

- **`ci.yml`** — builds the debug APK on every push/PR as a build check.
- **`release.yml`** — builds a **release APK** and uploads it. Trigger it either by pushing a
  version tag (`git tag v1.0 && git push --tags`) or from the Actions tab ("Run workflow").
  Tag builds also attach the APK to a GitHub Release.

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

1. Download `app-release.apk` (from the Actions run's artifacts, or the GitHub Release).
2. On the phone (**Android 16+**), allow installs from your browser/file manager, then open the APK.
3. Grant **Nearby devices / Location** permissions, tap **Scan**, pick your `Sleepytroll_…`
   device, and control it.

## Project layout

```
app/src/main/java/com/example/bleat/
  ble/BleForegroundService.kt   – GATT client, AT commands, telemetry parsing, alerts
  commands/CommandModel.kt      – control definitions
  commands/CommandsViewModel.kt – command building + two-way state sync
  ui/MainActivity.kt            – Compose UI
```

## Safety

The device enforces a **3-hour runtime cap** and **sensor-mode duration limits** in firmware.
This app surfaces them but does not defeat them; the keep-alive only re-arms the runtime timer in
continuous/manual mode. There is **no OTA/firmware-flashing** capability in this app. Don't rely
on any of this for unattended operation.
