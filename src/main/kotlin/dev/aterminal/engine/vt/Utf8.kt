package dev.aterminal.engine.vt

// UTF-8 incremental decoder — the stream may cut chunks at any position (app's 64KB drain batch)
// Invalid-input policy = the recommended "maximal subpart" (Unicode 3.9 D): emit one U+FFFD, then
// restart with the offending byte — xterm.js also replaces (details may differ only for invalid streams, which fixtures don't contain)
class Utf8 {
    private var need = 0          // bytes left for the current codepoint (0 = idle)
    private var acc = 0
    private var lower = 0x80      // bound check on the first continuation byte (prevents overlongs)
    private var upper = 0xBF

    fun reset() { need = 0; acc = 0 }

    /** mid multi-byte sequence — the parser must route every byte ≥0x80 into the decoder (incl. C1-range continuations) */
    val inProgress: Boolean get() = need != 0

    /**feed one byte (0..255); every fully decoded codepoint is emitted*/
    fun feed(b: Int, emit: (Int) -> Unit) {
        var byte = b
        var invalid = false
        while (true) {
            if (need == 0) {
                when {
                    byte < 0x80 -> emit(byte)
                    byte in 0xC2..0xDF -> { acc = byte and 0x1F; need = 1; lower = 0x80; upper = 0xBF }
                    byte in 0xE0..0xEF -> {
                        acc = byte and 0x0F; need = 2
                        if (byte == 0xE0) lower = 0xA0; if (byte == 0xED) upper = 0x9F // forbid overlongs + surrogates
                    }
                    byte in 0xF0..0xF4 -> {
                        acc = byte and 0x07; need = 3
                        if (byte == 0xF0) lower = 0x90; if (byte == 0xF4) upper = 0x8F
                    }
                    else -> if (!invalid) { invalid = true; emit(REPLACEMENT) } // stray 0x80-0xBF, C0/C1 overlong lead, F5+ out of range
                }
                return
            }
            if (byte in lower..upper) {
                acc = (acc shl 6) or (byte and 0x3F)
                lower = 0x80; upper = 0xBF
                if (--need == 0) emit(acc)
                return
            }
            // invalid mid-sequence: drop what was accumulated (maximal subpart); the loop then reprocesses this byte as a fresh start
            need = 0; acc = 0
            if (!invalid) { invalid = true; emit(REPLACEMENT) }
            // note: one reprocess pass is enough — a byte invalid at idle falls into the "else" branch above and returns
        }
    }

    companion object { const val REPLACEMENT = 0xFFFD }
}
