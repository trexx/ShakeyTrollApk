# Sleepytroll Android App Analysis

Primary target: **`com.sleepytroll.slt` v1.6.7** (decompiled with jadx). An older build,
`cn.Sleepytroll.connect` v1.0.6, is also present (same protocol, different package name and a
lighter feature set). Kotlin + Jetpack Compose + a foreground `Service` for BLE.

Package layout (1.6.7):
- `BLE/service/BleService` — the GATT client (a bound + started foreground service).
- `BLE/handler/BabyRockerCommandManager` — builds & sends AT commands (singleton).
- `BLE/handler/BleDataHandler` — parses inbound telemetry, holds `DeviceState`, broadcasts.
- `BLE/utils/HexUtils`, `BLE/utils/CrcHelper` — hex + CRC-16/MODBUS.
- `fragment/component/*` — UI (rocking dial, sensitivity, timer, OTA, verification).
- `network/*` — firmware-update server client.

## 1. BLE connection lifecycle (`BleService`)

Constants: `SERVICE_UUID 55535343-FE7D-4AE5-8FA9-9FAFD205E455` (the ISSC base; note the GATT
also exposes `49535343-FE7D-…`), `WRITE_UUID 49535343-8841-43F4-A8D4-ECBE34729BB3`,
`READ_UUID 49535343-1E4D-4BD9-BA61-23C647249616`, `MAX_DATA_LENGTH` truncates writes >128 B,
`MAX_RETRY_COUNT 3`, `RETRY_INTERVAL 3000 ms`.

Flow:
1. `connectGatt(ctx, autoConnect=false, cb, TRANSPORT_LE)`.
2. `onConnectionStateChange(CONNECTED)` → `discoverServices()`.
3. `onServicesDiscovered` → **`requestMtu(128)`**.
4. `onMtuChanged` → locate write + notify characteristics, then
   `setCharacteristicNotification(notify, true)` and write `ENABLE_NOTIFICATION_VALUE` to CCCD
   `0x2902`. (Notifications are enabled *after* MTU negotiation, not in service-discovery.)
5. Auto-reconnect: on abnormal disconnect a `reconnectRunnable` retries up to 3× every 3 s.

`writeData(bytes)`:
- Uses `writeCharacteristic` (the `…8841…` char). No explicit write type is set, so it inherits
  the characteristic's default — **Write-Without-Response** (matches the `0x52` opcodes in the
  capture). Truncates payloads >128 B (relevant to OTA's 64-B chunks).

## 2. Command builder (`BabyRockerCommandManager`)

Singleton; `sendCommand(str)` → `bleService.writeData(str.toByteArray())`. Public API → wire:

| Method | Wire command | Notes |
|--------|--------------|-------|
| `babyModeOperate(isPlay)` | `AT+BH=%02x;` | 01 start / 00 stop |
| `babySetRockingIntensity(p)` | `AT+FR=%02x;` | hex 0–100 (motor speed) |
| `babyRocker(CONTINUOUS/SENSOR/BABY_MONITOR)` | `AT+MODE=%02d;` | **decimal** 01/02/03 |
| `sendSleepProgram("s"/"m"/"l")` | `AT+SP=S;`/`M;`/`L;` | short/medium/long |
| `babySetSoundSensorSensitivity(p)` | `AT+SH=%02x;` | hex 0–4 |
| `babySetMovementSensorSensitivity(p)` | `AT+AU=%02x;` | hex 0–4 |
| `babySetLightSensitivity(p)` | `AT+BR=%02x;` | hex — **no-op on current fw** (see firmware §10) |
| `babySetModeRunTime(min)` | `AT+ST=%02x;` | **minutes** (hex), cap 180 |
| `babayReset()` | `AT+RESET` | no terminator |
| `ATOK()` | `AT+OK` | handshake ack |
| `babyUpdate(len)` | `AT+UPDATE=<len>;` | OTA start |

`BCRockerMode` values: CONTINUOUS=1, SENSOR=2, BABY_MONITOR=3 (SLEEP_PROGRAM_* map to `AT+SP`).

## 3. Inbound parsing (`BleDataHandler`)

`handleHeartData` splits notifications on `\r\n` and dispatches each line by its leading channel
digit to `handleFirstPacket … handleFivePacket`. All numeric fields are hex; many packets carry
a trailing CRC-16/MODBUS that is validated only when the length matches (otherwise skipped).

- **Packet 1 (`1,`)** — serial + verification + version code. Newer firmware encodes these as
  hex fields (`code = [2:11]`, `verificationCode = [12:16]`, `versionCode = [16:]`); the device
  in the capture emitted a human-readable `serial-hwid.x.build` string (version drift). Feeds
  `saveDeviceSN` / `checkDeviceVerificationAndHandleVersion` and the OTA version check.
- **Packet 2 (`2,`)** — live status → `SecondPacket`/`DeviceState`. Byte map (see PROTOCOL §4):
  `electricQuantity` (battery %, `<6` → low-battery notification), `rockingListening`
  (1=running, 3=standby), `rockingIntensity` (speed), `soundSensorSensitivity` (min 4),
  `movementSensorSensitivity` (min 4), `babyTime` H:M:S → `timeValue` seconds. Each field is
  also re-broadcast via `LocalBroadcastManager` actions (`ACTION_BATTERY_LEVEL`, …).
- **Packet 3 (`3,`)** — `babyRockerType`/stage, `micShowOrHidden`, `cellCount` (battery cycles),
  `devTotal` (device total runtime), `motTime` (motor minutes = the value the 3-hour cap uses).
- **Packet 4 (`4,`)** — command ack / status. Routing: contains `AT+QFREAD` → OTA pull;
  `DOWN` → OTA finished; `OTA-ERROR` → OTA error; starts `0000` → `handleStatusCommand`
  (`motorStatus = [4:6]`; the 3-hour limit arrives here as an `onMotorWarning`). Status codes
  `01`–`05` drive pop-ups.
- **Packet 5 (`5,`)** — `lightValue = hex[2:4]` (light sensor). Only present on `.old` firmware.

`DeviceState` (21 fields) is the app's single source of truth: sn, versionCode, code,
verificationCode, electricQuantity, rockingListening, rockingIntensity, soundSensorSensitivity,
movementSensorSensitivity, babyTime, timeValue, babyRockerType, babyRockerTimeDown,
micShowOrHidden, cellCount, devTotal, motTime, popValue, connectSocksMenu, isPO_10_20,
hasShownFirmwareUpdateNotification.

## 4. Run timer & the 3-hour cap (`fragment/component/poppage1Fragment`)

`MAX_TIME_MINUTES = 180`. A rotary dial picks `totalTimeInMinutes`, clamped to
`[10, 180 − motorTime]` (motorTime from packet 3). Requesting more triggers `open3hours()`
(a `hours3Fragment` dialog). On release, `onRotationStopped(min)` → `babySetModeRunTime(min)` →
`AT+ST=<min>;`. The device independently enforces 180 (firmware §6) and reports the cut-off on
channel 4. Override analysis in `SLEEPYTROLL_PROTOCOL.md` §4a.

## 5. OTA (device-pull)

`startOTA` → `startRemoteOTA` downloads firmware from a server URL (`network/`), then
`babyUpdate(len)` sends `AT+UPDATE=<len>;` (decimal byte count). The **device drives the
transfer**: it emits `4,AT+QFREAD=<offset>;` (a **byte offset**); `transOTAData(offset)` then
streams the file from that offset to EOF. Parameters (recovered from the dex + its log strings,
`逻辑包：128字节 物理包：64字节`):

- **Physical packet = 64 bytes** (one BLE write, write-without-response), **logical packet = 128 B**.
- **~100 ms `delay` after every physical packet** (`DelayKt.delay(100)`).
- Logical-packet count = `(fileSize + 127) / 128`.
- Completion: device sends `…DOWN` (success) or `…OTA-ERROR`; a further `QFREAD=<offset>` is a
  resume/retry from that offset.

Note: the app's **local-file** OTA is stubbed (`startOTA` non-remote logs
*"本地固件升级暂未实现"* — "local firmware upgrade not yet implemented"); only `startRemoteOTA`
(server download) is wired to the UI, though the `startOTAWithFile` plumbing exists. Default
remote image base name `BinSTL_BT_V2_1`. (This section documents the official app's behavior; the
`ble-at-sample` app does not implement OTA.)

