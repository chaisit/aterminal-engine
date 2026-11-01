package dev.aterminal.engine.term
// Portions derived from xterm.js (MIT), see THIRD_PARTY_NOTICES.md

// Faithful port of xterm.js common/buffer/BufferReflow.ts + common/buffer/Buffer.ts (_reflowLarger/_reflowSmaller)
// from the @xterm/headless 5.5.0 bundle (source recovered from the package sourcemap)
//
// Rule (deliberate): **bug-compatible ≡ oracle** — never "fix" behavior a probe captured, e.g.
//  - the line group containing the cursor: reflow skips it ("the program will handle") → shrink = data loss (oracle bug, probe 01/05)
//  - viewportAdjustments/wide-char edge rules: line-for-line from source, no reinterpretation
//
// ydisp = ybase always (replay has no user scroll) → the whole `if (ydisp === ybase)` branch disappears (no-op for the dump)
// every function mutates `lines` (refs) and ResizeState — the caller (TerminalEngine.resizeBuffer) owns the state

/** mutable state during resize — ybase is an explicit number (the engine normally derives it as lines.size - rows) */
class ResizeState(var ybase: Int, var y: Int, var savedY: Int)

object Reflow {

    /** reflowLargerGetLinesToRemove: returns a list of (startIndex, count) — indices refer to the current lines (before removal) */
    fun largerGetLinesToRemove(lines: List<TermLine>, oldCols: Int, newCols: Int, absY: Int): List<IntArray> {
        val toRemove = ArrayList<IntArray>()
        var y = 0
        while (y < lines.size - 1) { // tail case: the last row cannot wrap out
            var i = y
            var nextLine: TermLine? = lines[++i]
            if (nextLine == null || !nextLine.isWrapped) { y++; continue }
            val wrapped = ArrayList<TermLine>()
            wrapped.add(lines[y])
            while (i < lines.size && nextLine != null && nextLine.isWrapped) {
                wrapped.add(nextLine)
                i++
                nextLine = if (i < lines.size) lines[i] else null
            }
            // the group containing the cursor must not be touched ≡ oracle literal ("the program will handle fixing up wrapped lines with the cursor")
            if (absY >= y && absY < i) { y += wrapped.size; continue }

            var destLineIndex = 0
            var destCol = wrappedTrimmed(wrapped, destLineIndex, oldCols)
            var srcLineIndex = 1
            var srcCol = 0
            while (srcLineIndex < wrapped.size) {
                val srcTrimmed = wrappedTrimmed(wrapped, srcLineIndex, oldCols)
                val cellsToCopy = minOf(srcTrimmed - srcCol, newCols - destCol)
                wrapped[destLineIndex].copyCellsFrom(wrapped[srcLineIndex], srcCol, destCol, cellsToCopy, false)
                destCol += cellsToCopy
                if (destCol == newCols) { destLineIndex++; destCol = 0 }
                srcCol += cellsToCopy
                if (srcCol == srcTrimmed) { srcLineIndex++; srcCol = 0 }
                // wide char landing as the last cell of a dest row → move it to the next row + null the source (literal)
                if (destCol == 0 && destLineIndex != 0) {
                    if (wrapped[destLineIndex - 1].widthAt(newCols - 1) == 2) {
                        wrapped[destLineIndex].copyCellsFrom(wrapped[destLineIndex - 1], newCols - 1, destCol++, 1, false)
                        wrapped[destLineIndex - 1].setNull(newCols - 1)
                    }
                }
            }
            wrapped[destLineIndex].replaceWithNull(destCol, newCols)

            var countToRemove = 0
            var k = wrapped.size - 1
            while (k > 0) {
                if (k > destLineIndex || wrapped[k].trimmedLength() == 0) countToRemove++ else break
                k--
            }
            if (countToRemove > 0) toRemove.add(intArrayOf(y + wrapped.size - countToRemove, countToRemove))
            y += wrapped.size
        }
        return toRemove
    }

