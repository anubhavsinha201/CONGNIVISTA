# 002 — First compile of the Dart

`wayfinder:task` · AFK · Status: **blocked**

## Question

Run `flutter test` for the first time ever against `app/lib/`. ~3,900 lines of Dart have
been written and cross-validated against Python mirrors, but no Dart SDK has ever
compiled them. Fix whatever compile errors surface — expect nullability issues, `switch`
exhaustiveness on the newer enums (`PpgCorroboration`, `ClinicianOutcome`), and the
`sqlite3`/`sqlcipher_flutter_libs` API surface.

Resolution should record the error classes actually found, so the next person touching
this Dart knows the traps that were real vs. hypothetical.

Blocked by: 001 (Toolchain bring-up).
Blocks: 009, 010, 012.
