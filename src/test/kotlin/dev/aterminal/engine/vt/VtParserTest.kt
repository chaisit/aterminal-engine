package dev.aterminal.engine.vt

import org.junit.Assert.assertEquals
import org.junit.Test

// parser unit tests — events must match what the engine expects (semantics per the header of VtParser.kt)
class VtParserTest {
    private class Rec : VtConsumer {
        val log = ArrayList<String>()
        override fun print(codepoint: Int) { log.add("p:" + Integer.toHexString(codepoint)) }
        override fun control(b: Int) { log.add("c:" + Integer.toHexString(b)) }
        override fun esc(intermediates: IntArray, final: Int) {
            log.add("e:${intermediates.joinToString(",")}~${final.toChar()}")
        }
        override fun csi(marker: Int, params: IntArray, subparams: BooleanArray, intermediates: IntArray, final: Int) {
            val m = if (marker == 0) "" else marker.toChar().toString()
            // subparam (after ':') = suffixed with '#' — test pins that the parser separates sub vs top-level (probe underline_color)
            val pj = params.mapIndexed { i, v -> if (subparams.getOrElse(i) { false }) "$v#" else v.toString() }.joinToString(",")
            log.add("s:$m[$pj]i[${intermediates.joinToString(",")}]${final.toChar()}")
        }
        override fun osc(data: ByteArray) { log.add("o:" + String(data, Charsets.UTF_8)) }
        override fun stringAbsorb(kind: Int, data: ByteArray) { log.add("a:${kind.toChar()}") }
    }

    private fun feed(bytes: String): List<String> {
        // char <0x100 → single byte (8-bit on-the-wire C1 as the parser must see it; ASCII = unchanged)
        // char ≥0x100 → actual UTF-8 bytes (multi-byte test)
        val bo = java.io.ByteArrayOutputStream()
        for (ch in bytes) {
            if (ch.code < 0x100) bo.write(ch.code) else bo.write(ch.toString().toByteArray(Charsets.UTF_8))
        }
        val r = Rec(); VtParser(r).feed(bo.toByteArray()); return r.log
    }

    @Test fun groundPrintAndControls() {
        assertEquals(listOf("c:d", "p:41", "c:7"), feed("\rA\u0007"))
    }

    @Test fun nulAndDelIgnored() {
        assertEquals(listOf("p:41", "p:42"), feed("A\u0000\u007fB"))
    }

    @Test fun utf8Multibyte() {
        val log = feed("ก한") // Thai 3-byte + Korean 3-byte
        assertEquals(listOf("p:e01", "p:d55c"), log)
    }

    @Test fun utf8SplitAcrossChunks() {
        val r = Rec()
        val p = VtParser(r)
        val b = "한".toByteArray()
        p.feed(b.copyOfRange(0, 1)); p.feed(b.copyOfRange(1, 3))
        assertEquals(listOf("p:d55c"), r.log)
    }

    @Test fun invalidUtf8EmitsReplacement() {
        val r = Rec(); VtParser(r).feed(byteArrayOf(0xBF.toByte(), 'A'.code.toByte()))
        assertEquals(listOf("p:fffd", "p:41"), r.log)
    }

    @Test fun c1Ground() {
        // 0x84 IND, 0x8D RI → control; 0x9B = CSI; 0x9D = OSC
        assertEquals(listOf("c:84", "c:8d"), feed("\u0084\u008d"))
        assertEquals(listOf("s:[]i[]H"), feed("\u009bH"))
        assertEquals(listOf("o:0;t"), feed("\u009d0;t\u009c"))
    }

    @Test fun csiVariants() {
        assertEquals(listOf("s:?[25]i[]h"), feed("\u001b[?25h"))
        assertEquals(listOf("s:[1,2]i[]H"), feed("\u001b[1;2H"))
        // param absent = -1 sentinel; trailing semicolon = param 0? `1;` → [1, -1]
        assertEquals(listOf("s:[1,-1]i[]m"), feed("\u001b[1;m"))
        // ':' = subparam (xterm.js Params) — param after ':' gets flag '#' (engine must not treat it as a new code — probe underline_color)
        assertEquals(listOf("s:[4,31#]i[]m"), feed("\u001b[4:31m"))
        assertEquals(listOf("s:[38,5#,1#]i[]m"), feed("\u001b[38:5:1m")) // entire ':' chain = sub of 38
        assertEquals(listOf("s:[1,4,31#]i[]m"), feed("\u001b[1;4:31m"))  // ';' clears the sub flag
        // marker must be in the first position — anywhere else = CSI_IGNORE (whole seq swallowed)
        assertEquals(emptyList<String>(), feed("\u001b[1?m"))
        // clamp 65535
        assertEquals(listOf("s:[65535]i[]m"), feed("\u001b[999999m"))
    }

    @Test fun csiIntermediates() {
        assertEquals(listOf("s:[]i[32]q"), feed("\u001b[ q")) // DECSCUSR: SP = 0x20
        assertEquals(listOf("s:[]i[33]p"), feed("\u001b[!p")) // DECSTR: ! = 0x21, no param = empty array
    }

    @Test fun escWithIntermediatesAndDesignation() {
        assertEquals(listOf("e:40~0"), feed("\u001b(0")) // '(' = 0x28
        assertEquals(listOf("e:~c"), feed("\u001bc"))
    }

    @Test fun escMidSequenceRestarts() {
        // ESC mid-CSI = drop the old seq, start a new escape (xterm.js)
        assertEquals(listOf("e:~M"), feed("\u001b[1;2\u001bM"))
    }

    @Test fun canAbortsStringAndCsi() {
        assertEquals(emptyList<String>(), feed("\u001b]0;t\u0018")) // OSC + CAN = abort, no dispatch
        // CSI + CAN = execute then abort → the remaining 'm' becomes print (GROUND)
        assertEquals(listOf("c:18", "p:6d"), feed("\u001b[1;2\u0018m"))
        assertEquals(listOf("c:18", "p:4d"), feed("\u001b\u0018M")) // ESC + CAN before final = abort; M (GROUND) = print
        assertEquals(listOf("e:~M", "c:18"), feed("\u001bM\u0018")) // ESC M dispatched, then CAN = ordinary C0
    }

    @Test fun oscTerminators() {
        assertEquals(listOf("o:0;t1"), feed("\u001b]0;t1\u0007")) // BEL
        assertEquals(listOf("o:0;t2", "e:~\\"), feed("\u001b]0;t2\u001b\\")) // ESC = dispatch OSC first, then ESC \ = esc dispatch (ST — engine ignores)
        assertEquals(listOf("o:0;t3", "p:41"), feed("\u001b]0;t3\u009cA")) // C1 ST
    }

    @Test fun dcsAbsorbed() {
        assertEquals(listOf("a:P", "p:41"), feed("\u0090q=1\u009cA"))
    }
}
