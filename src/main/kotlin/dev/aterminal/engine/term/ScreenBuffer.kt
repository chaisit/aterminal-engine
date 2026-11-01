package dev.aterminal.engine.term
// Portions derived from xterm.js (MIT), see THIRD_PARTY_NOTICES.md

// ScreenBuffer — flat list of lines = history + screen (screen = the last `rows` lines)
// this model matches xterm.js's dump output (CircularList + ybase):
//   baseY = lines.size - rows ; len = lines.size ; vp = baseY (replay has no user scroll)
// history rules (pinned from the bundle + probes region_scroll_history/su_full_history/ed2_history):
//   - scrollUp over the full-screen region (scrollTop==0 && scrollBottom==rows-1) on the main buffer → history +1 per line (cap rows+maxHistory)
//   - partial region or alt buffer → content pushed off the region top is gone (discarded)
//   - SU (CSI S) never adds history even over the full region — it is a plain splice (su_full_history evidence)
//   - ED2/DECSTR do not clear history; CSI 3J clears history; ESC c clears everything
// cursor: x ∈ [0..cols] (x==cols = "pending" — xterm.js really exposes cursorX=cols, ed0_pending evidence), y ∈ [0..rows-1]
class ScreenBuffer(val cols: Int, val rows: Int, val maxHistory: Int) {
    // ring of refs instead of ArrayList — trimHead/flood eviction must be O(1) (flood OOM lesson)
    val lines = TermLineRing(rows + maxHistory + 1)
    var x = 0
    var y = 0
    var scrollTop = 0
    var scrollBottom = rows - 1
    val tabs = BooleanArray(cols) { it % 8 == 0 } // DECSC slot lives in the engine (SavedCursor) — per buffer index

    init {
        repeat(rows) { lines.add(TermLine(cols)) }
    }

    val baseY: Int get() = lines.size - rows // always ≥ 0 (lines ≥ rows at all times)

    fun line(srow: Int): TermLine = lines[baseY + srow]

    fun newLine(wrapped: Boolean = false): TermLine {
        val t = TermLine(cols); t.isWrapped = wrapped; return t
    }

    /** wipe the whole buffer (leaving `rows` default screen lines) — RIS/alt-entry */
    fun clearAll() {
        lines.clear()
        repeat(rows) { lines.add(TermLine(cols)) }
        x = 0; y = 0; scrollTop = 0; scrollBottom = rows - 1
        tabs.fill(false); for (i in 0 until cols step 8) tabs[i] = true
    }

    /** refill the whole viewport with blank(proto) — the bundle's fillViewportRows(eraseAttrData) (alt entry) */
    fun refillBlank(proto: Cell?) {
        lines.clear()
        repeat(rows) { lines.add(blankLine(proto)) }
        scrollTop = 0; scrollBottom = rows - 1
    }

    // counts lines evicted from the head (monotonic per buffer instance)
    // the view measures a delta per write batch → compensation keeps a pinned view ≡ xterm content-lock
    // (a new buffer from resize/alt-swap = counter starts at 0 — the caller must check identity before measuring a delta)
    var evictedTotal = 0
        private set

    /** history over cap = trim the head — pollFirst O(1) per line (flood must not be O(n²)) */
    private fun trimHead() {
        while (lines.size > rows + maxHistory) { lines.pollFirst(); evictedTotal++ }
    }

    /**
     * scroll region [top,bot] up by n lines (content moves up, blank at the region bottom)
     * fullScreenHistory = the caller confirms this scroll comes from lineFeed/wrap (only LF/wrap earns history)
     */
    fun scrollUp(top: Int, bot: Int, n0: Int, proto: Cell?, fullScreenHistory: Boolean) {
        require(top <= bot)
        val len = bot - top + 1
        val n = maxOf(0, minOf(n0, len)) // clamp = region (oracle: n too large = undefined-ish — fixtures never do this)
        if (n == 0) return
        val full = top == 0 && bot == rows - 1
        if (full && fullScreenHistory && maxHistory > 0) {
            repeat(n) { lines.add(blankLine(proto)) }
            trimHead()
            return
        }
        if (full && maxHistory == 0) { // alt buffer: content pushed off the top is gone
            repeat(n) { lines.removeAt(0) }
            repeat(n) { lines.add(blankLine(proto)) }
            return
        }
        val a = baseY + top
        repeat(n) { lines.removeAt(a) }
        repeat(n) { lines.add(a + len - n, blankLine(proto)) }
    }

    /** scroll region [top,bot] down by n (content moves down, blank at the region top, overflow off the bottom = dropped) — RI/SD */
    fun scrollDown(top: Int, bot: Int, n0: Int, proto: Cell?) {
        val len = bot - top + 1
        val n = maxOf(0, minOf(n0, len))
        if (n == 0) return
        val a = baseY + top
        // delete the region tail n times: index shrinks by 1 each time (after each delete, new tail = a+len-1-k)
        repeat(n) { k -> lines.removeAt(a + len - 1 - k) }
        repeat(n) { lines.add(a, blankLine(proto)) }
    }

    /** IL: insert a blank line at srow, discard at scrollBottom, content [srow+1..bot] shifts down */
    fun insertLines(srow: Int, n0: Int, proto: Cell?) {
        val a = baseY + srow
        val b = baseY + scrollBottom
        val n = maxOf(0, minOf(n0, scrollBottom - srow + 1))
        repeat(n) { lines.removeAt(b); lines.add(a, blankLine(proto)) }
    }

    /** DL: delete at srow, blank at scrollBottom */
    fun deleteLines(srow: Int, n0: Int, proto: Cell?) {
        val a = baseY + srow
        val b = baseY + scrollBottom
        val n = maxOf(0, minOf(n0, scrollBottom - srow + 1))
        repeat(n) { lines.removeAt(a); lines.add(b, blankLine(proto)) }
    }

    private fun blankLine(proto: Cell?): TermLine {
        val t = TermLine(cols)
        if (proto != null) t.fill(0, cols, proto)
        return t
    }

    /** xterm.js nextStop: first stop ≥ x+1, none → cols-1 */
    fun nextTabFrom(x0: Int): Int {
        var t = x0 + 1
        while (t < cols - 1 && !tabs[t]) t++
        return minOf(t, cols - 1)
    }

    /** xterm.js prevStop: first stop ≤ x-1 (steps back before checking), none → 0 */
    fun prevTabFrom(x0: Int): Int {
        var t = x0 - 1
        while (t > 0 && !tabs[t]) t--
        return maxOf(t, 0)
    }

    /** xterm.js restrictCursor: clamp x ≤ cols-1, clamp y into the region/screen */
    fun restrictCursor(origin: Boolean) {
        x = minOf(maxOf(x, 0), cols - 1)
        y = if (origin) minOf(maxOf(y, scrollTop), scrollBottom) else minOf(maxOf(y, 0), rows - 1)
    }
}
