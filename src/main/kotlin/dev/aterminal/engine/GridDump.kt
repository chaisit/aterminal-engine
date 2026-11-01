package dev.aterminal.engine

import dev.aterminal.engine.term.Cell

/**
 * DUMP_EXPR-identical JSON (the conformance harness dump expression) — field order, 15-field tuple,
 * ch = getChars()||" " (dummy/code0 → " "), tail-trim = TUPLE comparison (isDef): ch==" " (after normalize),
 * w==1, colors (-1,0,-1,0), all attrs = 0 — note: tuple-based differs from the compact rule (Cell.isDefault uses ch=="")
 * — a really typed space is trimmable at dump time but is not collapsed in the buffer (REP semantics)
 * vp = viewportY — replay has no user scroll → always equals baseY
 */
object GridDump {
    fun dump(e: TerminalEngine): String {
        val b = e.buf
        val sb = StringBuilder(1 shl 16)
        sb.append("{\"cols\":").append(e.cols)
            .append(",\"rows\":").append(e.rows)
            .append(",\"x\":").append(b.x)
            .append(",\"y\":").append(b.y)
            .append(",\"baseY\":").append(b.baseY)
            .append(",\"vp\":").append(b.baseY)
            .append(",\"len\":").append(b.lines.size)
            .append(",\"cells\":[")
        for (r in 0 until e.rows) {
            if (r > 0) sb.append(',')
            val line = b.line(r)
            // tail-trim: count from the right, stop at the first non-default tuple (DUMP_EXPR's isDef — compares tuple values)
            var end = e.cols
            while (end > 0 && isDefCell(line.get(end - 1))) end--
            sb.append('[')
            for (x in 0 until end) {
                if (x > 0) sb.append(',')
                appendCell(sb, line.get(x))
            }
            sb.append(']')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun appendCell(sb: StringBuilder, c: Cell) {
        val ch = if (c.ch.isEmpty()) " " else c.ch // getChars() || ' '
        sb.append('[')
            .append(jsonQuote(ch)).append(',')
            .append(c.width).append(',')
            .append(c.fg).append(',').append(c.fgMode).append(',')
            .append(c.bg).append(',').append(c.bgMode).append(',')
            .append(bit(c, Cell.B_BOLD)).append(',')
            .append(bit(c, Cell.B_ITALIC)).append(',')
            .append(bit(c, Cell.B_DIM)).append(',')
            .append(bit(c, Cell.B_UNDERLINE)).append(',')
            .append(bit(c, Cell.B_BLINK)).append(',')
            .append(bit(c, Cell.B_INVERSE)).append(',')
            .append(bit(c, Cell.B_INVISIBLE)).append(',')
            .append(bit(c, Cell.B_STRIKE)).append(',')
            .append(bit(c, Cell.B_OVERLINE))
            .append(']')
    }

    private fun bit(c: Cell, mask: Int) = if (c.attrs and mask != 0) 1 else 0

    /** DUMP_EXPR's isDef (tuple-based): ch (normalized ' ') + w1 + (-1,0,-1,0) + attrs 0 — differs from Cell.isDefault (compact)
     *  mask 0x1FF = the 9 attr bits actually dumped; B_PROTECT never appears in the tuple (xterm has no getter → invisible to DUMP_EXPR) */
    private fun isDefCell(c: Cell): Boolean =
        (c.ch == " " || c.ch.isEmpty()) && c.width == 1 &&
            c.fg == Cell.DEFAULT_COLOR && c.fgMode == 0 && c.bg == Cell.DEFAULT_COLOR && c.bgMode == 0 &&
            (c.attrs and 0x1FF) == 0

    private fun jsonQuote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch < ' ' -> sb.append("\\u").append(String.format("%04x", ch.code)) // real cells contain no control chars
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
