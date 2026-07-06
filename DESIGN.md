---
name: LianYu Pink Romance
description: >
  Dark, romantic, glassmorphic companion-app aesthetic anchored by rose-pink #f4a6b5
  on warm-plum blacks, with serif/syne typography on auth & landing surfaces, soft
  EaseOutQuint motion, and ambient blurred orbs + grain. Dark-first with full light parity.
colors:
  # ── Brand accent (rose pink "Pink Romance") ──
  primary: "#f4a6b5"
  primary-light: "#f8c8d8"
  primary-dark: "#d48494"
  primary-muted: "#6b4a52"
  primary-glow: "rgba(244, 166, 181, 0.2)"
  # ── Dark mode backgrounds (blue-black, applied at boot by themeColor.js) ──
  bg-deepest: "#0e1218"
  bg-primary: "#121820"
  bg-secondary: "#171e28"
  bg-surface: "#1e2732"
  bg-elevated: "#252f3c"
  bg-glass: "rgba(30, 39, 50, 0.75)"
  # ── Light mode backgrounds ──
  bg-deepest-light: "#ffffff"
  bg-primary-light: "#f7f7f9"
  bg-secondary-light: "#ececee"
  bg-surface-light: "#e0e0e3"
  bg-elevated-light: "#d4d4d8"
  # ── Text (dark mode) ──
  text-primary: "#e8edf2"
  text-secondary: "#a8b4c0"
  text-muted: "#728090"
  text-inverse: "#141a22"
  # ── Text (light mode) ──
  text-primary-light: "#1a1a1e"
  text-secondary-light: "#4a4a52"
  text-muted-light: "#8a8a96"
  text-inverse-light: "#ffffff"
  # ── Functional / status ──
  success: "#7EB99F"
  warning: "#FFA500"
  error: "#FA5151"
  info: "#8BA4C8"
  # ── Chat scene (immersive dark) ──
  chat-scene-bg: "#0a0a12"
  chat-user-bubble-bg: "rgba(244, 166, 181, 0.26)"
  chat-assistant-bubble-bg: "rgba(30, 39, 50, 0.92)"
  # ── Auth / landing atmosphere ──
  auth-ink: "#06080f"
  on-primary: "#141a22"
  on-primary-light: "#ffffff"
typography:
  body:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', 'Hiragino Sans GB', system-ui, sans-serif"
    fontSize: 0.9375rem
    fontWeight: "400"
    lineHeight: "1.6"
  body-sm:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"
    fontSize: 0.875rem
    fontWeight: "400"
    lineHeight: "1.6"
  body-xs:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"
    fontSize: 0.75rem
    fontWeight: "500"
    lineHeight: "1.5"
    letterSpacing: "0.18em"
  display-serif:
    fontFamily: "'Noto Serif SC', 'Songti SC', serif"
    fontSize: 2.25rem
    fontWeight: "600"
    lineHeight: "1.25"
    letterSpacing: "0.04em"
  brand-wordmark:
    fontFamily: "'Syne', system-ui, sans-serif"
    fontSize: 1.125rem
    fontWeight: "700"
    lineHeight: "1.25"
    letterSpacing: "0.22em"
  heading-lg:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"
    fontSize: 1.75rem
    fontWeight: "600"
    lineHeight: "1.25"
  heading-md:
    fontFamily: "'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"
    fontSize: 1.375rem
    fontWeight: "600"
    lineHeight: "1.25"
  mono:
    fontFamily: "'JetBrains Mono', 'Cascadia Code', 'Fira Code', Consolas, monospace"
    fontSize: 0.875rem
    fontWeight: "400"
    lineHeight: "1.6"
rounded:
  sm: 8px
  md: 14px
  lg: 24px
  xl: 28px
  pill: 25px
  full: 9999px
spacing:
  "1": 0.25rem
  "2": 0.5rem
  "3": 0.75rem
  "4": 1rem
  "5": 1.25rem
  "6": 1.5rem
  "8": 2rem
  "10": 2.5rem
  "12": 3rem
  "16": 4rem
  "20": 5rem
