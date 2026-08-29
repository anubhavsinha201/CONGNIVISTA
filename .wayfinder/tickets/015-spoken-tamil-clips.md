# 015 — Spoken Tamil clips

`wayfinder:task` · Status: **6 real Tamil audio clips generated and verified — ticket
011's human review is the only thing left before any of this can reach a real patient**

## Question

Generate the spoken form of the Tamil string table once, bundle the audio clips into the
APK, and play the one matching the model's output tier — zero network dependency at
screening time, matching the rest of the offline flow. External dependency: an
ElevenLabs account for Tamil TTS generation.

Deliberately sequenced after written Tamil (ticket 011) so voice never blocks the
in-app result screen, which needs no external account at all.

Blocked by: 011 (Tamil string table) — **still true, and now the only real blocker**;
the ElevenLabs key's permission scope was the other one and is resolved.

## Progress (2026-08-30)

User provided a real ElevenLabs API key directly in chat — treated as burned on arrival
(same as the Mongo/Snowflake keys earlier), stored only as `android/.env`
(gitignored, confirmed via `git check-ignore`), never written to a tracked file.

**A real architectural split, not a shortcut:** only the 5 tier strings + the supporting
line (contracts/tiers.md §3, no dynamic content) are candidates for ElevenLabs-generated,
pre-bundled, played-back-offline-forever audio — that's what "generate once, bundle,
never call the network again" (CLAUDE.md non-negotiable 4) actually requires: fixed text.
The 33 `why.*` explanation strings (ticket 011, module 4/`Explanation.kt`) mostly embed a
live number (`{hr}`, `{sqi}`, `{deficit}`...) that's different per patient — no static
pre-rendered file can hold that. Android's own on-device `TextToSpeech` (Tamil locale,
downloadable language pack, genuinely offline once installed, synthesizes arbitrary text
including the live number at render time) is the right tool for those, not cloud TTS
called once at build time. `android/scripts/generate_tamil_audio.py`'s header comment
explains this in full and the script only ever targets the 6 fixed strings.

**Real result, not assumed:** ran the script against the actual account.
`GET /v1/voices` succeeded first (confirms the key and connectivity are both fine) and
returned a real voice ("Roger"). First `POST /v1/text-to-speech/{id}` failed with a
specific, actionable error: `401 — "The API key you used is missing the permission
text_to_speech"` — this key's ElevenLabs dashboard scope didn't include Text to Speech.
Told the user exactly that; they fixed the key's permissions directly. Re-ran: **all 6
clips generated for real** — `RED/ORANGE/YELLOW/GREEN/RETAKE/SUPPORTING_LINE.mp3` in
`android/app/src/main/assets/audio_DRAFT/`, 62–111 KB each, each one's first three bytes
verified as a genuine `ID3` MP3 header (not an error body saved with a `.mp3` extension).
`TierAudioClips.kt` maps each `Tier` to its asset path — pure and testable on its own;
actually playing a clip needs `MediaPlayer` and a real `Context`, which is ticket 010's
job once the result screen exists to call it from. 2 new tests, both passing (asset path
uniqueness, and that every path it names is a real file with a real `ID3` header).
`gradle testDebugUnitTest`: **58/58 passing** overall.

**A second, larger blocker, unrelated to the key: the Tamil TEXT itself is unreviewed.**
Wrote `android/app/src/main/assets/strings/tamil_strings_DRAFT.json` — all 6 tier/
supporting strings (carried over verbatim from contracts/tiers.md's own existing DRAFT)
plus all 33 `why.*` keys (new; nothing existed for these anywhere in the repo before this).
**Every string in that file, Tamil and English, is a machine draft — written by an AI
assistant, not a native Tamil speaker or a clinician.** The file's own `_comment` says so.
CLAUDE.md non-negotiable 7 and this ticket's own sequencing (deliberately after 011)
exist specifically to stop unreviewed text like this reaching a real worker or patient.
**This does not close ticket 011.** Nothing generated from this draft — text or audio —
should be used with a real patient until that review happens. What it's actually for:
giving a Tamil-speaking reviewer and a clinician a concrete draft to correct, and proving
the technical pipeline end to end, rather than starting ticket 011 from a blank page.