    /**
     * _reflowLarger whole function: compute removals → apply layout → adjustViewport
     * (createNewLayout/applyNewLayout = filter out the removed ranges — same result; the oracle uses a layout list because CircularList.splice is expensive)
     */
    fun reflowLarger(lines: MutableList<TermLine>, oldCols: Int, newCols: Int, newRows: Int, st: ResizeState) {
        val toRemove = largerGetLinesToRemove(lines, oldCols, newCols, st.ybase + st.y)
        if (toRemove.isEmpty()) return
        val keep = ArrayList<TermLine>(lines.size)
        var ri = 0
        var removedTotal = 0
        var i = 0
        while (i < lines.size) {
            if (ri < toRemove.size && toRemove[ri][0] == i) {
                removedTotal += toRemove[ri][1]
                i += toRemove[ri][1]
                ri++
            } else {
                keep.add(lines[i]); i++
            }
        }
        lines.clear(); lines.addAll(keep)
        // _reflowLargerAdjustViewport (literal)
        var adj = removedTotal
        while (adj-- > 0) {
            if (st.ybase == 0) {
                if (st.y > 0) st.y--
                if (lines.size < newRows) lines.add(TermLine(newCols))
            } else {
                st.ybase--
            }
        }
        st.savedY = maxOf(st.savedY - removedTotal, 0)
    }

    /** reflowSmallerGetNewLineLengths — wrap points decided ahead of time (wide atomic: endsWithWide → newCols-1) */
    fun smallerGetNewLineLengths(wrapped: List<TermLine>, oldCols: Int, newCols: Int): List<Int> {
        val out = ArrayList<Int>()
        var cellsNeeded = 0
        for (j in wrapped.indices) cellsNeeded += wrappedTrimmed(wrapped, j, oldCols)
        var srcCol = 0
        var srcLine = 0
        var cellsAvailable = 0
        while (cellsAvailable < cellsNeeded) {
            if (cellsNeeded - cellsAvailable < newCols) { out.add(cellsNeeded - cellsAvailable); break }
            srcCol += newCols
            val oldTrimmed = wrappedTrimmed(wrapped, srcLine, oldCols)
            if (srcCol > oldTrimmed) { srcCol -= oldTrimmed; srcLine++ }
            // srcCol==0 (fuzz case: trailing line trimmed=0) → oracle reads _data[-4] = undefined → NaN === 2 = false (no throw)
            // ≡ false, exact semantics — Kotlin must never AIOOBE (FuzzNoThrow invariant)
            val endsWithWide = srcCol > 0 && wrapped[srcLine].widthAt(srcCol - 1) == 2
            if (endsWithWide) srcCol--
            val lineLength = if (endsWithWide) newCols - 1 else newCols
            out.add(lineLength)
            cellsAvailable += lineLength
        }
        return out
    }

