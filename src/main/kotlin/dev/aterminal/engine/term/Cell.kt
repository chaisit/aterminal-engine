package dev.aterminal.engine.term
// Portions derived from xterm.js (MIT), see THIRD_PARTY_NOTICES.md

import java.util.Arrays

// Cell = one grid cell — the 15-field tuple of DUMP_EXPR (the conformance dump) is the contract:
// [ch, w, fg, fgMode, bg, bgMode, bold, italic, dim, underline, blink, inverse, invisible, strike, overline]
// fg/fgMode etc. values live in xterm.js 5.5's "number space" — do NOT rely on memory, verify against the real package
// dummy (right half of a wide char) = ch "" w 0 + the pen's color/attrs at print time (irm/wide probes: dummies carry the pen)
class Cell(
    var ch: String = " ",
    var width: Int = 1,           // 1, 2 (wide), 0 (dummy, right half of a wide char)
    var fg: Int = DEFAULT_COLOR,  // -1 = default
    var fgMode: Int = 0,
    var bg: Int = DEFAULT_COLOR,
    var bgMode: Int = 0,
    var attrs: Int = 0,           // bit = B_*
) {
    fun dup(): Cell = Cell(ch, width, fg, fgMode, bg, bgMode, attrs)

    companion object {
        const val DEFAULT_COLOR = -1
        const val B_BOLD = 1; const val B_DIM = 2; const val B_ITALIC = 4
        const val B_UNDERLINE = 8; const val B_BLINK = 16; const val B_INVERSE = 32
        const val B_INVISIBLE = 64; const val B_STRIKE = 128; const val B_OVERLINE = 256
        // DECSCA protected (bit in xterm's bg word — 536870912) — never appears in the dump (DUMP_EXPR has no protect getter)
        const val B_PROTECT = 512
    }
}

/**
 * One buffer row — Array of size cols; `null` = default cell (saves scrollback across 9025 lines)
 * Read via [get] (read-only — never mutate the result); write via [mutableAt]/[fill]/cell ops
 */
class TermLine(val cols: Int) {
    private var cells: Array<Cell?>? = null
    var isWrapped = false // xterm.js line.isWrapped (unused by the dump — kept to prevent wrong row joins during reflow)

    fun get(i: Int): Cell = cells?.get(i) ?: DEFAULT

    /** raw cell (null = never written — used by REP, which must distinguish a "blank cell" from default per xterm.js getString) */
    fun raw(i: Int): Cell? = cells?.get(i)

    fun mutableAt(i: Int): Cell {
        var arr = cells
        if (arr == null) { arr = arrayOfNulls<Cell?>(cols); cells = arr }
        var c = arr[i]
        if (c == null) { c = Cell(); arr[i] = c }
        return c
    }

    /**
     * Write proto over the range [from,to) — proto=null means default across the row (collapsed to null)
     * xterm.js erase = "erase color" (default fg, bg = pen bg, attrs cleared) → the caller builds proto
     */
    fun fill(from: Int, to: Int, proto: Cell?) {
        val to2 = minOf(to, cols) // right-side clamp (EL1/ED1 with cursor pending = x+1 > cols)
        if (from >= to2) return
        if (proto == null) { // default range = remove cells (collapse) — do not wipe the whole row unless the range is full
            val arr = cells ?: return
            Arrays.fill(arr, from, to2, null)
            if (from == 0 && to2 == cols && to >= cols) cells = null else compact()
            return
        }
        val arr = cells ?: arrayOfNulls<Cell?>(cols).also { cells = it }
        for (i in from until to2) {
            val c = arr[i]
            if (c == null) arr[i] = proto.dup()
            else { c.ch = proto.ch; c.width = proto.width; c.attrs = proto.attrs; c.fg = proto.fg; c.fgMode = proto.fgMode; c.bg = proto.bg; c.bgMode = proto.bgMode }
        }
        compact()
    }

    fun clearAll() { cells = null }

    /** ICH: shift [x, cols-n) right by n, drop overflow, [x,x+n) = proto — bundle literal + orphan fixes at both edges */
    fun insertCells(x: Int, n: Int, proto: Cell?) {
        if (n <= 0) return
        if (x > 0 && get(x - 1).width == 2) blankAt(x - 1, proto) // pre-shift: left half of a wide char at the range's left edge → code0 blank (bundle)
        val old = snapshot()
        val arr = cells ?: arrayOfNulls<Cell?>(cols).also { cells = it }
        for (i in 0 until cols) {
            arr[i] = when {
                i < x -> old[i]
                i < x + n -> proto?.dup()
                else -> old[i - n] // shift right (i-n ∈ [x, cols-n) always valid)
            }
        }
        if (get(cols - 1).width == 2) blankAt(cols - 1, proto) // post: left half of a wide char shifted flush against the right edge → code0 blank (bundle)
        compact()
    }

