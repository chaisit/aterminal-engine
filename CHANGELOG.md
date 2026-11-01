# Changelog

## 0.1.0 — 2026-11-01

Initial public release, extracted from the A-Terminal app monorepo (`:engine`).

- Pure-Kotlin VT/xterm terminal engine: `VtParser` state machine + `TerminalEngine` grid model,
  `kotlin-stdlib` as the only runtime dependency, JVM 17+.
- Conformance-first: grid dumps compared exact-string against `@xterm/headless` 5.5.0 during
  development with `@xterm/addon-unicode11` active (13 replay streams, 204 units, 47 reflow
  cases). Character widths follow Unicode 11, so emoji are width 2.
- Reflow on resize is ported from xterm.js and keeps its known quirks on purpose.
- O(1) scrollback eviction with bounded memory (pinned under `-Xmx256m` flood test).
- Includes the eviction-counter fix (`ScreenBuffer.evictedTotal`) so downstream viewports can
  lock content while history is being evicted.
