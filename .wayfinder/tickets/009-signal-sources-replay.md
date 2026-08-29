# 009 — Signal sources and replay traces

`wayfinder:task` · Status: **`SignalSource`/`ReplaySource` ported and verified against a
real, labelled AF recording** (Kotlin, ticket 019's module 8 pulled forward — see below)

## Question

Build the `SignalSource` interface, `ReplaySource`, and bundle real labelled AF traces.
The replay-first destination runs entirely through this — it's what lets the whole
software leg (capture → tier → Tamil → sync → dashboard) be demonstrated before any
BLE/firmware work lands.

~~Blocked by: 002 (First compile of the Dart)~~ — superseded; 002 no longer applies
after the Kotlin pivot (ticket 019). This never depended on Policy (module 3, currently
paused by user request), so it was pulled forward out of ticket 019's original ordering.

## Resolution (2026-08-30)

**`com.arogyax.data.SignalSource`** (`android/app/src/main/kotlin/com/arogyax/data/SignalSource.kt`):
a one-method interface (`captureEcg(): DoubleArray`) so a live BLE source (ticket 014,
not built) and a replay source are interchangeable to everything above them — the point
CLAUDE.md itself makes calling this "the most important abstraction in the app."
**`ReplaySource`** depends only on a generic `() -> InputStream`, not
`android.content.res.AssetManager` directly, so it stays a plain JVM-testable class with
no Android instrumentation needed — same reasoning as the signal-chain modules. The real
Android-asset-backed wrapper is a one-line addition once ticket 010's UI exists.

**Bundled asset is a real recording, not synthetic** — `docs/PRODUCT.md`/CLAUDE.md's
"Demo integrity" requirement (AF cannot be induced in a healthy teammate on stage).
`ml/reference/export_replay_trace.py` pulls PhysioNet/CinC 2017 record **A00004**
(`REFERENCE.csv` label `A`, confirmed programmatically, not assumed), resamples
300→250 Hz, and stops there — deliberately NOT filtered or normalised the way
`prepare_cinc2017.py`'s training path does, because a replay trace stands in for what
the ESP32 itself would send: raw, so the app's own `SqiAnalyser`/`FilterChain` do their
own filtering exactly as on a live capture. Amplitude checked, not assumed, to fit
`SqiAnalyser`'s rail threshold before shipping it (`android/app/src/main/assets/replay/af_A00004_250hz.raw`,
15,000 bytes, int16 LE, 250 Hz, 30 s).

**`ReplaySourceTest.kt` proves the point of this ticket, not just that the file loads:**
running the real bundled recording through the already-verified signal chain
(`SqiAnalyser` → `PanTompkins` → `RrAnalyser`) gives, on real measured output:

| | |
|---|---|
| SQI score | **0.934** (clean signal, well clear of the 0.5 gate) |
| R-peaks detected | 31 |
| RR intervals | 30 |
| Mean HR | 63.4 bpm (normal — irregularity is the finding here, not rate) |
| **Irregularity score** | **0.8136** (well above the 0.5 gate a real AF recording should clear) |

Does not check a final tier — Policy (module 3) is paused by user request. Checks
`irregularityScore >= 0.5` directly, the same constant `Policy.kRrIrregularityGate` uses.
`gradle testDebugUnitTest`: **9/9 tests passing** across all three test classes
(4 signal chain + 3 PPG/fusion + 2 replay).
