package dev.aterminal.engine

import org.junit.Assert.assertEquals
import org.junit.Test

// GridDump exact-string — must match the harness DUMP_EXPR (headless oracle) character by character
// (15-field tuple = [ch,w,fg,fgMode,bg,bgMode,bold,italic,dim,underline,blink,inverse,invisible,strike,overline]
//  — attr bits expanded to 0/1 in Cell's B_* order; JSON format = exactly the shape of common.mjs dumpGrid)
// helper x(): 4-digit hex placeholder — "~001b" → ESC, "~000d" → CR, "~000a" → LF
class GridDumpTest {
    private fun x(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '~' && i + 5 <= s.length) {
                sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 5
            } else { sb.append(s[i]); i++ }
        }
        return sb.toString()
    }

    private fun dump(vararg s: String, c: Int = 4, r: Int = 2, h: Int = 0): String {
        val e = TerminalEngine(c, r, h)
        for (b in s) e.write(x(b).toByteArray(Charsets.UTF_8))
        return GridDump.dump(e)
    }

    // tuple shorthand (15 fields)
    private val DEF = "[\" \",1,-1,0,-1,0,0,0,0,0,0,0,0,0,0]"
    private fun tD(ch: String) = "[\"$ch\",1,-1,0,-1,0,0,0,0,0,0,0,0,0,0]"

    @Test fun exactStringSmallGrid() {
        // cells":[ + row "[" + cell "[" → "[[[" before the first char of a row that has cells
        assertEquals(
            "{\"cols\":4,\"rows\":2,\"x\":2,\"y\":0,\"baseY\":0,\"vp\":0,\"len\":2,\"cells\":" +
                "[[${tD("A")},${tD("B")}],[]]}",
            dump("AB")
        )
    }

    @Test fun boldFlagExpandsToTuplePosition6() {
        // tuple index 6 = bold (attr bit 1) — order follows DUMP_EXPR
        assertEquals(
            "{\"cols\":4,\"rows\":2,\"x\":1,\"y\":0,\"baseY\":0,\"vp\":0,\"len\":2,\"cells\":" +
                "[[[\"A\",1,-1,0,-1,0,1,0,0,0,0,0,0,0,0]],[]]}",
            dump("~001b[1mA")
        )
    }

    @Test fun allDefaultRowIsEmptyArray() {
        // printing less than a full row → that row trims down to the written cells; a fully blank row = []
        assertEquals(
            "{\"cols\":4,\"rows\":2,\"x\":1,\"y\":1,\"baseY\":0,\"vp\":0,\"len\":2,\"cells\":" +
                "[[${tD("A")}],[${tD("B")}]]}",
            dump("A~001b[2;1HB")
        )
    }

    @Test fun tailTrimExact() {
        // isDefCell = tuple-based: " " w1 default = default → dropped by tail trim (same as DUMP_EXPR)
        assertEquals(
            "{\"cols\":4,\"rows\":2,\"x\":2,\"y\":0,\"baseY\":0,\"vp\":0,\"len\":2,\"cells\":" +
                "[[${tD("A")}],[]]}",
            dump("A ")
        )
        // middle space (not tail) must remain
        assertEquals(
            "{\"cols\":4,\"rows\":2,\"x\":3,\"y\":0,\"baseY\":0,\"vp\":0,\"len\":2,\"cells\":" +
                "[[${tD("A")},${DEF},${tD("B")}],[]]}",
            dump("A B")
        )
    }

    @Test fun code0BlankDumpsAsSpaceWithPenBg() {
        // erase blank (ch "") → " " + bg pen (44 = index 4 → C16 mode 1 shl 24 = 16777216), fg/attrs = default
        val b = "[\" \",1,-1,0,4,16777216,0,0,0,0,0,0,0,0,0]"
        assertEquals(
            "{\"cols\":4,\"rows\":2,\"x\":0,\"y\":0,\"baseY\":0,\"vp\":0,\"len\":2,\"cells\":" +
                "[[$b,$b,$b,$b],[$b,$b,$b,$b]]}",
            dump("~001b[44mA~001b[1;1H~001b[0J")
        )
    }

    @Test fun wideDummyDumpsAsZeroWidthSpace() {
        assertEquals(
            "{\"cols\":4,\"rows\":2,\"x\":2,\"y\":0,\"baseY\":0,\"vp\":0,\"len\":2,\"cells\":" +
                "[[[\"한\",2,-1,0,-1,0,0,0,0,0,0,0,0,0,0],[\" \",0,-1,0,-1,0,0,0,0,0,0,0,0,0,0]],[]]}",
            dump("한")
        )
    }

    @Test fun protectedBlankTailTrimsLikeDefault() {
        // protect bit (B_PROTECT) never surfaces in the tuple → invisible to DUMP_EXPR's isDef → tail-trimmed like an ordinary blank
        // (DUMP_EXPR reads through the xterm getter, which has no protect — gold g11u12: protected characters dump as ordinary tuples)
        assertEquals(
            "{\"cols\":4,\"rows\":2,\"x\":2,\"y\":0,\"baseY\":0,\"vp\":0,\"len\":2,\"cells\":" +
                "[[${tD("A")}],[]]}",
            dump("~001b[1~0022qA ")
        )
    }

    @Test fun historyRowsNotDumped() {
        // len = total (history + viewport) but cells = viewport (2 rows) — 2 LFs at r1 → one scroll, vp = baseY
        assertEquals(
            "{\"cols\":2,\"rows\":2,\"x\":0,\"y\":1,\"baseY\":1,\"vp\":1,\"len\":3,\"cells\":" +
                "[[${tD("a")}],[]]}",
            dump("a~000d~000a".repeat(2), c = 2, r = 2, h = 4)
        )
    }
}
