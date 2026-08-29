# 013 — ESP32 firmware

`wayfinder:task` · Status: **FLASHED AND RUNNING ON REAL HARDWARE — boot verified** ·
commit `6ebf74e` · ticket 003's own job (an actual ECG trace through the SQI gate) is
still separate and not yet done

## Question

250 Hz timer-driven ECG sampling, MAX30102 at 100 Hz, BLE GATT per `contracts/ble.md` and
`contracts/ppg.md`. Simpler than originally scoped: no IMU, one I²C device instead of two.

**Both streams must timestamp from one `millis()`** — pulse deficit is meaningless
otherwise (`ppg.md` §4), and with the IMU gone the PPG now carries motion detection too,
so its timing matters more than it used to.

Blocked by: 003 (Prove the AD8232), 001 (Toolchain bring-up — for PlatformIO).
~~006 (Inferred motion gate)~~ — closed; the 4-byte status frame in `contracts/ble.md`
§4 is now settled and ready to implement against.

## Update — flashed onto real hardware, boot sequence confirmed live

The ESP32 got connected via USB, Windows recognized it as
`Silicon Labs CP210x USB to UART Bridge (COM7)` (Windows' own automatic driver handling —
the CP210x driver package downloaded earlier turned out not to be needed), and
`pio run -t upload --upload-port COM7` succeeded in 41.27s against real silicon:

```
Chip is ESP32-D0WD-V3 (revision v3.1)
Features: WiFi, BT, Dual Core, 240MHz, VRef calibration in efuse, Coding Scheme None
MAC: 70:4b:ca:56:b1:10
```

Opening `pio device monitor` immediately after (which resets the board as a side effect
of the port opening) produced the **full boot sequence, live, with no crash and no reset
loop**:

```
=== ArogyaX sensor unit booting ===
ECG pins configured (GPIO34 analog in, GPIO32/33 lead-off)
[esp32-hal-i2c] Initialising I2C Master: sda=21 scl=22 freq=100000
MAX30102 found on I2C - PPG available
BLE device name: ArogyaX-B112
BLE advertising started - look for it in a BLE scanner app
Sampling timers armed (idle until a Start command arrives)
=== Boot complete - waiting for a BLE connection ===
```

**`MAX30102 found on I2C - PPG available` is the load-bearing line here** — that's not a
compile-time guess, that's the real chip at SDA=21/SCL=22 answering a real I2C
transaction. Pin item 1 below is resolved for the I2C pins specifically; the AD8232
analog/lead-off pins (34/32/33) are wired per the same confirmed map but haven't been
electrically exercised yet (no electrodes attached during this boot). The device then sat
idle and healthy for the full monitor window with no reboot — the failure mode a bad
`esp_timer` setup or a stack overflow would have produced.

**Not yet tested:** whether the BLE advertisement is actually visible/connectable to a
real central (needs a phone with a BLE scanner, e.g. nRF Connect — the firmware's own
claim that advertising "started" is unconfirmed from the outside), whether the control
opcodes work, and whether ECG/PPG frames actually stream correctly once started. Those
are the next concrete checks, not yet run.

## Resolution (compiles; now also boots clean on real hardware)

**Update:** PlatformIO turned out to already be installed on this machine (just not on
`PATH`) — installed 001's one real dependency for this ticket without needing the rest of
001's Android/Flutter scope. `pio run` against `firmware/` **succeeded on the first true
attempt** (`SUCCESS`, 6.06s, board `esp32dev`, resolved `framework-arduinoespressif32
@3.20017`, `SparkFun MAX3010x @1.1.2`, `ESP32 BLE Arduino @2.0.0`). RAM 12.2%, Flash
88.5% (1,160,285 / 1,310,720 B — tight, mostly the classic BLE stack; a custom partition
table or NimBLE-Arduino are the fixes if that becomes a real constraint later).

