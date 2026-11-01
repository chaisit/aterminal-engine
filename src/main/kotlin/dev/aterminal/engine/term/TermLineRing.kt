package dev.aterminal.engine.term

// TermLineRing — circular buffer of TermLine references replacing ArrayList in ScreenBuffer
// Real-world reason (device): flood "yes" → trimHead removes the head one line at a time via ArrayList.removeAt(0) = O(n) each time
//   (n ~ rows+maxHistory ≈ 9k) → the consumer (draining 64KB/16ms) can't keep up with the producer = queue bloats → OOM
// speed: get/set/addLast/addFirst/pollFirst/popLast = O(1)
//        add(index)/removeAt(index) = O(shift distance ≤ size) — the remaining paths (IL/DI/partial-scroll) already shift ≤ region
// semantics: a normal List<TermLine> (AbstractMutableList) — existing callers (lines[i], size, add, removeAt, clear, iterate) unchanged
// do NOT treat as an object pool: stores references only — TermLine is mutated in place (copyFrom/fill) and aliased across buffers, never recycle
class TermLineRing(capacityHint: Int) : AbstractMutableList<TermLine>() {
    private var a: Array<TermLine?> = arrayOfNulls(nextPow2(maxOf(4, capacityHint)))
    private var head = 0
    private var n = 0

    override val size: Int get() = n

    private fun slot(i: Int): Int = (head + i) and (a.size - 1)

    override fun get(index: Int): TermLine {
        if (index < 0 || index >= n) throw IndexOutOfBoundsException("index=$index size=$n")
        return a[slot(index)]!!
    }

    override fun set(index: Int, element: TermLine): TermLine {
        if (index < 0 || index >= n) throw IndexOutOfBoundsException("index=$index size=$n")
        val old = a[slot(index)]!!
        a[slot(index)] = element
        return old
    }

    /** addLast O(1) */
    fun addLast(element: TermLine) {
        growFor(n + 1)
        a[slot(n)] = element
        n++
    }

    /** addFirst O(1) */
    fun addFirst(element: TermLine) {
        growFor(n + 1)
        head = (head - 1) and (a.size - 1)
        a[head] = element
        n++
    }

    /** remove the head O(1) — the heart of trimHead under flood */
    fun pollFirst(): TermLine? {
        if (n == 0) return null
        val e = a[head]!!
        a[head] = null
        head = (head + 1) and (a.size - 1)
        n--
        return e
    }

    override fun add(element: TermLine): Boolean { addLast(element); return true }

    override fun add(index: Int, element: TermLine) {
        if (index == n) { addLast(element); return }
        if (index == 0) { addFirst(element); return }
        if (index < 0 || index > n) throw IndexOutOfBoundsException("index=$index size=$n")
        growFor(n + 1)
        var k = n
        while (k > index) { a[slot(k)] = a[slot(k - 1)]; k-- } // shift right [index, n)
        a[slot(index)] = element
        n++
    }

    override fun removeAt(index: Int): TermLine {
        if (index == 0) return pollFirst()!!
        if (index < 0 || index >= n) throw IndexOutOfBoundsException("index=$index size=$n")
        val e = a[slot(index)]!!
        if (index == n - 1) { a[slot(index)] = null; n--; return e }
        var k = index
        while (k < n - 1) { a[slot(k)] = a[slot(k + 1)]; k++ } // shift left (index, n)
        a[slot(n - 1)] = null
        n--
        return e
    }

    /** clear all references (helps GC) — capacity unchanged */
    override fun clear() {
        var i = 0
        while (i < n) { a[slot(i)] = null; i++ }
        head = 0
        n = 0
    }

    private fun growFor(min: Int) {
        if (min <= a.size) return
        var cap = a.size
        while (cap < min) cap = cap shl 1
        val b = arrayOfNulls<TermLine>(cap)
        var i = 0
        while (i < n) { b[i] = a[slot(i)]; i++ } // linearize, head=0
        a = b
        head = 0
    }

    private fun nextPow2(v: Int): Int {
        var c = 4
        while (c < v) c = c shl 1
        return c
    }
}
