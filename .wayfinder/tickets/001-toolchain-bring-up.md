# 001 — Toolchain bring-up

`wayfinder:task` · AFK where possible · Status: **blocked-in-practice (needs a physical Android phone)**

## Question

**Retargeted 2026-08-30: Android Studio + Kotlin instead of Flutter**, per ticket 019 —
the underlying need is identical (Android SDK, adb, a first app installed on the target
phone), just via Gradle/Android Studio's run flow instead of `flutter run`. Get Android
Studio, the Android SDK, and adb installed, and get *any* app onto the target phone.
The first on-device run is where OEM drivers, Gradle, and signing config all fail at
once — this ticket exists to absorb that cost once, deliberately, rather than discover
it mid-demo.

Resolution should record: which machine, which phone (model + Android version), SDK
paths, and any driver/signing gotchas hit along the way.

Blocked by: none — frontier.
Blocks: 002 (First compile of the Dart), 013 (ESP32 firmware, via 003).

**Partial, unplanned progress:** PlatformIO — the one piece of this ticket's scope that
013 actually needed — turned out to already be installed on the machine, just not on
`PATH`; confirmed working (`pio run` compiles `firmware/` clean). That does not touch
this ticket's real subject (Flutter, the Android SDK, adb, a first APK on a physical
phone) — still genuinely blocked on a phone in hand.
