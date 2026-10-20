#!/usr/bin/env bash
# Brand fonts (OFL licence, google/fonts repository): Dela Gothic One (headings), Space Grotesk (body text).
# Copied for Android (res/font, lowercase names) and iOS (Fonts/, declared in UIAppFonts).
set -euo pipefail
cd "$(dirname "$0")/.."
base="https://raw.githubusercontent.com/google/fonts/main/ofl"
tmp="$(mktemp -d)"
curl -fsSL "$base/delagothicone/DelaGothicOne-Regular.ttf" -o "$tmp/DelaGothicOne-Regular.ttf"
curl -fsSL "$base/spacegrotesk/SpaceGrotesk%5Bwght%5D.ttf" -o "$tmp/SpaceGrotesk.ttf"
cp "$tmp/DelaGothicOne-Regular.ttf" androidApp/src/main/res/font/dela_gothic_one.ttf
cp "$tmp/SpaceGrotesk.ttf" androidApp/src/main/res/font/space_grotesk.ttf
cp "$tmp/DelaGothicOne-Regular.ttf" "$tmp/SpaceGrotesk.ttf" iosApp/Fonts/
rm -rf "$tmp"
echo "Fonts installed for Android and iOS."
