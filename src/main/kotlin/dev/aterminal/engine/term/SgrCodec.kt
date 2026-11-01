package dev.aterminal.engine.term
// Portions derived from xterm.js (MIT), see THIRD_PARTY_NOTICES.md

// SGR → (color, mode) "number space" of xterm.js 5.5 — do NOT memorize!
// pinned from color-encoding and erase-semantics probes against the real package:
//   SGR 31      → fg=1,   mode=16777216 (1<<24)  [C16]
//   SGR 38;5;1  → fg=1,   mode=33554432 (1<<25)  [C256 — never downgraded to C16 even when n<16]
//   SGR 38;5;196→ fg=196, mode=1<<25
//   SGR 38;2;1;2;3 → fg=0x010203=66051, mode=50331648 (3<<24) [RGB]
//   default     → -1, mode 0;  39/49 = default
object SgrCodec {
    const val MODE_DEFAULT = 0
    const val MODE_C16 = 1 shl 24
    const val MODE_C256 = 1 shl 25
    const val MODE_RGB = 3 shl 24

    // returns the base-color value usable as an (fg,bg) index (0..15) — SGR 30-37 → 0..7, 90-97 → 8..15
    fun basic(index: Int) = index // mode = MODE_C16

    fun rgb(r: Int, g: Int, b: Int): Int = ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}
