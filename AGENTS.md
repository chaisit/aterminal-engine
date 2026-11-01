# aterminal-engine — guidance for AI agents and contributors

Pure-Kotlin VT engine (extracted from the A-Terminal app), single-module Gradle, MIT, `kotlin-stdlib` as the only runtime dependency.

## Where changes come from

- This library is extracted from the `:engine` module of the A-Terminal app, which is not published
  yet and remains the upstream source of truth. Engine changes land upstream first, pass its
  conformance gates (corpus + units + reflow against `@xterm/headless` 5.5.0 + `addon-unicode11` 0.8.0), then sync down here.
- LICENSE is MIT. Any code ported from elsewhere must have a compatible license and be recorded in
  `THIRD_PARTY_NOTICES.md`. Do not add runtime dependencies beyond `kotlin-stdlib`
  (test scope may use JUnit only).

## Build / test

- `./gradlew test` — hermetic JVM tests (parser, grid, flood-eviction bounds, fuzz-no-throw)
- Conformance mains (`dumpGrid`, `dumpGridBatch`, `engineSteps`) drive the same grids the upstream
  harness compares against `@xterm/headless` 5.5.0 gold (see README "Conformance harness").
- JVM target is 17 — do not remove the `KotlinCompile.jvmTarget JVM_17` block.
- When judging a build, trust the literal `BUILD SUCCESSFUL` line, not the exit code of a piped command.

## Invariants (do not break)

- `TerminalEngine.write` never throws, for any input (fuzz-no-throw test pins this).
- `TermLineRing` never recycles a `TermLine` object (scrollback is O(1) eviction; recycling
  creates aliasing bugs in ring consumers).
- `Reflow` stays bug-compatible with xterm's observed behaviour — known quirks are intentional;
  do not "fix" them.
- Never rely on memory for external library behaviour (xterm included) — verify against the real
  artifact/jar. This repo's conformance contract was built that way.

## Conventions

- Comments: English-first; short notes in other languages may survive only as secondary annotations.
- Public API changes require a test plus a conformance-visible check (grid dump or unit probe).

## AI-assisted development disclosure

This project is developed with AI assistance (Claude Code). Commits carry a
`Co-Authored-By: Claude Code <noreply@anthropic.com>` trailer where AI-authored work is included.
