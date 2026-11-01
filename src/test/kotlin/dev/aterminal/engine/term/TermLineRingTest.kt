package dev.aterminal.engine.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.random.Random

// TermLineRing — differential fuzz against an ArrayList reference + wrap/grow/clear cases
class TermLineRingTest {
    private fun tl(tag: Int) = TermLine(3).also { it.isWrapped = tag % 2 == 0 }

    @Test fun randomOpsMatchArrayList() {
        val rnd = Random(7)
        val ring = TermLineRing(4)
        val ref = ArrayList<TermLine>()
        var next = 0
        repeat(5000) {
            when (rnd.nextInt(7)) {
                0 -> { val t = tl(next++); ring.addLast(t); ref.add(t) }
                1 -> { val t = tl(next++); ring.addFirst(t); ref.add(0, t) }
                2, 6 -> { val i = rnd.nextInt(ref.size + 1); val t = tl(next++); ring.add(i, t); ref.add(i, t) }
                3 -> if (ref.isNotEmpty()) { val i = rnd.nextInt(ref.size); assertSame("removeAt($i)", ref.removeAt(i), ring.removeAt(i)) }
                4 -> if (ref.isNotEmpty()) assertSame("pollFirst", ref.removeAt(0), ring.pollFirst())
                5 -> if (ref.isNotEmpty()) { val i = rnd.nextInt(ref.size); val t = tl(next++); ring.set(i, t); ref[i] = t }
            }
            assertEquals("size", ref.size, ring.size)
        }
        for (i in ref.indices) assertSame("[$i]", ref[i], ring[i])
    }

    @Test fun pollFirstShiftedHeadKeepsIndexing() {
        val ring = TermLineRing(8)
        val items = (0 until 100).map { tl(it) }
        items.forEach { ring.addLast(it) }
        repeat(60) { assertSame(items[it], ring.pollFirst()) }
        val mid = tl(999); ring.add(3, mid) // head shifted — mid insert must still line up
        assertSame(mid, ring[3])
        assertSame(items[63], ring[4])
        assertEquals(41, ring.size)
        assertSame(items[60], ring.pollFirst()) // head = items[60] (mid now at index 4)
    }

    @Test fun growLinearizesUnderWrap() {
        val ring = TermLineRing(4) // capacity 4 → wraps several rounds + grows
        val all = (0 until 50).map { tl(it) }
        var evicted = 0
        for (t in all) {
            ring.addLast(t)
            if (ring.size > 3) assertSame(all[evicted++], ring.pollFirst())
        }
        assertEquals(3, ring.size)
        for (i in 0 until 3) assertSame(all[47 + i], ring[i])
    }

    @Test fun clearThenReuse() {
        val ring = TermLineRing(8)
        (0 until 5).forEach { ring.addLast(tl(it)) }
        ring.clear()
        assertEquals(0, ring.size)
        val t = tl(1); ring.addLast(t); assertSame(t, ring[0])
    }
}
