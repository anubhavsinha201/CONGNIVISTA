# 013 — ESP32 firmware

`wayfinder:task` · Status: **blocked**

## Question

250 Hz timer-driven ECG sampling, MAX30102 at 100 Hz, BLE GATT per `contracts/ble.md` and
`contracts/ppg.md`. Simpler than originally scoped: no IMU, one I²C device instead of two.

**Both streams must timestamp from one `millis()`** — pulse deficit is meaningless
otherwise (`ppg.md` §4), and with the IMU gone the PPG now carries motion detection too,
so its timing matters more than it used to.

Blocked by: 003 (Prove the AD8232), 001 (Toolchain bring-up — for PlatformIO).
~~006 (Inferred motion gate)~~ — closed; the 4-byte status frame in `contracts/ble.md`
§4 is now settled and ready to implement against.
