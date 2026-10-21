#!/bin/sh
# Remakes the Ogg Opus fixtures of the audio tests the way the backend encodes episode audio
# (backend/src/vovoscan/worker/voice/audio.py): ffmpeg libopus at 48 kHz.
#   voice.ogg: a mono voice line (macOS `say`), loudness-normalised, 48 kbit/s (to_opus).
#   loop.ogg:  a seamless stereo loop of 2.025 s (97 200 frames, not a whole number of 20 ms Opus frames),
#              440 Hz left and 400 Hz right (whole numbers of periods), from 16-bit PCM at 128 kbit/s (pcm_to_opus).
set -eu
cd "$(dirname "$0")/Fixtures"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
say -o "$work/voice.aiff" "The door was open. Nobody had come home that night."
ffmpeg -nostdin -hide_banner -loglevel error -y -i "$work/voice.aiff" -vn -ac 1 \
  -af loudnorm=I=-16:TP=-1.5:LRA=11 -ar 48000 -c:a libopus -b:a 48k -f ogg voice.ogg
ffmpeg -nostdin -hide_banner -loglevel error -y \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=2.025" \
  -f lavfi -i "sine=frequency=400:sample_rate=48000:duration=2.025" \
  -filter_complex "[0][1]amerge=inputs=2,volume=0.5" -f s16le -ac 2 -ar 48000 "$work/loop.pcm"
ffmpeg -nostdin -hide_banner -loglevel error -y -f s16le -ar 48000 -ac 2 -i "$work/loop.pcm" \
  -c:a libopus -b:a 128k -f ogg loop.ogg
