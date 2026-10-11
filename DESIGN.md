# Auralis design brief

Simple, calm, premium. Generous spacing, one accent color, few controls.
Edge-to-edge with transparent system bars. Floating pill navigation and a mini
player above it. Glass belongs on bars, cards, sheets and controls; long lists
use readable surfaces.

Dark base: #07090D. Light base: #EEF2F7. Artwork drives ambient color.
Text: #F5F7FA in dark mode, #0E141B in light mode.
Spacing: 4, 8, 12, 16, 24, 32 dp. Radii: 12, 20, 28 dp.
Glass combines backdrop blur, tint, grain, gradient border and a top highlight.
Full glass on capable API 31+ devices; Lite uses tinted gradients; Off is opaque.
Never animate blur radius. Use 120/220/400 ms motion and soft interactive springs.
Honor reduced motion. Minimum 48 dp touch targets, icon descriptions, 200% font
scale and WCAG AA text contrast. Detailed glass components begin in Day 2.
