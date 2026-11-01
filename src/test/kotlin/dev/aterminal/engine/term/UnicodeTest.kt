package dev.aterminal.engine.term

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class UnicodeTest {
    // sha256 of one width byte per codepoint, from @xterm/addon-unicode11 0.8.0 wcwidth() on @xterm/headless 5.5.0
    @Test
    fun `every codepoint from U+0020 matches addon-unicode11`() {
        val b = ByteArray(0x110000 - 0x20) { Unicode.width(it + 0x20).toByte() }
        val hex = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        assertEquals("726723fa41eff6a0f5b828cb3152d6a082f3640e4d449f3b57843fe9662e8e15", hex)
    }

    // stacked marks must stay in one cell: "ที่" = base + vowel + tone (a real bug before joinWidth)
    @Test
    fun `stacked thai marks join one cell`() {
        val e = dev.aterminal.engine.TerminalEngine(80, 24)
        e.write("\u0E17\u0E35\u0E48x".toByteArray())
        assertEquals("\u0E17\u0E35\u0E48", e.buf.line(0).get(0).ch)
        assertEquals("x", e.buf.line(0).get(1).ch)
        assertEquals(2, e.buf.x)
    }

    // erasing one half of a wide char must blank the other half
    @Test
    fun `erase half of emoji blanks the other half`() {
        val e = dev.aterminal.engine.TerminalEngine(80, 24)
        e.write("\uD83D\uDE00\uD83D\uDE00\u001B[1;2H\u001B[K".toByteArray())
        assertEquals(0, e.buf.line(0).trimmedLength())
        assertEquals(1, e.buf.line(0).get(0).width)
    }

    @Test
    fun `sample widths`() {
        for ((cp, w) in listOf(
            0x1F600 to 2, 0x231A to 2, 0x1F468 to 2, 0x200D to 0, 0xFE0F to 0, 0xE33 to 1, 0xE48 to 0,
            0xAC00 to 2, 0x1100 to 2, 0x1160 to 0, 0xAD to 1, 0x20000 to 2, 0x10FFFF to 1,
        )) assertEquals("U+%04X".format(cp), w, Unicode.width(cp))
    }
}
