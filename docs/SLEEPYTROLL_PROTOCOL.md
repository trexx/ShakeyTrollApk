# Sleepytroll BLE Protocol — Reverse-Engineering Notes

Reconstructed from four independent sources that agree with each other:

1. `btsnoop_hci.log` — live BLE capture of the official app driving a device.
2. Firmware strings/decompilation — `BinSLT_BT_V2_1.bin` (ARM Cortex-M).
3. Decompiled APK 1.6.7 (`com.sleepytroll.slt`) — `BabyRockerCommandManager`, `BleDataHandler`, `BleService`.
4. Vendor notes in `BinSLT_BT_V2_1.txt`.

---

## 1. Transport / GATT

The device uses a **Microchip/ISSC "Transparent UART" BLE module** (BM70/BM71/IS1678S
family). Its signature is the `49535343-…` base UUID. The module bridges BLE to the
application MCU over a UART; the MCU implements the AT command parser and the telemetry.

| Role | UUID | Handle (in capture) | Properties |
|------|------|--------------------|------------|
| Transparent service | `49535343-FE7D-4AE5-8FA9-9FAFD205E455` | 0x0025 | — |
| **Write** (phone → device) | `49535343-8841-43F4-A8D4-ECBE34729BB3` | 0x0027 | Write **Without Response** |
| **Notify** (device → phone) | `49535343-1E4D-4BD9-BA61-23C647249616` | 0x0029 | Notify (CCCD 0x002a) |

Notes:
- The app calls the notify characteristic `READ_UUID`; it is notify, not read.
- Writes use ATT **Write Command (0x52)** — i.e. `WRITE_TYPE_NO_RESPONSE`. Using
  write-with-response also works on some stacks but the official app uses no-response.
- Advertised name is `Sleepytroll_XXXX`. The capture device MAC was `94:C9:60:EA:D5:3E`.
- Two other vendor services exist in the GATT (`23F16E53-…`, `5833FF…`) — unused for
  normal control (likely module/OTA control).

## 2. Connection & handshake (from the capture)

1. Connect, discover services.
2. Enable notifications: write `0x0001` to the CCCD (handle 0x002a).
3. Device immediately begins streaming telemetry lines (`1,…` then `2,…` then `3,…`).
4. Phone sends **`AT+OK;`** once — a handshake/keep-alive acknowledgement.
5. From then on: phone sends AT commands; device replies `4,OK\r\n` and keeps streaming
   `2,…`/`3,…` roughly once per second.

## 3. Command set  (phone → write char 0x0027)

ASCII text, **terminated with `;`** (except `AT+OK` / `AT+RESET`, which the 1.6.7 app
sends without a terminator — the parser tolerates both). No CRC on outbound commands.

| Command | Action | Argument | Confirmed by |
|---------|--------|----------|--------------|
| `AT+OK;` | handshake / ack | none | app `ATOK()`, capture |
| `AT+BH=01;` / `AT+BH=00;` | **start / stop rocking** | `%02x` (01=run, 00=stop) | app `babyModeOperate`, capture |
| `AT+FR=%02x;` | rocking **intensity / motor speed** | **hex** `00`–`64` (0–100) | app `babySetRockingIntensity`; fw parses base-16 and clamps `< 0x65` |
| `AT+MODE=%02d;` | operating mode | **decimal** `01`=Continuous, `02`=Sensor, `03`=Baby-monitor | app `BCRockerMode` |
| `AT+SP=L;` / `AT+SP=M;` / `AT+SP=S;` | sleep program long / medium / short | letter | app `sendSleepProgram` |
| `AT+SH=%02x;` | **sound** sensor sensitivity | hex `00`–`04` | app `babySetSoundSensorSensitivity` |
| `AT+AU=%02x;` | **movement** sensor sensitivity | hex `00`–`04` | app `babySetMovementSensorSensitivity` |
| `AT+BR=%02x;` | **light** sensitivity | hex | app `babySetLightSensitivity` |
| `AT+ST=%02x;` | mode run-time / timer | hex | app `babySetModeRunTime` |
| `AT+RESET` | reboot device | none | app `babayReset` |
| `AT+SLEEP=1` | power-saving mode | — | vendor txt |
| `AT+UPDATE=<len>;` | begin OTA of `len` bytes | decimal | app `babyUpdate` |

