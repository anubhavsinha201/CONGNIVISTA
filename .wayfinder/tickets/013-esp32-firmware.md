# 013 — ESP32 firmware

`wayfinder:task` · Status: **CODE DRAFTED, UNVERIFIED** · commit `6ebf74e` · blocked
on 001 (PlatformIO, to compile) and 003 (hardware, to test) for actual verification

## Question

250 Hz timer-driven ECG sampling, MAX30102 at 100 Hz, BLE GATT per `contracts/ble.md` and
`contracts/ppg.md`. Simpler than originally scoped: no IMU, one I²C device instead of two.

**Both streams must timestamp from one `millis()`** — pulse deficit is meaningless
otherwise (`ppg.md` §4), and with the IMU gone the PPG now carries motion detection too,
so its timing matters more than it used to.

Blocked by: 003 (Prove the AD8232), 001 (Toolchain bring-up — for PlatformIO).
~~006 (Inferred motion gate)~~ — closed; the 4-byte status frame in `contracts/ble.md`
§4 is now settled and ready to implement against.

## Resolution (partial — code only, not verified)

**This ticket is not actually closeable from here, and is not being marked closed.**
Every other AFK ticket in this tracker had a Python mirror to run the result against
(`ml/reference/validate_*.py`) — the whole point of the architecture described at the top
of CLAUDE.md. There is no equivalent for firmware C++: nothing in this environment can
compile it (no PlatformIO — confirmed absent, `.wayfinder/map.md`'s own "Ground truth"
table), let alone flash it and watch a real AD8232 trace come back. What's here is
source written carefully against `contracts/ble.md` and `contracts/ppg.md` (both LOCKED),
not a tested result.

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

**Named, not resolved — the specific things to check first when this actually gets
compiled:**

1. Pin numbers in the `PIN ASSIGNMENTS` block at the top of `main.cpp` are this file's
   own invention, not sourced from any contract or wiring diagram — verify against the
   real breadboard from ticket 003 before flashing.
2. The SparkFun MAX3010x library's `setup()`/`check()`/`getFIFOIR()`/`nextSample()`
   calls are written against that library's long-stable public API, but the exact
   version PlatformIO resolves (unpinned in `platformio.ini`) hasn't been checked.
3. `BLECharacteristic::getValue()` is called expecting `std::string`; some arduino-esp32
   core versions have returned `String` there instead — one-line fix if so.
4. `esp_timer_create_args_t`'s field set is written against the long-stable esp-idf
   shape; verify against whatever esp-idf version ships with the resolved core.
5. `ADC_11db` attenuation enum naming has drifted across arduino-esp32 major versions
   (2.x vs. 3.x) — verify it resolves, or use whatever the installed core calls it.

None of these are guesses I'm confident enough in to call this "done." They're the
punch list for whoever runs `pio run` first.

Pushed as `6ebf74e` on `main` — as source to review and compile against, not as a
verified result.
