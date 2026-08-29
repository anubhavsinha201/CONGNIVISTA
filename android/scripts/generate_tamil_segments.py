"""Generates the stitchable Tamil audio vocabulary for the 10 dynamic
`why.*` explanation strings (ticket 015), from
android/app/src/main/assets/strings/tamil_audio_manifest_DRAFT.json.

Indian-Railways-style: rather than trying to compose Tamil number grammar by
hand (irregular/sandhi-heavy for two-digit numbers - "42" is one word,
"நாற்பத்திரெண்டு", not "நான்கு" + "இரண்டு"), every whole number in
`number_range` is synthesized once, correctly, by ElevenLabs' own Tamil
pronunciation - the same reason a station announcement system pre-records
whole numbers rather than trying to splice digits. At playback, a dynamic
key is not one audio file - see the manifest's own `_comment` - it is an
ordered list of these clips played back-to-back.

Same draft status as everything else this ticket has produced: see
tamil_strings_DRAFT.json's `_comment`. This script makes the clips playable,
it does not make the words reviewed.

Run:  python android/scripts/generate_tamil_segments.py
"""

from __future__ import annotations

import json
import os
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST_PATH = os.path.join(HERE, "..", "app", "src", "main", "assets", "strings", "tamil_audio_manifest_DRAFT.json")
SEGMENTS_OUT_DIR = os.path.join(HERE, "..", "app", "src", "main", "assets", "audio_DRAFT", "segments")
NUMBERS_OUT_DIR = os.path.join(HERE, "..", "app", "src", "main", "assets", "audio_DRAFT", "numbers")

MODEL_ID = "eleven_multilingual_v2"
REQUEST_DELAY_S = 0.3
MAX_RETRIES = 4


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


def pick_voice(api_key: str) -> str:
    resp = requests.get("https://api.elevenlabs.io/v1/voices", headers={"xi-api-key": api_key}, timeout=30)
    resp.raise_for_status()
    voices = resp.json().get("voices", [])
    if not voices:
        sys.exit("no voices available on this ElevenLabs account")
    return voices[0]["voice_id"]


def synthesize(api_key: str, voice_id: str, text: str) -> bytes:
    for attempt in range(MAX_RETRIES):
        resp = requests.post(
            f"https://api.elevenlabs.io/v1/text-to-speech/{voice_id}",
            headers={"xi-api-key": api_key, "Content-Type": "application/json"},
            json={"text": text, "model_id": MODEL_ID, "voice_settings": {"stability": 0.5, "similarity_boost": 0.75}},
            timeout=60,
        )
        if resp.status_code == 429 and attempt < MAX_RETRIES - 1:
            wait = 2 ** attempt
            print(f"    rate-limited, retrying in {wait}s...")
            time.sleep(wait)
            continue
        resp.raise_for_status()
        return resp.content
    sys.exit("exhausted retries against ElevenLabs rate limiting")


def main() -> None:
    load_env_file(os.path.join(HERE, "..", ".env"))
    api_key = os.environ.get("ELEVENLABS_API_KEY")
    if not api_key:
        sys.exit("ELEVENLABS_API_KEY not set - see android/.env (gitignored)")

    with open(MANIFEST_PATH, encoding="utf-8") as f:
        manifest = json.load(f)

    voice_id = pick_voice(api_key)
    os.makedirs(SEGMENTS_OUT_DIR, exist_ok=True)
    os.makedirs(NUMBERS_OUT_DIR, exist_ok=True)

    segments = manifest["fixed_segments"]
    print(f"Generating {len(segments)} fixed segment(s)...")
    for i, (seg_id, text) in enumerate(segments.items(), 1):
        out_path = os.path.join(SEGMENTS_OUT_DIR, f"{seg_id}.mp3")
        if os.path.exists(out_path):
            print(f"  [{i}/{len(segments)}] {seg_id} - already exists, skipping")
            continue
        audio = synthesize(api_key, voice_id, text)
        with open(out_path, "wb") as f:
            f.write(audio)
        print(f"  [{i}/{len(segments)}] wrote {seg_id}.mp3 ({len(audio)} bytes)")
        time.sleep(REQUEST_DELAY_S)

    lo, hi = manifest["number_range"]["min"], manifest["number_range"]["max"]
    total = hi - lo + 1
    print(f"\nGenerating {total} number word(s), {lo}-{hi}...")
    for i, n in enumerate(range(lo, hi + 1), 1):
        out_path = os.path.join(NUMBERS_OUT_DIR, f"{n}.mp3")
        if os.path.exists(out_path):
            if i % 25 == 0 or i == total:
                print(f"  [{i}/{total}] ({n}) already exists, skipping")
            continue
        audio = synthesize(api_key, voice_id, str(n))
        with open(out_path, "wb") as f:
            f.write(audio)
        if i % 25 == 0 or i == total:
            print(f"  [{i}/{total}] wrote {n}.mp3 ({len(audio)} bytes)")
        time.sleep(REQUEST_DELAY_S)

    print(
        "\nDone. REMINDER: these are pronunciation clips for UNREVIEWED DRAFT text "
        "(tamil_strings_DRAFT.json / tamil_audio_manifest_DRAFT.json). Do not wire "
        "this into a build a real worker uses before ticket 011's native-speaker + "
        "clinician review."
    )


if __name__ == "__main__":
    main()