**Discrepancies worth testing on hardware:**
- Mode numbering: the vendor txt says `00`=Continuous / `01`=Sleep / `02`=Sensor, but the
  shipping 1.6.7 app sends `01`/`02`/`03` (Continuous/Sensor/Baby-monitor). Trust the app
  for current firmware; verify each value against the physical device.
- The txt labels both `AU` and `SH` "movement sensor". The app disambiguates: **`SH` = sound**,
  **`AU` = movement**.

**Module-internal AT commands** (MCU → BLE module, seen in firmware — *not* something a phone
client sends): `AT+BLENAME=Sleepytroll_%s`, `AT+TRANSENTER=1,1`, `AT+QFREAD=%d;`.

## 4. Telemetry / responses  (device → notify char 0x0029)

ASCII lines, each ending `\r\n`, each prefixed with a **channel digit + comma**. The MCU
multiplexes four logical channels over the one transparent pipe. Split incoming data on
`\r\n`, then switch on the leading digit.

### Channel 1 — identity
`1,<serial>-<hwid>.<n>.<build>` — e.g. `1,586949573-28360.0.94ffa`. Serial number + firmware
version. Format `1,%s%s%04x` (two strings + CRC16).

### Channel 2 — live status  ← the important one
Firmware format `2,%02x%02x%02x%02x%02x%02x%02x%02x%04x` = 8 data bytes + CRC16.
Example `2,5d01640203012e1a9dd0`:

| Byte | Hex | Field | Meaning |
|------|-----|-------|---------|
| 0 | `5d` | battery % | 0–100 (93 here). `< 6` triggers low-battery warning |
| 1 | `01` | run state | **1 = running/rocking, 3 = stopped/standby** |
| 2 | `64` | intensity | current motor speed 0–100 (mirrors `AT+FR`) |
| 3 | `02` | sound sensitivity | current `AT+SH`, clamped 0–4 |
| 4 | `03` | movement sensitivity | current `AT+AU`, clamped 0–4 |
| 5 | `01` | timer H | hours (hex) |
| 6 | `2e` | timer M | minutes (0x2e = 46) |
| 7 | `1a` | timer S | seconds (0x1a = 26) → `timeValue = H*3600 + M*60 + S` |
| 8–9 | `9dd0` | CRC16 | over the 8 data bytes |

Observed in the capture: byte 1 went `01`→`03` when `AT+BH=00;` (stop) was sent, and byte 2
tracked `AT+FR` exactly (`4c`→`64`). This is how you read back current state.

### Channel 3 — usage counters
Firmware format `3,%02x%02x%02x%04x%04x%02x%02x%02x%04x`. The app decodes: baby-rocker
type/stage, mic show/hide, battery cycle count, device total runtime, motor minutes. Exact
byte offsets drift slightly between firmware/app versions; treat as cumulative counters.

### Channel 4 — command acknowledgement
`4,OK\r\n` after a successful command (also `4,<hex>…` status forms). This is the ACK for the
AT command you just wrote.

**CRC16** — the app validates channels 2/3 with `CrcHelper.crc16` over the data bytes,
comparing against the trailing `%04x`. Not needed to *send* commands (they carry no CRC);
only to validate received telemetry, and validation is skipped when the length doesn't match.

## 4a. Run timer and the 3-hour auto-off

**Setting the run length:** `AT+ST=<minutes>;` where `<minutes>` is a **hex byte of minutes**
(app `babySetModeRunTime(timeInMinutes)` → `AT+ST=%02x;`; UI shows `formatTime(min*60)`).
Range in the app is **10…(180 − motorTime)** minutes. The remaining time counts down in
channel-2 bytes 5/6/7 (H:M:S).

**The 3-hour cap is real and enforced in two places:**
- App: `poppage1Fragment.MAX_TIME_MINUTES = 180`; it clamps any requested time to
  `180 − motorTime` and pops a "3 hours" dialog (`open3hours()` / `hours3Fragment`).
  `motorTime` = cumulative motor minutes from channel-3 telemetry.