components:
  button-primary:
    backgroundColor: "linear-gradient(135deg, {colors.primary} 0%, {colors.primary-dark} 100%)"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.pill}"
    padding: "8px 18px"
  button-primary-hover:
    backgroundColor: "linear-gradient(135deg, {colors.primary-light} 0%, {colors.primary} 100%)"
  button-default:
    backgroundColor: "{colors.bg-elevated}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.pill}"
    padding: "8px 18px"
  button-default-hover:
    borderColor: "{colors.primary}"
  button-text:
    textColor: "{colors.text-secondary}"
    rounded: "{rounded.pill}"
  button-text-hover:
    textColor: "{colors.primary}"
    backgroundColor: "rgba(244, 166, 181, 0.08)"
  input-field:
    backgroundColor: "{colors.bg-secondary}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.md}"
    padding: "8px 12px"
  input-field-focus:
    borderColor: "{colors.primary}"
  card:
    backgroundColor: "{colors.bg-glass}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.lg}"
    padding: "20px"
  dialog:
    backgroundColor: "{colors.bg-surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.xl}"
    padding: "24px"
  glass-surface:
    backgroundColor: "{colors.bg-glass}"
    rounded: "{rounded.lg}"
---

## Overview

**LianYu "Pink Romance" (粉恋)** — a dark, romantic, glassmorphic companion-app
aesthetic. The UI evokes an intimate editorial space: warm-plum blacks paired
with a single soft rose-pink accent `#f4a6b5`, glass surfaces with
`backdrop-filter: blur`, ambient floating orbs, and serif display type for
emotional headlines. This is a companion/character-chat app, not an admin
dashboard — every surface is softened with EaseOutQuint motion and generous
radii.

**Non-negotiable rule:** Any new or modified UI **must** use the tokens defined
in this file. Do not invent new colors, fonts, radii, or spacing values. If a
need is not covered, extend the tokens here first, then use the new token.

## Colors

The palette is a single-accent system: rose-pink `#f4a6b5` drives all
interaction; backgrounds are layered warm-plum/blue-blacks in dark mode and
soft neutrals in light mode.