    /** DCH: shift left — new[i>=x] = old[i+n] (past the tail = proto), [x,x+n) = proto — bundle literal + orphan fixes */
    fun deleteCells(x: Int, n: Int, proto: Cell?) {
        if (n <= 0) return
        val old = snapshot()
        val arr = cells ?: arrayOfNulls<Cell?>(cols).also { cells = it }
        for (i in x until cols) {
            arr[i] = if (i + n < cols) old[i + n] else proto?.dup() // delete [x,x+n), shift left, tail = erase blank
        }
        // post-shift (bundle literal, this order): left edge hit / leftover dummy at x → code0 blank w1
        if (x > 0 && get(x - 1).width == 2) blankAt(x - 1, proto)
        if (get(x).width == 0 && get(x).ch.isEmpty()) blankAt(x, proto)
        compact()
    }

    /** setCellFromCodepoint(i, NULL_CELL_CODE=0, 1, proto) — code0 blank w1 (ch "" = no content per getString/REP) */
    private fun blankAt(i: Int, proto: Cell?) {
        val c = mutableAt(i)
        c.ch = ""; c.width = 1
        c.fg = proto?.fg ?: Cell.DEFAULT_COLOR; c.fgMode = proto?.fgMode ?: 0
        c.bg = proto?.bg ?: Cell.DEFAULT_COLOR; c.bgMode = proto?.bgMode ?: 0
        c.attrs = proto?.attrs ?: 0
    }

    /** ECH: replace n cells with proto without shifting */
    fun eraseCells(x: Int, n: Int, proto: Cell?) = replaceCells(x, x + n, proto)

    /** replaceCells(start, end, fill) from the bundle: first blank the half of a wide char left outside the range */
    fun replaceCells(from: Int, to: Int, proto: Cell?) {
        if (from > 0 && get(from - 1).width == 2) blankAt(from - 1, proto)
        if (to in 1 until cols && get(to - 1).width == 2) blankAt(to, proto)
        fill(from, to, proto)
    }

    /** DECSED/DECSEL = replaceCells(...,protect=true) — literal from the bundle:
     *  edge orphan fix (skipped when the protect check says so), then write one cell at a time, skipping protected cells
     *  (right edge checks isProtected at dummy t, not head t-1 — per the bundle) */
    fun replaceProtected(from: Int, to: Int, proto: Cell?) {
        if (from > 0 && get(from - 1).width == 2 && !isProtectedAt(from - 1)) blankAt(from - 1, proto)
        if (to in 1 until cols && get(to - 1).width == 2 && !isProtectedAt(to)) blankAt(to, proto)
        var i = from
        val to2 = minOf(to, cols)
        while (i < to2) {
            if (!isProtectedAt(i)) {
                val arr = cells ?: arrayOfNulls<Cell?>(cols).also { cells = it }
                val c = arr[i]
                if (proto == null) arr[i] = null
                else if (c == null) arr[i] = proto.dup()
                else { c.ch = proto.ch; c.width = proto.width; c.attrs = proto.attrs; c.fg = proto.fg; c.fgMode = proto.fgMode; c.bg = proto.bg; c.bgMode = proto.bgMode }
            }
            i++
        }
        compact()
    }

    private fun isProtectedAt(i: Int) = raw(i)?.let { it.attrs and Cell.B_PROTECT != 0 } ?: false

    // ---------- reflow helpers (port of xterm.js BufferLine.ts) ----------

    /** xterm getTrimmedLength: i + width of the rightmost cell with content (HAS_CONTENT = code≠0 ⇔ ch non-empty — dummy/blank ch "" has no content) */
    fun trimmedLength(): Int {
        val arr = cells ?: return 0
        for (i in cols - 1 downTo 0) {
            val c = arr[i] ?: continue
            if (c.ch.isNotEmpty()) return i + c.width // literal ≡ oracle (wide head at the last col = cols+1 — bug-compat)
        }
        return 0
    }