This resolves most of the "named, not resolved" list below by construction — a wrong
`getValue()` return type, a wrong `esp_timer_create_args_t` field, or a wrong MAX3010x
method signature would all have been compile errors, and none were. **What compiling
does NOT prove:** that the timing is actually right, that the BLE stack behaves under a
real connection, that the MAX30102 initializes correctly against real silicon, or that
any of the five pin numbers are wired correctly — that needs an actual board, which is
still ticket 003's job. One real (and initially confusing) non-code failure hit along the
way: the first `pio run` failed with `WinError 183` creating `.pio/build/esp32dev` —
OneDrive racing PlatformIO's directory creation, since this repo lives inside a
OneDrive-synced folder. Not a code bug; a plain retry succeeded. Worth knowing if it
recurs on a clean `.pio` wipe.

Every other AFK ticket in this tracker had a Python mirror to run the result against
(`ml/reference/validate_*.py`) — the whole point of the architecture described at the top
of CLAUDE.md. There still isn't an equivalent for firmware behavior once it's running —
compiling is real evidence, but it is not the same evidence a flash-and-run against the
real AD8232 gives. What's here is now source that provably builds against
`contracts/ble.md` and `contracts/ppg.md` (both LOCKED), not yet a tested runtime result.

`firmware/platformio.ini` + `firmware/src/main.cpp`, ~370 lines. Implements:

- 250 Hz ECG sampling and 100 Hz PPG sampling via `esp_timer` callbacks
  (`ESP_TIMER_TASK` dispatch — a dedicated high-priority task, not a hardware ISR, so
  `analogRead()`/`Wire` calls inside the callback are safe). Chosen over the more
  commonly-tutorialed hardware-timer-ISR-plus-flag pattern specifically because that
  pattern defers the actual sample read to `loop()`, which reintroduces the sampling
  jitter CLAUDE.md's own "timer-driven, not `delay()`-based" rule exists to avoid, the
  moment a BLE `notify()` call takes a few ms.
- The exact ECG (56 B), status (4 B), and PPG (46 B) frame layouts from `ble.md`
  §3/§4 and `ppg.md`§3, byte-packed with explicit little-endian shifts (not a packed
  struct + `memcpy`) so each line traces to one row of the contract's offset table.
- Both streams timestamped from the same `millis()`, read at the first sample of each
  frame — `ppg.md` §4's "shared clock" requirement.
- Lead-off read from the AD8232's `LO+`/`LO-` pins in hardware every ECG tick, per
  `ble.md` §4's "do not infer electrode detachment in software" instruction.
- Status frame's bit2 left reserved, not repurposed, matching `ble.md` §4's point about
  old firmware failing visibly rather than silently setting a bit nothing reads.
- The control opcodes (start / stop / start-for-N-seconds) from `ble.md` §5, firmware
  booting not-streaming.
- Double-buffered frame handoff between the sampling timers and `loop()`'s BLE sends,
  so a slow `notify()` call can never tear a frame mid-write.
- Graceful PPG-sensor-not-found handling: ECG-only capture still works if the MAX30102
  isn't detected at boot, same as any other capture with no simultaneous PPG.

**Explicitly out of scope, not attempted:** the USB-serial fallback (`ble.md` §7) —
adding a second, equally-unverified transport on top of an already-unverified primary
one would only compound the risk, not reduce it.

**Resolved by the successful compile (2, 3, 4, 5 below) vs. still open (1):**

1. **Partially resolved.** I2C pins (SDA=21/SCL=22) are now confirmed correct against
   real silicon — the MAX30102 answered. The AD8232 pins (34/32/33) are wired per the
   same confirmed map but still electrically unverified — no electrodes were attached
   for this boot. That's ticket 003's specific job.
2. ~~SparkFun MAX3010x API~~ — resolved at `@1.1.2`, compiled clean.
3. ~~`getValue()` return type~~ — `std::string` was correct for the resolved
   `ESP32 BLE Arduino @2.0.0`.
4. ~~`esp_timer_create_args_t` fields~~ — compiled clean against the resolved esp-idf.
5. ~~`ADC_11db` naming~~ — resolved, compiled clean.

What's still genuinely unverified: real timing behavior under load, real BLE connection
stability with an actual central, whether streaming/control actually works, and the
AD8232-side pins. None of that is provable without a phone-side BLE test and ticket 003's
electrode work.

Pushed as `6ebf74e` (source) and later commits (pin fix, boot prints, this update) on
`main`.