- Firmware: the run-timer tick `fcn.00006074` (called from scheduler `fcn.00002d74`) has a
  **hard-coded `cmp …, 0xB4` (180)** at two sites and sets an auto-off flag (RAM `0x20000175`,
  pointer at ROM `0x646c`) when the minute counter reaches 180. Because it's a code constant,
  sending a larger `AT+ST` value alone will **not** bypass it.

  | Site | Addr | Bytes | Instruction |
  |------|------|-------|-------------|
  | A | `0x60b6` | `b4 28 03 d1` | `cmp r0,#0xb4` ; `bne +3` |
  | B | `0x60e8` | `b4 29 03 d1` | `cmp r1,#0xb4` ; `bne +3` |

  The minute counter lives in SRAM (`0x20000040` region), so it clears on reboot/power-cycle.

**Overriding it — three options, least to most invasive:**
1. **Re-arm before 3 h (non-invasive):** the counter is in RAM, so `AT+RESET` (then reconnect),
   or stop+start (`AT+BH=00;`→`AT+BH=01;`) and re-issue `AT+ST`, gives a fresh budget. Schedule
   it every ≤ ~2h45m for effectively unlimited runtime. Validate by watching channel-3 motorTime
   drop back toward 0 after the re-arm.
2. **Raise the cap:** patch both compare immediates `0xB4 → 0xFF` (file offsets `0x60b6` and
   `0x60e8`, byte `b4 → ff`) → ~4.25 h ceiling. Reflash via `AT+UPDATE` OTA.
3. **Disable the auto-off (permanent):** make each guard branch unconditional so the flag write
   is never reached — patch the `bne` (`d1`) after each compare to `b` (`e0`): byte at `0x60b9`
   and `0x60eb`, `d1 → e0`. Reflash via OTA.

> The 3-hour auto-off is a safety limit on an unattended motor near an infant. Prefer option 1.
> Options 2/3 modify firmware; the OTA path may checksum the image (`BleDataHandler` computes a
> CRC), so a patched build might be rejected — test on a spare/at your own risk.

## 5. Firmware facts (`BinSLT_BT_V2_1.bin`)

- Raw ARM Cortex-M image, loads at `0x00000000`; initial SP `0x200009c0`, reset `0x000028d4`.
- **AT command parser: `FUN_00002fd4`** (chained string matches for RESET/BH/MODE/FR/ST/AU/SH/SP/UPDATE).
  The `AT+FR` branch parses its argument base-16 and clamps `< 0x65`, confirming FR = hex 0–100.
- **Module init: `FUN_000046d0`** — sets `AT+BLENAME=Sleepytroll_%s` and `AT+TRANSENTER=1,1`.
- OTA: app downloads firmware from a server URL, sends `AT+UPDATE=<len>;`, then streams
  64-byte packets to the write characteristic; device replies on channel 4.

---

## 6. Tooling installed in this repo

- **radare2 MCP** — installed via `r2pm -ci r2mcp`; registered in `.mcp.json` (`r2pm -r r2mcp`).
  Restart Claude Code to load it. Tools: `open_file`, `decompile_function`,
  `disassemble_function`, `xrefs_to`, `list_strings`, `analyze`, …
- **Ghidra (headless)** — `re-tools/ghidra-decompile.sh <bin>` decompiles a firmware to
  `re-tools/<bin>.c` (used a private JDK 21 at `~/.local/jdk`; the system had only stub JVMs).
  A pre-analyzed project is at `ghidra-project/SleepytrollFW.gpr` — open the GUI with
  `source re-tools/env.sh && "ghidra_12.0_PUBLIC/ghidraRun"`.
- **jadx** — at `~/.local/jadx`, used to decompile the APK.
- **Ghidra MCP** *(not installed)* — `pyghidra-mcp` needs JPype1, which won't build here
  (Python 3.14, no `g++`/`python3-devel`). To enable: `sudo dnf install gcc-c++ python3-devel`,
  then `pipx install pyghidra-mcp` and add it to `.mcp.json` with `GHIDRA_INSTALL_DIR` and
  `JAVA_HOME=~/.local/jdk`. Until then, radare2 MCP is the interactive RE bridge and the
  Ghidra headless script covers batch decompilation.