    /** xterm hasContent(i) */
    fun hasContentAt(i: Int): Boolean = raw(i)?.ch?.isNotEmpty() == true

    /** xterm getWidth(i) (empty cell = nullCell width 1) */
    fun widthAt(i: Int): Int = get(i).width

    /** setCell(i, nullCell) — null = default cell (already compacted) exactly; code0-blank-default ≡ null (isDefault rule) */
    fun setNull(i: Int) {
        cells?.set(i, null)
    }

    /**
     * BufferLine.replaceCells(start, end, nullCell, respectProtect=false) as used by reflow:
     * orphan-wide fix at both edges (setCellFromCodepoint(0,1,nullCell) ≡ null) + setCell(null) over the whole range
     */
    fun replaceWithNull(start0: Int, end0: Int) {
        var start = start0
        var end = end0
        if (start > 0 && widthAt(start - 1) == 2) setNull(start - 1)
        if (end < cols && widthAt(end - 1) == 2) setNull(end)
        var i = start
        while (i < end && i < cols) {
            setNull(i)
            i++
        }
    }

    /**
     * BufferLine.copyCellsFrom — raw cell copy (null→null ≡ oracle's nullCell copy)
     * Order matters (same-line overlap during _reflowSmaller): reverse = read high→low so writes don't clobber unread cells
     * (combined data in the oracle moves with each cell — ours keeps ch as a String inside the cell already ≡ observed equal)
     */
    fun copyCellsFrom(src: TermLine, srcCol: Int, destCol: Int, length: Int, reverse: Boolean) {
        if (length <= 0) return
        val arr = cells ?: arrayOfNulls<Cell?>(cols).also { cells = it }
        var k = if (reverse) length - 1 else 0
        while (if (reverse) k >= 0 else k < length) {
            arr[destCol + k] = src.raw(srcCol + k)?.dup()
            k += if (reverse) -1 else 1
        }
    }

    /**
     * BufferLine.resize(newCols, nullCell) — grow: new cells = null (nullCell default); shrink: view cut (cells dropped whole)
     * Always returns a new object (engine TermLine.cols is val) — isWrapped preserved ≡ oracle
     */
    fun copyToWidth(n: Int): TermLine {
        if (n == cols) return this
        val out = TermLine(n)
        out.isWrapped = isWrapped
        val src = cells ?: return out
        val m = minOf(cols, n)
        val arr = arrayOfNulls<Cell?>(n)
        for (i in 0 until m) src[i]?.let { arr[i] = it.dup() }
        out.cells = arr
        return out
    }

    /** deep copy from another row (Cell instances are not shared) */
    fun copyFrom(other: TermLine) {
        isWrapped = other.isWrapped
        val src = other.cells
        if (src == null) { cells = null; return }
        val arr = arrayOfNulls<Cell?>(cols)
        val n = minOf(cols, other.cols)
        for (i in 0 until n) src[i]?.let { arr[i] = it.dup() }
        cells = arr
        compact()
    }

    /** snapshot into a new array (cells already duplicated — the result belongs to the caller) */
    private fun snapshot(): Array<Cell?> {
        val out = arrayOfNulls<Cell>(cols)
        val src = cells ?: return out
        for (i in 0 until cols) src[i]?.let { out[i] = it.dup() }
        return out
    }

    /** collapse cells equal to default → null (keeps the array from bloating after erase/shift) */
    fun compact() {
        val arr = cells ?: return
        for (i in 0 until cols) arr[i]?.let { if (it.isDefault()) arr[i] = null }
        var any = false
        for (i in 0 until cols) if (arr[i] != null) { any = true; break }
        if (!any) cells = null
    }

    fun isAllDefault(): Boolean = cells == null

    /** the two tuple values most often used by null-cell/erase */
    companion object {
        /** do not mutate — read-only */
        val DEFAULT = Cell()
    }
}

/**
 * collapse rule (compact): equivalent to the bundle's "unwritten" = code0 blank (NULL_CELL_CODE=0) with default color/attrs
 * Note: a genuinely typed space (code 32, ch=" ") does NOT collapse — the bundle stores it as a content cell
 * (differs from REP's getString: " " vs "" — probe rep-clear)
 */
fun Cell.isDefault(): Boolean =
    ch == "" && width == 1 && fg == Cell.DEFAULT_COLOR && fgMode == 0 && bg == Cell.DEFAULT_COLOR && bgMode == 0 && attrs == 0
