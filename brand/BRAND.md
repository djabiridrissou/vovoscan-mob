# vovoscan — Brand dossier

Source of truth: the live site <https://vovoscan.yieldgrowth.org/> (landing + `/login`). Every value below was read from its shipped CSS/HTML; the "Ref" column says where. Machine-readable: [tokens.json](tokens.json), [tokens.css](tokens.css). Logo: [assets/logo-mark.svg](assets/logo-mark.svg).
Backend, web and mobile all follow this DA.

## 1. Essence
- **Product**: self-hosted, invite-only tool that turns a manga/webtoon chapter URL into a voiced motion-comic episode.
- **Tagline**: *"Your scans, fully voiced."*
- **Footer line**: "Self-hosted motion comics · 2026"
- **Concept**: a printed manga page — halftone dots, hard ink outlines, offset "sticker" shadows, speed lines, onomatopoeia — on a dark ink background.
- **Personality**: confident, concise, playful, technical-but-trustworthy (security is a selling point: "Locked down like a vault.").

## 2. Logo
- Wordmark: lowercase **vovoscan** in Dela Gothic One, preceded by the mark. Ref: `.landing_logo`.
- Mark: red square (#ff2d55), 4px paper outline, rotated **-6°**, katakana **ヴ** in ink; on an ink rounded tile (rx 12/64). Ref: `/icon.svg` (copied to assets/logo-mark.svg).
- In-nav version: 32×32, 2px paper border, `3px 3px 0 paper` hard shadow, rotate(-6deg).
- Always keep the -6° tilt; never recolor outside red/ink/paper. Minimum 24px.

## 3. Color
| Token | Hex | Use |
|---|---|---|
| ink | `#0b0b0e` | background; text on red/yellow/cyan |
| ink-2 | `#15151b` | cards, panels, player |
| line | `#2a2a33` | app borders |
| paper | `#f3eee4` | main text, light panels, outlines |
| paper-2 | `#e6dfd1` | secondary light surface |
| muted | `#a39e94` | secondary text, labels |
| **red** | `#ff2d55` | **accent**: primary action, links, key words |
| yellow | `#ffd60a` | highlights, stickers, SFX |
| cyan | `#2de2e6` | audio / voice / scanning |
| danger | `#ff6b6b` | errors |

Rules: dark-only (no light theme). Red = action, cyan = audio/AI processing, yellow = emphasis. Text on red is **ink**, never white. Soft text = paper at 76% (`#f3eee4c2`) / 66% (`#f3eee4a8`).

## 4. Typography
- **Display — Dela Gothic One** (400 only): logo, h1/h2/h3, big numbers, step numbers (outlined: transparent fill + 2px ink stroke), SFX. Tight leading (.98–1.15).
- **Body — Space Grotesk** (variable 300–700): everything else. 15px/1.5 base, 13px labels, 12px uppercase kickers (tracking .12em, bold).
- Hero title `clamp(44px,6.6vw,92px)`; section h2 `clamp(32px,4.6vw,60px)`; card h3 22px.
- Japanese SFX (ドン! ゴゴゴ ザワ ジッ ペタ ドドド) set in the display font, often vertical (`writing-mode: vertical-rl`), with ink stroke/shadow.
- Both are open-source Google Fonts — bundle them in mobile.

## 5. Shape, depth, motion
- **Landing = sharp**: radius 0, 2–3px solid borders, **hard offset shadows with no blur** (`6px 6px 0`), slight rotations (±1.5–2°) and clip-path skewed edges on cards.
- **App/auth screens = softer**: cards radius 12, inputs/buttons radius 8, 1px `line` border, no shadow.
- Pills (radius 999) for eyebrow tags, audio waveform, status dots.
- Texture: halftone dot overlays (`radial-gradient` dots 8–14px), speed-line conic gradients, cyan scan-line sweep.
- Hover: element lifts `translate(-2px,-2px)` and its hard shadow grows (150ms). Cards lift 3–6px (200ms). Arrow glyph slides 4px right.
- Motion: pulse dot (2s), marquee ticker (28s), waveform bars, progress fill. Easing `cubic-bezier(.2,.8,.2,1)`. Respect reduced-motion.

## 6. Components (reference spec)
- **Primary button**: bg red, text ink, 700, 16px, padding 15×24, 2px ink border, shadow `0 0 0 2px paper, 6px 6px 0 2px paper`, "→" suffix.
- **Ghost button**: transparent, paper text, 2px paper border; hover paper @8%.
- **Nav CTA**: 2px paper border, shadow `3px 3px 0 red`.
- **App button** (auth screens): red bg, ink text, bold, radius 8; ghost = transparent + 1px line.
- **Input**: bg `#0b0c10`, 1px line, radius 8, 10×12 padding, 13px muted label above.
- **Card (app)**: ink-2, 1px line, radius 12, padding 24.
- **Feature tile**: ink-2, 2px `#ffffff1f` border → paper on hover.
- **Kicker**: paper bg, ink text, uppercase, rotated -2°.
- **Sticker**: yellow bg, ink text, display font, rotate 8°, `3px 3px 0 ink`.
- **Player**: ink-2, 2px paper border, `6px 6px 0 cyan`, round red play button, red progress.
- **Detection boxes** (analysis UI): dashed 2px — red = panel/speaker, cyan = bubble, yellow = secondary panel, label chip on top-left.
- **Stats**: display-font number + 13px dim caption, dashed top border.

## 7. Voice & copy
- Language: English UI with Japanese SFX accents. Short sentences, second person, verbs first ("Paste a chapter URL", "Press play").
- Sentence case, period-ended headlines. Title case never.
- Key phrases: "From URL to episode." · "Built for people who actually read." · "Locked down like a vault." · "Ready for episode one?"
- Pipeline vocabulary (use exactly, UPPERCASE in UI chips/logs): **FETCH · ANALYZE · OCR · TAG · VOICE · TIMELINE · PUBLISH** (7 stages).
- Terms: *chapter*, *episode*, *cast*, *voice*, *invite*, *library*. Avoid "AI-powered", "revolutionary", emojis. Use ✦ as separator.
- CTAs: "Sign in →", "I have an invite", "Press play".
- Error tone: calm, specific, in `#ff6b6b` ("Invite already used." not "Oops!").

## 8. Feature copy (from #features)
1. **Manga or webtoon** — Right-to-left pages and infinite vertical strips, each read in its own order.
2. **A cast that stays** — Same character, same voice, every chapter. (e.g. Kai · bold, young / Mira · calm, low / Narrator · warm)
3. **Live progress** — Watch each stage advance in real time, no refresh needed.
4. **Smart dedupe** — The same chapter found on another site is recognised by its pages and never processed twice.

Security promises (must stay true in backend): invite-only (single-use invites), Argon2id + revocable server sessions + CSRF on writes, sandboxed Chromium fetching (no downloads, no disk profile), SSRF guard (private/loopback/cloud-metadata refused, redirects re-checked), image type by bytes + metadata stripped, zero trackers / strict nonce CSP.

## 9. Applying the DA

### Backend
- **Pipeline stage enum** = `FETCH, ANALYZE, OCR, TAG, VOICE, TIMELINE, PUBLISH` (shared across API, events/SSE, logs, UI chips). Add `QUEUED/DONE/FAILED` as states, not stages.
- Status → color map for any API-provided hints: running = cyan, done = paper/red check, failed = danger, queued = muted.
- Emails / error messages / CLI output follow §7 voice; transactional email: ink background, paper text, red button, Space Grotesk fallback to system.
- Invite codes & IDs: uppercase mono-ish, short; docs/Swagger theme: dark with red accent.
- No analytics SDKs, no third-party tracking in any service (brand promise).

### Mobile
- Dark only; background ink, surfaces ink-2, outline paper, accent red.
- Fonts: bundle Dela Gothic One + Space Grotesk.
- Hard offset shadows: implement as a second offset shape behind the view (no blur radius) — Flutter `BoxShadow(offset: Offset(6,6), blurRadius: 0)`, RN a sibling `View`, SwiftUI `.background(offset)`.
- Haptics + 150ms lift/press animation on buttons (press = translate +2,+2 and shadow shrinks).
- Reader/player: cyan waveform and scan-line for voice processing, red progress, sticker for "AUTO-VOICED / EP 01 · 04:12".
- Status bar light content; app icon = logo-mark.svg (red tilted square on ink, ヴ).

## 10. Open items
- Only the landing + login CSS are public; authenticated screens (library, reader, player) are not visible — extend this dossier once designed.
- No brand font license file/assets are bundled yet; fetch from Google Fonts.
