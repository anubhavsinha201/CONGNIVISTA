# 001 — Toolchain bring-up

`wayfinder:task` · AFK where possible · Status: **blocked-in-practice (needs a physical Android phone)**

## Question

Get Flutter, the Android SDK, and adb installed, and get *any* APK onto the target phone.
The first `flutter run` on a physical device is where OEM drivers, Gradle, and signing
config all fail at once — this ticket exists to absorb that cost once, deliberately,
rather than discover it mid-demo.

Resolution should record: which machine, which phone (model + Android version), SDK
paths, and any driver/signing gotchas hit along the way.

Blocked by: none — frontier.
Blocks: 002 (First compile of the Dart), 013 (ESP32 firmware, via 003).
