package dev.aterminal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// simulates the "yes" flood (a real device OOM): ~20MB of "y\r\n" into the engine under a 256m heap
// (tasks.test maxHeapSize=256m in build.gradle.kts) — must (a) not OOM, (b) evict to exactly the cap,
// (c) every line = 'y' + blank (no torn content), (d) parse+evict time within budget (guards against O(n²) regressions)
class FloodEvictTest {
    @Test fun yesFloodBoundedAndFast() {
        val e = TerminalEngine(80, 24, 9001)
        val pattern = byteArrayOf('y'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        val chunk = ByteArray(64 * 1024)
        var i = 0
        while (i < chunk.size) { chunk[i] = pattern[i % 3]; i++ }
        val t0 = System.currentTimeMillis()
        var fed = 0L
        repeat((20L * 1024 * 1024 / chunk.size).toInt()) { e.write(chunk); fed += chunk.size }
        val ms = System.currentTimeMillis() - t0
        assertEquals("history = exactly cap", 24 + 9001, e.buf.lines.size)
        assertEquals("baseY = history", 9001, e.buf.baseY)
        for (idx in intArrayOf(0, 1, 4500, 9023, 9024)) {
            val l = e.buf.lines[idx]
            assertEquals("col0 = y @$idx", "y", l.mutableAt(0).ch)
            assertEquals("col1 blank @$idx", ' '.code, l.mutableAt(1).ch[0].code) // TermLine default blank = " " (32) — not the erase-proto NULL
        }
        assertTrue("flood ${fed / 1048576}MB must finish within budget (measured ${ms}ms)", ms < 20_000)
    }
}
