"""Generates bundled Tamil TTS audio for ArogyaX's FIXED worker-facing strings
via ElevenLabs (ticket 015), from android/app/src/main/assets/strings/tamil_strings_DRAFT.json.

Read that file's own "_comment" before using anything this script produces:
every string in it is an unreviewed machine draft, not clinician/native-speaker
reviewed text. This script's job is proving the TTS mechanism and giving a
Tamil speaker + clinician something concrete to correct - not shipping audio
to a real worker's phone.

## Why only the 6 tier/supporting strings get audio here, not all 39

The 6 tier strings (RED/ORANGE/YELLOW/GREEN/RETAKE + the supporting line) have
no dynamic content - "Go to the PHC today" never changes per patient. That is
exactly what makes them safe to render ONCE, here, and bundle as static files
played back fully offline forever after (CLAUDE.md non-negotiable 4).

The 33 `why.*` explanation strings are a different shape: most embed a live
number ("{hr} beats per minute", "{sqi}") that is different for every patient,
every visit. Pre-bundling one static audio file per key cannot represent that
number - there is no way to splice "{hr}" into an already-rendered MP3 without
re-synthesizing speech on-device anyway, at which point ElevenLabs (cloud,
called once at build time) is the wrong tool entirely: Android's own on-device
TextToSpeech engine (Tamil locale, downloadable language pack, genuinely
offline once installed, and able to synthesize arbitrary text including a
live number at render time) is the architecturally correct mechanism for
those. This script does not attempt to force cloud TTS onto content it is not
suited for - it generates nothing for the `explanations` section and says so.

Run:  ELEVENLABS_API_KEY=... python android/scripts/generate_tamil_audio.py
  (or source android/.env first - see that file, gitignored, never committed)
"""

from __future__ import annotations

import json
import os
import sys

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
STRINGS_PATH = os.path.join(HERE, "..", "app", "src", "main", "assets", "strings", "tamil_strings_DRAFT.json")
OUT_DIR = os.path.join(HERE, "..", "app", "src", "main", "assets", "audio_DRAFT")

MODEL_ID = "eleven_multilingual_v2"  # supports Tamil; confirmed by the actual synthesis below, not assumed


def load_env_file(path: str) -> None:
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())


def pick_voice(api_key: str) -> tuple[str, str]:
    """Returns (voice_id, name) - fetched from the real account, not hardcoded,
    since a voice ID that doesn't exist on this account fails the whole run."""
    resp = requests.get(
        "https://api.elevenlabs.io/v1/voices",
        headers={"xi-api-key": api_key},
        timeout=30,
    )
    resp.raise_for_status()
    voices = resp.json().get("voices", [])
    if not voices:
        sys.exit("no voices available on this ElevenLabs account")
    v = voices[0]
    return v["voice_id"], v["name"]


def synthesize(api_key: str, voice_id: str, text: str) -> bytes:
    resp = requests.post(
        f"https://api.elevenlabs.io/v1/text-to-speech/{voice_id}",
        headers={"xi-api-key": api_key, "Content-Type": "application/json"},
        json={
            "text": text,
            "model_id": MODEL_ID,
            "voice_settings": {"stability": 0.5, "similarity_boost": 0.75},
        },
        timeout=60,
    )
    resp.raise_for_status()
    return resp.content


def main() -> None:
    load_env_file(os.path.join(HERE, "..", ".env"))
    api_key = os.environ.get("ELEVENLABS_API_KEY")
    if not api_key:
        sys.exit("ELEVENLABS_API_KEY not set - see android/.env (gitignored)")

    with open(STRINGS_PATH, encoding="utf-8") as f:
        strings = json.load(f)

    voice_id, voice_name = pick_voice(api_key)
    print(f"using voice: {voice_name} ({voice_id})")

    os.makedirs(OUT_DIR, exist_ok=True)
    generated = 0
    for key, entry in strings["tiers"].items():
        text = entry["ta"]
        audio = synthesize(api_key, voice_id, text)
        out_path = os.path.join(OUT_DIR, f"{key}.mp3")
        with open(out_path, "wb") as f:
            f.write(audio)
        # Avoid printing raw Tamil to a Windows console stuck on a legacy
        # codepage (cp1252) - print the key and byte count only.
        print(f"  wrote {out_path} ({len(audio)} bytes) for key: {key}")
        generated += 1

    skipped = len(strings["explanations"])
    print(
        f"\n{generated} tier clip(s) generated (fixed text, safe to bundle offline).\n"
        f"{skipped} explanation string(s) intentionally skipped - they carry dynamic "
        f"values (a live {{hr}}, {{sqi}}, etc.) that a pre-bundled static file can't "
        f"represent. Use Android's on-device TextToSpeech (Tamil locale) for those "
        f"instead of cloud TTS - see this file's own header comment for why."
    )
    print(
        "\nREMINDER: everything just generated is from UNREVIEWED DRAFT text "
        "(tamil_strings_DRAFT.json). Do not play this for a real patient before "
        "ticket 011's native-speaker + clinician review."
    )


if __name__ == "__main__":
    main()
