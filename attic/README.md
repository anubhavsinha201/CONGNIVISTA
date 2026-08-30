# attic — retired, not deleted

Nothing in here is on any build path. It is kept because deleting history is
harder to undo than ignoring it, and because two of these files are evidence
for decisions recorded elsewhere.

## `congnivista/`

`config.py`, `util.py`, `complete.ipynb`, and the original `README.md` belong to a
**different project** — PyTorch, `resNet1D`, 5 classes, 100 Hz, 10 s windows, lead II.
ArogyaX is TensorFlow, binary, 250 Hz, 30 s, single-lead. They arrived in the very
first `Baseline: current state before wayfinder execution begins` commit and were never
part of this codebase; `torch` is not a dependency anywhere in it.

They were moved here rather than removed because the repository root is not the place
to decide someone else's project is worthless. Note `util.py` is broken on its own
terms regardless: `torch.random.seed(seed)` takes no argument (`complete.ipynb`, which
it was transcribed from, correctly calls `torch.manual_seed`).

## `replay/`

`af_A00004_250hz.raw` was the first bundled demo trace — a genuine PhysioNet/CinC 2017
AF recording, superseded on 2026-08-30 for one reason: it produced **exactly 30 RR
intervals** against `Policy.kMinRrIntervals >= 30`. It cleared the gate by nothing, so a
single missed R peak on stage would have turned the flagship AF demo into a RETAKE.

Replaced by `A02501` (49 intervals, SQI 0.9655, irregularity 0.9431, mean HR 99.0 bpm —
every gate cleared by at least 30%). `ml/reference/export_replay_trace.py` now refuses
to emit a trace with less than 25% headroom on any gate, so this cannot recur.

Kept as the worked example behind that rule.
