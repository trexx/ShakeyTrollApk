# Sleepytroll Firmware Analysis

Target: `BinSLT_BT_V2_1.bin` (the current image), cross-checked against `.old` and `.new`.
Tools: radare2 6.1.8 + Ghidra 12 headless (full decompilation at `re-tools/BinSLT_BT_V2_1.bin.c`).
All addresses are for `BinSLT_BT_V2_1.bin` loaded at `0x00000000`.

## 1. Hardware / architecture

Two chips:
- **Application MCU** — an ARM Cortex-M running *this* firmware. It implements the AT command
  parser, motor PWM, sensors, timers and the telemetry protocol.
- **BLE module** — a Microchip/ISSC transparent-UART module (BM70/BM71/IS1678S family,
  `49535343-…` UUIDs). The MCU talks to it over UART with vendor AT commands
  (`AT+BLENAME`, `AT+TRANSENTER`, `AT+QFREAD`, `AT+GMR`). Strings like
  *"Wait Bluetooth firmware version read……"* / *"Bluetooth firmware version read faild!"*
  (in `.old`/`.new`) are the MCU querying the module.

So the phone talks BLE → module (transparent mode) → UART → MCU. The AT commands the phone
sends (`AT+BH`, `AT+FR`, …) are parsed by the MCU, not the module.

## 2. Memory map & vectors

| Region | Range | Notes |
|--------|-------|-------|
| Flash/code | `0x00000000`–`0x0000813a` (33082 B) | image loaded at 0 |
| SRAM | `0x20000000`+ | initial SP `0x200009c0` (≈2.4 KB stack) |

Vector table @ `0x0`: SP `0x200009c0`, Reset `0x000028d4`, NMI `0x000028ee`,
HardFault `0x00004404`, SVCall `0x000028f2`, PendSV `0x000028f4`. 284 functions recovered.

Key runtime state lives in an SRAM struct based at `0x20000040` (motor/timer/sensor state);
another status block is pointed to by tables around `0x6130`–`0x6470`.

## 3. Boot / module bring-up — `FUN_000046d0`

Initialises the BLE module over UART:
- `AT+BLENAME=Sleepytroll_%s` — sets the advertised name (suffix from a device ID).
- `AT+TRANSENTER=1,1` — puts the module into transparent (data pass-through) mode.

After this the module relays raw bytes both directions and the MCU speaks the channel-tagged
line protocol (see §7).

## 4. AT command parser — `FUN_00002fd4`

Reached from the main loop / scheduler `FUN_00002d74`. It is a chain of matches
(`FUN_0000263a(buf, len, marker, "AT+XXX")`, non-zero = match) against, in order:
`AT+RESET`, `AT+BH`, `AT+MODE`, `AT+FR`, `AT+ST`, `AT+AU`, `AT+SH`, `AT+SP=L/M/S`, `AT+UPDATE`.
Command strings live at `0x3400` (`AT+BH`), `0x3418` (`AT+MODE`), `0x343c` (`AT+FR`), etc.

Confirmed argument handling:
- **`AT+FR`** → `FUN_0000035c(arg, 0, 0x10)` parses the argument **base-16**, then
  `if (val < 0x65)` (0–100) stores it as the motor-speed setpoint. Confirms FR = hex 0–100.
- **`AT+BH`** → sets the run/stop state; `01` starts, `00` stops the motor and telemetry state.
- **`AT+MODE`** → stores mode 1/2/3 (Continuous/Sensor/Baby-monitor).
- **`AT+SP=L/M/S`** → sets sleep-program state (`*DAT_0000373c = 5` for long, etc.) and
  kicks a stage machine.
- Each handler re-emits telemetry via `FUN_00003978` and often `FUN_00006470` (motor).

## 5. Motor drive — `FUN_00006470`

Drives the vibration motor PWM. Two paths:
- **Preset/stage path** (`*DAT_000065a8 == 0`): a stage index 1–5 maps to PWM compare +
  percentage:

  | Stage | PWM (`+0x50`) | % (`+2`) |
  |-------|---------------|----------|
  | 1 | `0x244` | 20 |
  | 2 | `0x1cc` | 40 |
  | 3 | `0x168` | 60 |
  | 4 | `0x0dc` | 80 |
  | 5 | `0x000` | 100 |

  (PWM compare falls as % rises → active-low drive; used by the S/M/L sleep programs to ramp.)
- **Direct path** (else): writes the raw speed byte (`*DAT_000065ac`, from `AT+FR`) straight to
  the PWM, so continuous mode tracks the FR value 0–100 exactly (matches channel-2 byte 2).

## 6. Run timer & 3-hour auto-off — `FUN_00006074`

Per-mode countdown, prescaled ÷100, one counter per mode (SRAM `0x20000040+8`, `+0xc`,
`0x20000144`). Reload value comes from `AT+ST` (via `FUN_0000753c`/`FUN_00007534`).
Two **hard-coded `cmp …,#0xB4` (180)** checks (`0x60b6`, `0x60e8`) set an auto-off flag at
SRAM `0x20000175` when the minute counter reaches 180 = **3 hours**. The MCU then stops the
motor and emits a channel-4 status the app renders as *"reached the maximum rocking time of
3 hours, needs to rest"*. See `SLEEPYTROLL_PROTOCOL.md` §4a for exact patch bytes / override.

