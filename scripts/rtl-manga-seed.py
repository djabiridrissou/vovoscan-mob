#!/usr/bin/env python3
"""Draws a test manga read right to left, as a backend seed (`seeds/<slug>/`, loaded by `vovoscan seed-demo` or
`vovoscan load-seeds`), so the paged reader can be checked on a simulator or a device: right-to-left paging, two
facing pages on a wide screen (iPad sideways, an unfolded foldable), a double spread standing alone.

Every page is drawn here (no third-party art): panels numbered in reading order (right to left, top to bottom), the
page number, and on each page the way to the next one. Page 1 is the cover; chapter 1's page 6 is a double spread
(twice as wide), which must show alone even when pages face each other.

    cd ../vovoscan-be && uv run python ../vovoscan-mob/scripts/rtl-manga-seed.py ../seeds
    uv run vovoscan load-seeds --root ..        # or: uv run vovoscan seed-demo --root ..

Needs Pillow (the backend's environment has it). Rerunnable: the files are drawn again, identical.
"""

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

SLUG = "rtl-test-manga"
NAME = "Migi kara Hidari (RTL test)"
WIDTH, HEIGHT = 1000, 1414  # B5 proportions, a printed manga page.
INK, PAPER, RED, GREY = (14, 14, 16), (247, 244, 236), (229, 57, 53), (150, 150, 150)
FONT = Path(__file__).resolve().parent.parent / "iosApp" / "Fonts" / "DelaGothicOne-Regular.ttf"
CHAPTERS = {"ch-001": (1, "Right to left", 9, {5}), "ch-002": (2, "Facing pages", 8, set())}


def font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT), size)


def centred(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], text: str, size: int, fill) -> None:
    f = font(size)
    left, top, right, bottom = draw.textbbox((0, 0), text, font=f)
    x = box[0] + (box[2] - box[0] - (right - left)) / 2 - left
    y = box[1] + (box[3] - box[1] - (bottom - top)) / 2 - top
    draw.text((x, y), text, font=f, fill=fill)


def cover(chapter: int, name: str) -> Image.Image:
    page = Image.new("RGB", (WIDTH, HEIGHT), INK)
    draw = ImageDraw.Draw(page)
    draw.rectangle((60, 60, WIDTH - 60, HEIGHT - 60), outline=RED, width=12)
    centred(draw, (0, 260, WIDTH, 520), "右から左へ", 120, PAPER)
    centred(draw, (0, 560, WIDTH, 680), "RTL TEST", 80, RED)
    centred(draw, (0, 760, WIDTH, 860), f"Chapter {chapter} · {name}", 44, PAPER)
    centred(draw, (0, 1060, WIDTH, 1160), "Page 1 · cover", 40, GREY)
    centred(draw, (0, 1180, WIDTH, 1280), "Next page: ←", 40, GREY)
    return page


def panels(number: int, wide: bool) -> Image.Image:
    width = WIDTH * 2 if wide else WIDTH
    page = Image.new("RGB", (width, HEIGHT), PAPER)
    draw = ImageDraw.Draw(page)
    margin, gutter = 50, 24
    # Two rows of two panels, then one wide panel: read right to left, top to bottom.
    rows = [(margin, 560), (560 + gutter, 1000)]
    order = 1
    for top, bottom in rows:
        middle = width // 2
        for left, right in ((middle + gutter // 2, width - margin), (margin, middle - gutter // 2)):
            draw.rectangle((left, top, right, bottom), outline=INK, width=8)
            centred(draw, (left, top, right, bottom), str(order), 220, INK)
            order += 1
    draw.rectangle((margin, 1000 + gutter, width - margin, HEIGHT - 150), outline=INK, width=8)
    centred(draw, (margin, 1000 + gutter, width - margin, HEIGHT - 150), str(order), 160, INK)
    label = f"Page {number}" + (" · double spread" if wide else "")
    centred(draw, (0, HEIGHT - 140, width, HEIGHT - 70), f"{label} · next page: ←", 40, RED)
    return page


def main(out: Path) -> None:
    folder = out / SLUG
    chapters = {}
    for chapter_folder, (number, name, count, wide_pages) in CHAPTERS.items():
        target = folder / chapter_folder
        target.mkdir(parents=True, exist_ok=True)
        for index in range(count):
            image = cover(number, name) if index == 0 else panels(index + 1, index in wide_pages)
            image.save(target / f"{index + 1:03d}.png", optimize=True)
        chapters[chapter_folder] = {"number": number, "name": name}
    seed = {
        "slug": SLUG,
        "name": NAME,
        "description": "Test pages drawn for the reader: a manga read right to left, panels numbered in reading order.",
        "format": "manga",
        "status": "ongoing",
        "authors": ["vovoscan test"],
        "genres": ["Test"],
        "chapters": chapters,
    }
    (folder / "seed.json").write_text(json.dumps(seed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{folder}: {sum(c[2] for c in CHAPTERS.values())} pages")


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path("seeds"))
