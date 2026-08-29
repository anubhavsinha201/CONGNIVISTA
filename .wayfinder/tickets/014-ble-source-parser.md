# 014 — BLE source and frame parser

`wayfinder:task` · Status: **blocked**

## Question

The `BleSource` implementation of `SignalSource`, plus the frame parser. Must use
`Endian.little` explicitly (Dart's `ByteData` defaults to big-endian; the ESP32 is
little-endian native), and a sequence gap must invalidate the window rather than being
silently concatenated across — a dropped frame fabricates a short RR interval that looks
exactly like AF (`ble.md` §3).

Blocked by: 013 (ESP32 firmware — need real frames to parse), 009 (Signal sources).
