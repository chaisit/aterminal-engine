package dev.aterminal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

// invariant lock: `write` must never throw on any input (the app calls it on Main — a throw crashes the whole terminal)
// seeded = deterministic; biased byte sampling hits the sequence states more often than uniform
class FuzzNoThrowTest {
    private fun checkInvariants(e: TerminalEngine, ctx: String) {
        assertTrue("$ctx lines", e.buf.lines.size >= e.rows)
        assertTrue("$ctx x=${e.buf.x}", e.buf.x in 0..e.cols)
        assertTrue("$ctx y=${e.buf.y}", e.buf.y in 0 until e.rows)
        for (i in 0 until e.rows) {
            val l = e.buf.line(i)
            assertEquals("$ctx rowwidth", e.cols, l.cols)
        }
        GridDump.dump(e) // dumper must not throw on whatever state the fuzz drove it into
    }

    @Test fun randomBytesNeverThrow() {
        for (seed in 0 until 64L) {
            val rnd = Random(seed)
            val e = TerminalEngine(80, 24, 32)
            repeat(400) {
                val n = 1 + rnd.nextInt(64)
                val b = ByteArray(n)
                rnd.nextBytes(b)
                for (i in 0 until n) {
                    // bias: ESC/CSI digits/';' ':'/final bytes/UTF-8 lead — reaches the state machine's hard-to-hit states more often
                    when (rnd.nextInt(8)) {
                        0 -> b[i] = 0x1b
                        1 -> b[i] = (0x30 + rnd.nextInt(16)).toByte() // digits..':'
                        2 -> b[i] = (0x40 + rnd.nextInt(0x3f)).toByte() // CSI finals
                        3 -> b[i] = (rnd.nextInt(0x100)).toByte() // covers the C1 range (0x80-0x9F)
                        else -> {} // uniform from nextBytes
                    }
                }
                e.write(b)
                checkInvariants(e, "seed=$seed it=$it")
            }
            e.resize(40, 12); checkInvariants(e, "seed=$seed shrink")
            e.resize(100, 30); checkInvariants(e, "seed=$seed grow")
            GridDump.dump(e)
        }
    }

    @Test fun utf8SplitAtEveryChunkBoundary() {
        // "한aก𐀀" (3+1+3+4 bytes) one byte per write = stream must decode fully (𐀀 = U+10000 astral w1 — not an emoji w2)
        val bytes = ("한aก" + String(Character.toChars(0x10000))).toByteArray(Charsets.UTF_8)
        val e = TerminalEngine(20, 4, 0)
        for (b in bytes) e.write(byteArrayOf(b))
        assertEquals(5, e.buf.x) // 한 = w2 → cell+dummy, then a/ก/astral
        assertEquals("한", e.buf.line(0).get(0).ch)
        assertEquals("a", e.buf.line(0).get(2).ch)
        assertEquals("ก", e.buf.line(0).get(3).ch)
        assertEquals(String(Character.toChars(0x10000)), e.buf.line(0).get(4).ch)
    }

    @Test fun unterminatedSequencesSwallowedNotStuck() {
        // stream ends mid OSC/CSI/DCS — must the next write (print) keep working after re-feeding?
        // (state machine stays stuck per VT — check only no-throw + invariants; print after ST must restore)
        val e = TerminalEngine(20, 4, 0)
        e.write("A\u001b]0;t".toByteArray()) // unterminated OSC → only A, state stuck in OSC
        e.write("B\u0007C".toByteArray())    // data B consumed by OSC, BEL then terminates, C prints
        assertEquals("AC", (0 until 2).joinToString("") { e.buf.line(0).get(it).ch })
        checkInvariants(e, "stuck")
    }
}
