#!/usr/bin/env bash
# Brand fonts (OFL licence), bundled in the repository: Dela Gothic One (headings, kana included) and Space Grotesk
# (body text) as three static faces, Regular, Medium and Bold (github.com/floriankarsten/space-grotesk).
# Android: androidApp/src/main/res/font (lowercase names). iOS: iosApp/Fonts, declared in UIAppFonts (project.yml).
# This script only checks that both copies are present and identical.
set -euo pipefail
cd "$(dirname "$0")/.."
status=0
for pair in \
  "dela_gothic_one.ttf:DelaGothicOne-Regular.ttf" \
  "space_grotesk_regular.ttf:SpaceGrotesk-Regular.ttf" \
  "space_grotesk_medium.ttf:SpaceGrotesk-Medium.ttf" \
  "space_grotesk_bold.ttf:SpaceGrotesk-Bold.ttf"; do
  android="androidApp/src/main/res/font/${pair%%:*}"
  ios="iosApp/Fonts/${pair##*:}"
  if ! cmp -s "$android" "$ios"; then
    echo "Font missing or different: $android / $ios" >&2
    status=1
  fi
done
[ "$status" -eq 0 ] && echo "Brand fonts present for Android and iOS."
exit "$status"