`AT+ST` unit is **minutes** (hex byte, so ≤255). The counter is SRAM → clears on reboot.

## 6a. Sensor-mode limits (separate from the 3-hour cap)

Sensor/baby-monitor modes have their **own duration limits**, enforced independently of the
3-hour master cap, in the sensor state machine `FUN_00006140` (+ `FUN_0000602c`), operating on
an SRAM struct at `*DAT_000063a4`:

| Counter (struct off) | Threshold const | Value | Effect on reaching it |
|---|---|---|---|
| `[0x1a]` | `0x63bc` | **5000** | stops motor, stage→4, sets auto-off flag `0x20000175` |
| `[0x1c]` | `0x63c4` | **10000** | stops motor, stage→5, marks session done (`[0x25]=1`) |
| `[0x1e]` (`FUN_0000602c`) | `0x6070` | **5001** | sets stage 7 + flags, resets |

These are **duration** limits (how long it keeps rocking after a trip / total sensor session),
not trip-count limits — no "max N triggers" counter exists; the device keeps responding to
triggers, each bout and the session are time-bounded, and the 3-hour master cap bounds all of it.
The counters only advance while a sensor session is active (`[0x24]==1`). `AT+AU`/`AT+SH` set
sensitivity *thresholds* (0–4), which is a separate thing from these duration caps.

Tick base: the master timer `FUN_000063cc` is prescaled to 1 s (10800 = 3 h). The sensor
counters in `FUN_00006140` are *not* internally prescaled; their real-time meaning depends on
how often the sensor poller `FUN_000026d8` runs (not resolvable statically here — its call site
is indirect). At the ~100 Hz raw tick that's ~50 s / ~100 s; at a 1 s tick it's ~83 min / ~167 min.
**Measure on hardware** (time one trip→stop) to fix the unit.

Post-cap rest: after `[0x10]` passes 10800 (3 h), `FUN_000063cc` counts `[0x16]` to **300** before
re-enabling (`[8]=1`) — a cooldown/"needs to rest" period (≈5 min at a 1 s tick).

`FUN_000063cc` also persists a usage counter to flash at `0x1fc00` roughly hourly (const
`0x6460 = 3601`) — these are the lifetime `devTotal`/`motTime` values reported on channel 3, not
a limit.

## 7. Telemetry builder — `FUN_00003978`

Formats the outbound channel-tagged lines (format strings at `0x3de0`/`0x3e04` for ch3,
`0x7ff0`/`0x8031` for ch2, `~0x7fd4` for ch4) and pushes them to the module. Emitted ~1/s and
after each command. Layouts (device→phone):

| Ch | Format | Contents |
|----|--------|----------|
| 1 | `1,%s%s%04x` | serial + build id + CRC16 (e.g. `1,586949573-28360.0.94ffa`) |
| 2 | `2,%02x×8 %04x` | battery, runState(1/3), speed, soundSens, moveSens, H, M, S + CRC16 |
| 3 | `3,%02x%02x%02x %04x%04x %02x%02x%02x %04x` | mode/stage, mic, batt-cycles, total-time, motor-min + CRC16 |
| 4 | `4,OK` / `4,%02x…` | command ack / status (incl. 3-hour notice, `AT+QFREAD` OTA pulls) |

Channel-2 byte map (the important one) is tabulated in `SLEEPYTROLL_PROTOCOL.md` §4.

## 8. CRC

`CRC-16/MODBUS`: init `0xFFFF`, poly `0xA001` (reflected 0x8005), no final XOR; appended as
`%04x` over the packet's data bytes. (Taken from the app's `CrcHelper.crc16`; matches the
firmware trailers.) Outbound *commands* carry no CRC.

## 9. OTA update (device-pull)

1. Phone sends `AT+UPDATE=<len>;` (total image bytes, decimal).
2. **Device drives the transfer**: it emits `4,AT+QFREAD=<offset>;` requests; the phone replies
   by writing the **64-byte** chunk at that offset to the write characteristic.
3. Device signals completion with `DOWN` (success) or `OTA-ERROR` on channel 4.

The image is a raw Cortex-M binary (same layout as these `.bin` files). A modified image must
keep whatever integrity the bootloader checks; test on a spare before relying on it.

## 10. Firmware variants (feature diff)

| Feature | `.old` (35018 B) | `.bin` current (33082 B) | `.new` (33826 B) |
|---------|:---:|:---:|:---:|
| `AT+BH/MODE/FR/ST/AU/SH/SP/RESET/UPDATE` | ✓ | ✓ | ✓ |
| `AT+BR` (light sensitivity) | ✓ | — | — |
| `AT+GMR` (module version query) | ✓ | — | ✓ |
| `AT+READ` | ✓ | — | ✓ |
| Channel 5 (`5,%02x` light value) | ✓ | — | — |
| "Bluetooth firmware version read…" | ✓ | — | ✓ |

Implication: **the shipping app (1.6.7) still sends `AT+BR` (light sensitivity), but the current
firmware `.bin` has no `AT+BR` handler and no channel 5** — light sensitivity is effectively a
no-op on this firmware; it existed in `.old`. Treat `AT+BR` and the light sensor as
version-dependent.