    /**
     * _reflowSmaller whole function (incl. the final rebuild that splices into `lines` in one pass)
     * cap = lines.maxLength (= newRows + hist) — literal in every branch, incl. the trailing trim-overflow
     */
    fun reflowSmaller(lines: MutableList<TermLine>, oldCols: Int, newCols: Int, newRows: Int, cap: Int, st: ResizeState) {
        class Ins(val start: Int, val newLines: ArrayList<TermLine>)
        val toInsert = ArrayList<Ins>()
        var countToInsert = 0
        var y = lines.size - 1
        while (y >= 0) { // backwards: many lines may be trimmed later — avoid considering them
            val first = lines[y]
            if (!first.isWrapped && first.trimmedLength() <= newCols) { y--; continue } // oversized NOT-wrapped lines also split (probe S5 — literal condition)
            val wrapped = ArrayList<TermLine>()
            wrapped.add(first)
            // track the group with the real y (oracle: --y on the loop var — the walk must continue at head-1, not loop back inside the group)
            while (lines[y].isWrapped && y > 0) wrapped.add(0, lines[--y])
            // cursor inside the group → skip the whole group (literal, ≡ larger)
            val absoluteY = st.ybase + st.y
            if (absoluteY >= y && absoluteY < y + wrapped.size) { y--; continue }

            val lastLineLength = wrapped[wrapped.size - 1].trimmedLength()
            val destLineLengths = smallerGetNewLineLengths(wrapped, oldCols, newCols)
            val linesToAdd = destLineLengths.size - wrapped.size
            val trimmedLines = if (st.ybase == 0 && st.y != lines.size - 1) {
                maxOf(0, st.y - cap + linesToAdd)
            } else {
                maxOf(0, lines.size - cap + linesToAdd)
            }
            val newLines = ArrayList<TermLine>(linesToAdd)
            repeat(linesToAdd) { val t = TermLine(newCols); t.isWrapped = true; newLines.add(t) } // getBlankLine(DEFAULT, true)
            if (newLines.isNotEmpty()) {
                toInsert.add(Ins(y + wrapped.size + countToInsert, newLines))
                countToInsert += newLines.size
            }
            wrapped.addAll(newLines)

            // backwards in-place copy (literal)
            var destLineIndex = destLineLengths.size - 1
            var destCol = destLineLengths[destLineIndex]
            if (destCol == 0) { destLineIndex--; if (destLineIndex >= 0) destCol = destLineLengths[destLineIndex] } // edge = JS undefined → loop breaks via the sanity guard
            var srcLineIndex = wrapped.size - linesToAdd - 1
            var srcCol = lastLineLength
            while (srcLineIndex >= 0) {
                if (destLineIndex < 0) break // ≡ oracle sanity check `wrappedLines[destLineIndex] === undefined`
                val cellsToCopy = minOf(srcCol, destCol)
                wrapped[destLineIndex].copyCellsFrom(wrapped[srcLineIndex], srcCol - cellsToCopy, destCol - cellsToCopy, cellsToCopy, true)
                destCol -= cellsToCopy
                if (destCol == 0) { destLineIndex--; if (destLineIndex >= 0) destCol = destLineLengths[destLineIndex] }
                srcCol -= cellsToCopy
                if (srcCol == 0) {
                    srcLineIndex--
                    srcCol = wrappedTrimmed(wrapped, maxOf(srcLineIndex, 0), oldCols)
                }
            }
            // wide char wrapped across the edge → null cell at the seam (literal — one cell only, the tail beyond it = intentionally stale)
            for (i in wrapped.indices) {
                if (destLineLengths[i] < newCols) wrapped[i].setNull(destLineLengths[i])
            }

            // viewport adjust (literal — lines.size is still the "before insert" value during the walk)
            var viewportAdjustments = linesToAdd - trimmedLines
            while (viewportAdjustments-- > 0) {
                if (st.ybase == 0) {
                    if (st.y < newRows - 1) { st.y++; lines.removeAt(lines.size - 1) } else { st.ybase++ }
                } else {
                    if (st.ybase < minOf(cap, lines.size + countToInsert) - newRows) st.ybase++
                }
            }
            st.savedY = minOf(st.savedY + linesToAdd, st.ybase + newRows - 1)
            y--
        }

        if (toInsert.isEmpty()) return
        // final rebuild: ≡ splicing every insertion point in one pass (oracle literal, incl. the trailing overflow-trim)
        val original = ArrayList(lines)
        val originalLength = lines.size
        var oi = originalLength - 1
        var ti = 0
        val newLen = minOf(cap, originalLength + countToInsert)
        val arr = arrayOfNulls<TermLine>(newLen)
        var insertedSoFar = 0
        var i = newLen - 1
        while (i >= 0) {
            val next = if (ti < toInsert.size) toInsert[ti] else null
            if (next != null && next.start > oi + insertedSoFar) {
                var ni = next.newLines.size - 1
                // JS literal: lines.set(i<0) = property write outside CircularList._array (no-op for the buffer) — i keeps running as before
                while (ni >= 0) { if (i >= 0) arr[i] = next.newLines[ni]; i--; ni-- }
                i++
                insertedSoFar += next.newLines.size
                ti++
            } else {
                // JS literal: originalLines[originalLineIndex--] — out-of-bounds read = undefined (no throw) → empty cell
                // (state fuzz can cause this: block start lagged behind the slot — the oracle writes undefined into that slot too)
                arr[i] = if (oi >= 0) original[oi] else null
                oi--
            }
            i--
        }
        lines.clear()
        for (j in 0 until newLen) lines.add(arr[j] ?: TermLine(newCols)) // undefined slot ≡ getLine()=undefined → dump shows an empty row
    }

    /** getWrappedLineTrimmedLength: group interior = cols, except (ends null + next line starts wide) → cols-1; last line = real trimmedLength */
    fun wrappedTrimmed(lines: List<TermLine>, i: Int, cols: Int): Int {
        if (i == lines.size - 1) return lines[i].trimmedLength()
        val endsInNull = !lines[i].hasContentAt(cols - 1) && lines[i].widthAt(cols - 1) == 1
        val followingLineStartsWithWide = lines[i + 1].widthAt(0) == 2
        return if (endsInNull && followingLineStartsWithWide) cols - 1 else cols
    }
}