## 5a. Channel-4 status codes, `popValue`, and the disconnect signal

`handleFourthPacket` routes a channel-4 line (after stripping `4,`):

- `AT+QFREAD…` / `DOWN` / `OTA-ERROR` → OTA (see §5).
- `OK` → plain command ack.
- **starts with `0000`** → `handleZeroResponse`: the **device is about to disconnect**
  (*"收到0000指令，设备即将主动断开"*) — it broadcasts `ACTION_DEVICE_WILL_DISCONNECT`. This is
  **not** a warning. (My first cut of the sample wrongly keyed the 3-hour warning on this prefix;
  corrected.)
- otherwise → `handleStatusCommand`:
  - `motorStatus = byte[4:6]`; **`== "01"` → the 3-hour "needs to rest" motor warning**
    (`onMotorWarning`).
  - a leading **status code** (`byte[0:2]`, 2-digit) is stored as **`popValue`** and drives a
    notification — so **your #4 (`popValue`) and #5 (mode activations) are one mechanism**:

    | code | `popValue` | notification |
    |------|-----------|--------------|
    | `01` | 1 | `send3minutesEndActivationNotification` (3-minute mode ended) |
    | `02` | 2 | `sendOn1SMActivationNotification` (sensor mode activated) |
    | `03` | 3 | `sendOn1SMActivationNotification` (same call — likely a copy-paste of 3SM) |
    | `04` | 4 | `sendOnSMOActivationNotification` |
    | `05` | 5 | `sendPopValue5Notification` |

    `SM` ≈ sensor mode, `SMO` ≈ sensor-mode-off/other, "3-minute" is a short-timer stage.
    `onPopCommand(popValue)` is the callback.

**Version-drift caveat:** the 1.6.7 app expects `0000…`/longer frames, but the firmware you have
emits 3-byte `4,%02x%02x%02x` status frames (`4,OK` is the only channel-4 line seen live in the
capture). So these exact byte offsets and codes are **reverse-engineered from the app and not
verified against this firmware** — capture a real event (HCI snoop while the device hits 3 h /
changes mode) to confirm before relying on them.

## 6. Handshake / verification

After connect and first telemetry, the app runs a verification path
(`checkDeviceVerificationAndHandleVersion`, `VerificationFragment`) and sends `AT+OK` — matching
the single `AT+OK;` seen in the capture right after the first `1,` line. Treat `AT+OK` as a
"client present / keep-alive" ack.

## 7. Version differences (1.0.6 vs 1.6.7)

Same transport, UUIDs and AT vocabulary. 1.0.6 (`cn.Sleepytroll.connect`) is a smaller 2-dex
build; 1.6.7 (`com.sleepytroll.slt`) adds the packet-3/5 fields, remote OTA, verification flow,
Compose UI, and the light-sensitivity control. For a clean-room client, target the 1.6.7
behavior but expect the *device's* firmware to define which commands actually do anything
(see firmware §10).