- **Primary (#f4a6b5):** "Pink Romance" — the sole driver for buttons, links,
  active states, focus rings, scrollbars, glow shadows. Never use a different
  hue for interactive elements.
- **Primary-light (#f8c8d8):** Hover gradients, soft highlights.
- **Primary-dark (#d48494):** Pressed/active states, gradient ends.
- **Primary-muted (#6b4a52):** Disabled buttons, placeholders.
- **Backgrounds (dark):** Five-step ramp from `#0e1218` (deepest) → `#252f3c`
  (elevated). Always pick the correct layer — never hardcode a random dark.
- **Backgrounds (light):** `#ffffff` → `#d4d4d8` mirror ramp.
- **Text:** `#e8edf2` / `#a8b4c0` / `#728090` (primary/secondary/muted) in dark;
  `#1a1a1e` / `#4a4a52` / `#8a8a96` in light. Inverse text on pink = `#141a22`.
- **Status:** success `#7EB99F` (mint), warning `#FFA500`, error `#FA5151`,
  info `#8BA4C8`. Use these for toasts/badges only — never as decorative color.
- **Chat scene:** Immersive `#0a0a12` background with translucent bubbles.
  User bubble = pink-tinted; assistant bubble = surface-tinted.

**Accent is user-tunable at runtime** (6 warm presets: pink/peach/rose-gold/
coral/warm-sun/apricot). All accent references must go through `{colors.primary}`
or the CSS variable `--ly-accent`, never hardcoded hex — except in this file.

## Typography

Two-typeface system for elegance; body falls back to system Chinese sans.

- **Body** (`PingFang SC` / `Microsoft YaHei`): All UI text, labels, chat
  messages, Element Plus components. Base size 15px (0.9375rem), line-height 1.6.
- **Display serif** (`Noto Serif SC`): Emotional headlines on auth & landing
  pages only. Loaded from Google Fonts (weights 500/600/700).
- **Brand wordmark** (`Syne`): "LianYu" logo, uppercase, letter-spacing 0.22em.
  Loaded from Google Fonts alongside Noto Serif SC.
- **Mono** (`JetBrains Mono`): Code blocks in QQ Bridge / About / Settings.
- **Eyebrow labels**: `body-xs` token — 12px, 500 weight, 0.18em tracking,
  uppercase, pink color. Use for section headers and metadata.

**Type scale:** 12 · 14 · 15 · 18 · 22 · 28 · 36px. Weights: 300/400/500/600/700.
Line heights: 1.25 (tight) · 1.6 (normal) · 1.8 (relaxed).

## Layout

Rem-based 4px-step spacing scale (root 16px): 4 · 8 · 12 · 16 · 20 · 24 · 32 ·
40 · 48 · 64 · 80px.

**Layout constants:**
- Header height: 64px (Electron caption: 52px, controls width: 138px)
- App dock height: 72px (wheel clearance: 96px)
- Sidebar width: 260px (collapsed: 72px)
- Max content width: 1200px; narrow page: 780px
- Page gutter: `clamp(1rem, 4vw, 2.5rem)`

**Z-index scale:** sidebar 100 · header 200 · overlay 500 · modal 600 ·
toast 700. Electron caption drag 9999, app header 10001.

## Elevation & Depth

Glassmorphism is the primary depth language — not flat shadows.

- **Glass** (default): `backdrop-filter: blur(20px) saturate(120%)` over
  `rgba(30, 39, 50, 0.75)`, border `1px solid rgba(244, 166, 181, 0.08)`.
- **Glass-strong**: `blur(24px) saturate(140%)` over 0.96 opacity surface.
- **Pink glow shadow**: `0 0 30px rgba(244,166,181,0.12), 0 0 60px rgba(244,166,181,0.05)`
  — use on primary buttons and active cards.
- **Lift shadow**: `0 4px 16px rgba(244,166,181,0.28)` on primary button hover.
- **Ambient**: `#app::before` runs a 20s opacity animation over three stacked
  radial gradients. Auth/landing pages add blurred orbs (`blur(90-100px)`) on
  9-14s float loops + SVG fractal-noise grain at `mix-blend-mode: overlay`.

## Shapes

Consistent rounded-soft silhouette across the entire app:

| Token | Radius | Usage |
|-------|--------|-------|
| sm (8px) | Small chips, captcha images |
| md (14px) | Inputs, selects, menu items, feed avatars |
| lg (24px) | Cards, sidebar sections, image canvas |
| xl (28px) | Dialogs, modals, auth container, atmosphere panels |
| pill (25px) | **All buttons** and CTA chips |
| full (9999px) | Circular avatars, scrollbars, icon buttons |

Never use sharp corners (0px radius) or arbitrary radius values.

## Components

- **Button primary**: Pink gradient (135deg light→dark), pill radius, no border,
  text inverse, pink glow shadow on hover lifts `translateY(-1px)`.
- **Button default**: Elevated bg, `rgba(accent,0.22)` border, pill, hover
  border → pink.
- **Input**: Secondary bg, `rgba(accent,0.14)` border, md radius, inset shadow;
  focus → pink border + `0 0 0 3px rgba(accent,0.18)` ring.
- **Card**: Glass bg, lg radius, `rgba(accent,0.08)` border, backdrop blur.
- **Dialog**: Surface bg, xl radius, glow + lift shadow, header/body/footer
  with pink-tinted dividers.
- **Scrollbar**: 6px wide, transparent track, thumb `rgba(accent,0.15)`.

All transitions use `cubic-bezier(0.23, 1, 0.32, 1)` (EaseOutQuint) at 0.2-0.28s.
Micro-animations (stagger reveals, bubble-in, button shine) respect
`prefers-reduced-motion`.

## Do's and Don'ts

**Do:**
- Use `{colors.primary}` / `--ly-accent` for all interactive color
- Use glass surfaces (`backdrop-filter: blur`) for cards and overlays
- Use EaseOutQuint easing for all transitions
- Use the spacing scale tokens — never arbitrary px
- Use pill radius for buttons, lg/xl for containers
- Support both dark and light mode via CSS variables

**Don't:**
- Don't introduce new colors outside this palette (no blues, greens, purples
  as decorative accents — only the status colors for toasts/badges)
- Don't use sharp corners or `border-radius: 0`
- Don't use `font-family` values not defined here
- Don't hardcode hex values in components — reference tokens or CSS variables
- Don't use `linear` or `ease` timing functions — use EaseOutQuint
- Don't add flat shadows without pink tint — depth comes from glass + glow
