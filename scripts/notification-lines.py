#!/usr/bin/env python3
"""Writes iosApp/NotificationService/NotificationCatalog.swift from the UI catalogues (core/common/i18n).

The notification service extension links no Kotlin, so it carries the sentences of NotificationText.kt itself.
`--check` fails when the file is out of date (run it with the catalogues' other checks).
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "iosApp/NotificationService/NotificationCatalog.swift"
KEYS = [
    "notifications.title", "notifications.someone", "notifications.generic", "notifications.groupRequest",
    "notifications.groupApproved", "notifications.groupDeclined", "notifications.groupReply",
    "notifications.groupReplies", "notifications.badgeApproved", "notifications.badgeDenied",
    "notifications.gameInvite", "notifications.gameTurn", "notifications.gameOver", "notifications.gameOverDraw",
    "games.aGame", "games.clash.name", "games.four.name", "games.panels.name",
]


def swift(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("\u202f", "\\u{202F}") + '"'


def render() -> str:
    lines = [
        "// Generated from core/common/i18n/{en,fr}.json by scripts/notification-lines.py: never edit by hand.",
        "// The extension links no Kotlin, so it carries the sentences the inbox uses (NotificationText.kt).",
        "",
        "enum NotificationCatalog {",
        "    static let lines: [String: [String: String]] = [",
    ]
    for locale in ("en", "fr"):
        catalogue = json.loads((ROOT / f"core/common/i18n/{locale}.json").read_text(encoding="utf-8"))
        lines.append(f'        "{locale}": [')
        lines += [f'            "{key}": {swift(catalogue[key])},' for key in KEYS]
        lines.append("        ],")
    lines += ["    ]", "}", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    text = render()
    if "--check" in sys.argv:
        if OUT.read_text(encoding="utf-8") != text:
            sys.exit(f"{OUT.relative_to(ROOT)} is out of date: run scripts/notification-lines.py")
    else:
        OUT.write_text(text, encoding="utf-8")
