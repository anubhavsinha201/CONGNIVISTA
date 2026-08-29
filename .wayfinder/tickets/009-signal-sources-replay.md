# 009 — Signal sources and replay traces

`wayfinder:task` · Status: **blocked**

## Question

Build the `SignalSource` interface, `ReplaySource`, and bundle real labelled AFDB traces
into `app/assets/replay/`. The replay-first destination runs entirely through this — it's
what lets the whole software leg (capture → tier → Tamil → sync → dashboard) be
demonstrated before any BLE/firmware work lands.

Blocked by: 002 (First compile of the Dart).
