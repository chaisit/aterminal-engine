package dev.aterminal.engine

import dev.aterminal.engine.term.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// hermetic micro-streams — every case is a PROBE pinned from the oracle (recorded against the real package):
//   erase-semantics / reset-scope / reset-scope2 / final-semantics / rep-clear — never edit expectations from memory
//   (probe name is given in each test's comment; tuple values = DUMP-normalized: code0 blank "" → " ")
//
// helper x("...") = hex escape so control chars cannot be lost in source: "~001b" → ESC, "~000d" → CR, "~000a" → LF, "~0009" → TAB, "~0008" → BS
// ('~' never appears as a literal character in any body in this file)
class TerminalEngineTest {
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

    private val ESC = 27.toChar().toString()
    private val C16 = 1 shl 24
    private val C256 = 1 shl 25
    private val RGB = 3 shl 24
    private val SARA_AA = 0x0e32.toChar().toString() // w1 (rep_combining probe uses this one, NOT Mai Han Akat!)
    private val MAI_EK = 0x0e48.toChar().toString()  // w0 combining (combining_lone probe)
    private val ACUTE = 0x0301.toChar().toString()   // w0 combining (join test)
    private val MAI_HAN_AKAT = 0x0e31.toChar().toString()

    private fun eng(c: Int = 80, r: Int = 24, h: Int = 9001, vararg s: String): TerminalEngine {
        val e = TerminalEngine(c, r, h)
        for (b in s) e.write(x(b).toByteArray(Charsets.UTF_8))
        return e
    }

    private fun cell(e: TerminalEngine, row: Int, col: Int) = e.buf.line(row).get(col)

    /** DUMP-normalized tuple (ch ""→" ") — compares directly against probe artifact digests */
    private fun tup(e: TerminalEngine, row: Int, col: Int): List<Any> {
        val c = cell(e, row, col)
        return listOf(if (c.ch.isEmpty()) " " else c.ch, c.width, c.fg, c.fgMode, c.bg, c.bgMode, c.attrs)
    }

    private fun t(ch: String, w: Int, fg: Int = -1, fm: Int = 0, bg: Int = -1, bm: Int = 0, at: Int = 0) =
        listOf<Any>(ch, w, fg, fm, bg, bm, at)

    private val DEF = t(" ", 1) // default tuple (unwritten or erase-default give the same tuple)

    // ================= print / wrap (pending model — ed0_pending + ris_wrap) =================

    @Test fun pendingWrapExposesCursorAtCols() { // ed0_pending: xterm.js expose cursorX = cols
        val e = eng(s = arrayOf("A".repeat(80)))
        assertEquals(80, e.buf.x); assertEquals(0, e.buf.y)
    }

    @Test fun ed0AtPendingErasesNothing() { // ed0_pending: ED0 at x==80 = fill(80,80) = no-op
        val e = eng(s = arrayOf("x".repeat(80), "~001b[0J"))
        assertEquals(t("x", 1), tup(e, 0, 79)); assertEquals(80, e.buf.x)
    }

    @Test fun printAfterPendingWraps() {
        val e = eng(s = arrayOf("A".repeat(80), "B"))
        assertEquals("B", cell(e, 1, 0).ch); assertEquals(1, e.buf.x); assertEquals(1, e.buf.y)
    }

    @Test fun wideSplitAtLastColFillsOldRowWithPen() { // bundle: wrap fill = setCellFromCodepoint(NULL,1,f) — code0 + pen
        val e = eng(s = arrayOf("x".repeat(79), "~001b[44m한"))
        assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e, 0, 79)) // dump: code0 → " "
        assertEquals(t("한", 2, -1, 0, 4, C16), tup(e, 1, 0))
        assertEquals(2, e.buf.x); assertEquals(1, e.buf.y)
    }

    @Test fun dummyCarriesPenAndIsZeroWidth() { // wide probes: dummy right half = ch "" w0 + pen
        val e = eng(s = arrayOf("~001b[31;42m한"))
        assertEquals(t(" ", 0, 1, C16, 2, C16), tup(e, 0, 1))
    }

    @Test fun nowrapWideSkippedAtEdge() { // nowrap_wide: continue at the last col — cell is not written
        val e = eng(s = arrayOf("~001b[?7l", "x".repeat(79), "한"))
        assertEquals(79, e.buf.x); assertEquals(0, e.buf.y)
        assertEquals(DEF, tup(e, 0, 79))
    }

    @Test fun nowrapOverwritesLastCol() { // ris_wrap: cursor at the edge with nowrap = every char overwrites the last col
        val e = eng(s = arrayOf("~001b[?7l", "x".repeat(80), "END"))
        assertEquals(80, e.buf.x); assertEquals(0, e.buf.y)
        assertEquals(t("x", 1), tup(e, 0, 78)); assertEquals(t("D", 1), tup(e, 0, 79))
        assertEquals(t("x", 1), tup(e, 0, 0))
    }

    @Test fun combiningJoinsLeftCell() {
        val e = eng(s = arrayOf("A~0301"))
        assertEquals("A$ACUTE", cell(e, 0, 0).ch); assertEquals(1, e.buf.x)
    }

    @Test fun combiningLoneAtZeroWritesZeroWidthCell() { // combining_lone: empty joinState → its own w0 cell + x++
        val e = eng(s = arrayOf("~0e48ABC"))
        assertEquals(t(MAI_EK, 0), tup(e, 0, 0))
        assertEquals("A", cell(e, 0, 1).ch); assertEquals(4, e.buf.x)
    }

    @Test fun combiningGateClearedByCsi() { // probe rep-clear family: CSI clears joinState → combining = lone
        val e = eng(s = arrayOf("A~001b[0m~0e31"))
        assertEquals("A", cell(e, 0, 0).ch)
        assertEquals(t(MAI_HAN_AKAT, 0), tup(e, 0, 1)); assertEquals(2, e.buf.x)
    }

    // ================= cursor family (reset-scope / final-semantics) =================

    @Test fun bsCancelsPendingThenMovesLeft() { // bs_pending: restrict(80→79) then 79→78 → Y overwrites col 78
        val e = eng(s = arrayOf("x".repeat(80), "~0008Y"))
        assertEquals(79, e.buf.x)
        assertEquals(t("Y", 1), tup(e, 0, 78)); assertEquals(t("x", 1), tup(e, 0, 79))
    }

    @Test fun cufCancelsPending() { // cuf_pending: restrict before move → 79
        val e = eng(s = arrayOf("A".repeat(80), "~001b[C"))
        assertEquals(79, e.buf.x)
    }

    @Test fun htPendingNoopAndDefaultStops() {
        assertEquals(80, eng(s = arrayOf("A".repeat(80), "~0009")).buf.x)
        assertEquals(8, eng(s = arrayOf("AB~0009")).buf.x)
    }

    @Test fun tabGapNotPaintedWithPen() { // tabstop_pen: HT does not write cells — the gap stays default
        val e = eng(s = arrayOf("~001b[43mAB~0009CD"))
        assertEquals(t("A", 1, -1, 0, 3, C16), tup(e, 0, 0))
        assertEquals(DEF, tup(e, 0, 2)); assertEquals(DEF, tup(e, 0, 7))
        assertEquals(t("C", 1, -1, 0, 3, C16), tup(e, 0, 8))
        assertEquals(10, e.buf.x)
    }

    @Test fun bareTabClearIsNoop() { // bundle switch(params[0]): bare CSI g = undefined (default stop at 8 still in effect)
        val e = eng(s = arrayOf("~001b[gAB~0009C"))
        assertEquals(9, e.buf.x)
    }

    @Test fun htsSetsStopAndTabLands() { // hts_set: ESC H sets a stop at col6 → AB TAB → 6, C → 7
        val e = eng(s = arrayOf("~001b[3gABCD~001b[1;7H~001bH~001b[1;1HAB~0009C"))
        assertEquals(7, e.buf.x)
        assertEquals(t("C", 1), tup(e, 0, 6))
        assertEquals(t("C", 1), tup(e, 0, 2)); assertEquals(t("D", 1), tup(e, 0, 3)) // the old "ABCD" is not deleted
    }

    @Test fun tabClearAllSendsTabToLastCol() { // tab_clear_all + hts_esc (CSI 5g = unsupported no-op)
        val e = eng(s = arrayOf("~001b[3gAB~0009C"))
        assertEquals(80, e.buf.x); assertEquals(t("C", 1), tup(e, 0, 79))
        val e2 = eng(s = arrayOf("~001b[3gAB~001b[1;5H~001b[5g~0009C"))
        assertEquals(80, e2.buf.x); assertEquals(t("C", 1), tup(e2, 0, 79))
    }

    @Test fun tabClearOneThenAll() { // tab_clear_one
        val e = eng(s = arrayOf("AB~001b[g~001b[3gCD~001b[gEF~0009G"))
        assertEquals(80, e.buf.x); assertEquals(t("G", 1), tup(e, 0, 79))
        assertEquals("ABCDEF", (0 until 6).joinToString("") { cell(e, 0, it).ch })
    }

    @Test fun lctRctNoStops() { // lct_rct: 39 →(2I×2) 79 →(Z) 0
        val e = eng(s = arrayOf("~001b[3g~001b[1;40H~001b[2I~001b[Z"))
        assertEquals(0, e.buf.x)
    }

    @Test fun lctAtLastColNoMove() { // ht_lastcol: nextStop ≥ 80 → clamp 79
        assertEquals(79, eng(s = arrayOf("~001b[1;80H~001b[I")).buf.x)
    }

    // ================= SGR number space (color-encoding.json + sgr38_* + underline_color) =================

    @Test fun sgrNumberSpace() {
        assertEquals(t("X", 1, 1, C16), tup(eng(s = arrayOf("~001b[31mX")), 0, 0))
        assertEquals(t("Y", 1, 1, C256), tup(eng(s = arrayOf("~001b[38;5;1mY")), 0, 0)) // no downgrade of C16 (probe)
        assertEquals(t("Z", 1, 16, C256), tup(eng(s = arrayOf("~001b[38;5;16mZ")), 0, 0))
        assertEquals(t("W", 1, 66051, RGB), tup(eng(s = arrayOf("~001b[38;2;1;2;3mW")), 0, 0))
        assertEquals(t("W", 1, 8, C16), tup(eng(s = arrayOf("~001b[90mW")), 0, 0))
        val e = eng(s = arrayOf("~001b[31;41m~001b[39;49mA"))
        assertEquals(t("A", 1), tup(e, 0, 0))
    }

    @Test fun bareCsiMResets() { // bare_m: AA bold-BB CC — bare CSI m = SGR 0
        val e = eng(s = arrayOf("AA~001b[1mBB~001b[mCC"))
        assertEquals(0, cell(e, 0, 0).attrs)
        assertEquals(Cell.B_BOLD, cell(e, 0, 2).attrs)
        assertEquals(0, cell(e, 0, 4).attrs)
        assertEquals(6, e.buf.x)
    }

    @Test fun underlineSubparamDoesNotLeakAsColor() { // underline_color: `4:31` = style 31, fg stays default
        val e = eng(s = arrayOf("~001b[4:31mQ"))
        assertEquals(t("Q", 1, -1, 0, -1, 0, Cell.B_UNDERLINE), tup(e, 0, 0))
    }

    @Test fun underlineLegacyStaysAfterStyleZero() { // isUnderline = legacy OR style (bundle getter) — after `4m`, `4:0m` is still ON
        val e = eng(s = arrayOf("~001b[4m~001b[4:0mX"))
        assertEquals(Cell.B_UNDERLINE, cell(e, 0, 0).attrs)
    }

    // ================= ED/EL taxonomy (erase-semantics) =================

    @Test fun eraseBlankIsCode0WithPenBgOnly() { // ed0_bg44/erase probe: code0 blank w1, fg default, attrs cleared
        val e = eng(s = arrayOf("~001b[31;44mAB~001b[1;1H~001b[0J"))
        assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e, 0, 0))
    }

    @Test fun ed2WithPenColorFillsEveryCell() { // ed2_bg41: every cell = (" ",1,-1,0,1,C16,0)
        val e = eng(s = arrayOf("~001b[41m~001b[2J"))
        assertEquals(t(" ", 1, -1, 0, 1, C16), tup(e, 0, 0))
        assertEquals(t(" ", 1, -1, 0, 1, C16), tup(e, 23, 79))
        assertEquals(0, e.buf.x); assertEquals(0, e.buf.y)
    }

    @Test fun elEraseMidPenAndBold() { // el_midpen + el_boldpen: erase = current pen bg (fg/attrs NOT carried)
        val e = eng(s = arrayOf("~001b[42mA~001b[35;46m~001b[K"))
        assertEquals(t("A", 1, -1, 0, 2, C16), tup(e, 0, 0))
        assertEquals(t(" ", 1, -1, 0, 6, C16), tup(e, 0, 1)); assertEquals(t(" ", 1, -1, 0, 6, C16), tup(e, 0, 79))
        val e2 = eng(s = arrayOf("~001b[1;44mA~001b[K"))
        assertEquals(t("A", 1, -1, 0, 4, C16, Cell.B_BOLD), tup(e2, 0, 0))
        assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e2, 0, 1)) // attrs cleared in the blank
    }

    @Test fun ed1CoversRowsAboveAndInclusiveCursor() { // ed1_top / ed1_mid
        val e = eng(s = arrayOf("abc~000d~000axyz~001b[H~001b[1J"))
        assertEquals(DEF, tup(e, 0, 0)); assertEquals(t("b", 1), tup(e, 0, 1)); assertEquals(t("c", 1), tup(e, 0, 2))
        assertEquals(t("x", 1), tup(e, 1, 0))
        val e2 = eng(s = arrayOf("abc~000d~000axyz~001b[H~001b[C~001b[1J"))
        assertEquals(DEF, tup(e2, 0, 1)); assertEquals(t("c", 1), tup(e2, 0, 2)); assertEquals(1, e2.buf.x)
    }

    @Test fun ed1FromRow1ClearsEverythingAbove() { // ed1_row1x2: every row above the cursor down to ROW 0 (origin irrelevant)
        val e = eng(s = arrayOf("abc~000d~000axyz~001b[2;3H~001b[1J"))
        assertEquals(DEF, tup(e, 0, 0)); assertEquals(DEF, tup(e, 1, 0)); assertEquals(DEF, tup(e, 1, 2)); assertEquals(1, e.buf.y)
    }

    @Test fun ed0BelowCursorOnly() { // ed0_row0x2: the cursor's [x,cols) + every row below
        val e = eng(s = arrayOf("abc~000d~000adefg~001b[1;3H~001b[0J"))
        assertEquals(t("a", 1), tup(e, 0, 0)); assertEquals(t("b", 1), tup(e, 0, 1)); assertEquals(DEF, tup(e, 0, 2))
        assertEquals(DEF, tup(e, 1, 0))
    }

    @Test fun ed0OnTopOfEd2KeepsPenFill() { // ed0_bg44: ED2 (materialize bg44 in every cell) then ED0 overwrites — whole screen bg44
        val e = eng(s = arrayOf("AB~000d~000aCD~001b[44m~001b[2J~001b[1;1H~001b[0J"))
        assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e, 0, 0)); assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e, 23, 79))
    }

    @Test fun ed2KeepsHistoryEd3Clears() { // ed2_history (y does not home!) + ed3_history (len→rows, screen kept)
        val e = eng(s = arrayOf("a~000d~000a".repeat(30), "~001b[2J"))
        assertEquals(31, e.buf.lines.size); assertEquals(7, e.buf.baseY); assertEquals(23, e.buf.y); assertEquals(0, e.buf.x)
        assertEquals(DEF, tup(e, 0, 0))
        val e2 = eng(s = arrayOf("a~000d~000a".repeat(30), "~001b[3J"))
        assertEquals(24, e2.buf.lines.size); assertEquals(0, e2.buf.baseY); assertEquals(23, e2.buf.y)
        assertEquals(t("a", 1), tup(e2, 0, 0)); assertEquals(t("a", 1), tup(e2, 22, 0)); assertEquals(DEF, tup(e2, 23, 0))
    }

    @Test fun ed1Ed2UnderOrigin() { // ed1_origin: CUP=scrollTop, rows-above = 0..3, current inclusive → r5,r6 remain; ed2_origin: cursor does not home
        val e = eng(s = arrayOf("~001b[5;8r~001b[?6h", "a~000d~000a".repeat(10), "~001b[H~001b[1J"))
        assertEquals(0, e.buf.x); assertEquals(4, e.buf.y)
        assertEquals(t("a", 1), tup(e, 5, 0)); assertEquals(t("a", 1), tup(e, 6, 0))
        assertEquals(DEF, tup(e, 4, 0)); assertEquals(DEF, tup(e, 3, 0))
        val e2 = eng(s = arrayOf("~001b[5;8r~001b[?6h", "a~000d~000a".repeat(10), "~001b[2J"))
        assertEquals(0, e2.buf.x); assertEquals(7, e2.buf.y); assertEquals(DEF, tup(e2, 7, 0))
    }

    // ================= scroll / history (region_scroll_history, su_*, sd_*, lflnm) =================

    @Test fun lineFeedFullRegionGrowsHistory() { // ed2_history prefix: 30 LF → 7 scroll = len 31
        val e = eng(s = arrayOf("a~000d~000a".repeat(30)))
        assertEquals(31, e.buf.lines.size); assertEquals(7, e.buf.baseY); assertEquals(23, e.buf.y)
    }

    @Test fun historyCapRecycles() {
        val e = eng(h = 4, s = arrayOf("a~000d~000a".repeat(30)))
        assertEquals(28, e.buf.lines.size) // rows + maxHistory
    }

    @Test fun partialRegionScrollNoHistory() { // region_scroll_history: region [4..7] = splice, len stays fixed
        val e = eng(s = arrayOf("~001b[5;8r", "z~000d~000a".repeat(10), "~001b[m"))
        assertEquals(24, e.buf.lines.size); assertEquals(0, e.buf.baseY)
        assertEquals(7, e.buf.y); assertEquals(0, e.buf.x)
        for (r in 0..6) assertEquals("r$r", t("z", 1), tup(e, r, 0))
        assertEquals(DEF, tup(e, 7, 0))
    }

    @Test fun suPartialRegion() { // su_partial: SU 1 in region [4..7] → z remains at r0..r5
        val e = eng(s = arrayOf("~001b[5;8r", "z~000d~000a".repeat(10), "~001b[S"))
        assertEquals(24, e.buf.lines.size); assertEquals(7, e.buf.y)
        for (r in 0..5) assertEquals("r$r", t("z", 1), tup(e, r, 0))
        assertEquals(DEF, tup(e, 6, 0))
    }

    @Test fun suFullScreenNeverTouchesHistory() { // su_full_history: CSI 10S = straight splice, len 24 + content gone
        val e = eng(s = arrayOf("a~000d~000a".repeat(10), "~001b[10S"))
        assertEquals(24, e.buf.lines.size); assertEquals(10, e.buf.y); assertEquals(0, e.buf.x)
        assertEquals(DEF, tup(e, 0, 0)); assertEquals(DEF, tup(e, 23, 0))
    }

    @Test fun sdShiftsContentDown() { // sd_full: CSI 2T → the original a at r0..r9 → r2..r11
        val e = eng(s = arrayOf("a~000d~000a".repeat(10), "~001b[2T"))
        assertEquals(10, e.buf.y); assertEquals(0, e.buf.x)
        assertEquals(t("a", 1), tup(e, 2, 0)); assertEquals(t("a", 1), tup(e, 11, 0))
        assertEquals(DEF, tup(e, 0, 0)); assertEquals(DEF, tup(e, 1, 0))
    }

    @Test fun suBlankUsesEraseProtoSdBlankIsDefault() { // su_blank_pen vs sd_blank_pen — the taxonomies differ!
        val e = eng(s = arrayOf("~001b[44ma~000d~000ab~001b[S"))
        assertEquals(t("b", 1, -1, 0, 4, C16), tup(e, 0, 0))
        assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e, 23, 0)) // SU blank = erase-proto (pen bg comes along)
        val e2 = eng(s = arrayOf("~001b[44ma~000d~000ab~001b[T"))
        assertEquals(DEF, tup(e2, 0, 0)) // SD blank = DEFAULT even with pen bg44
        assertEquals(t("a", 1, -1, 0, 4, C16), tup(e2, 1, 0))
    }

    @Test fun reverseIndexAtTopShiftsRegionDown() { // ri_top / ri_mid
        val e = eng(s = arrayOf("z~001bM"))
        assertEquals(t("z", 1), tup(e, 1, 0)); assertEquals(1, e.buf.x); assertEquals(0, e.buf.y)
        val e2 = eng(s = arrayOf("a~000d~000ab~001bM"))
        assertEquals(0, e2.buf.y); assertEquals(t("a", 1), tup(e2, 0, 0)); assertEquals(t("b", 1), tup(e2, 1, 0))
    }

    @Test fun crLfUnderOriginStillJustCr() { // lflnm: LNM = state-only — plain CR+LF
        val e = eng(s = arrayOf("~001b[?6h~000d~000aAB"))
        assertEquals(2, e.buf.x); assertEquals(1, e.buf.y); assertEquals(t("A", 1), tup(e, 1, 0))
    }

    // ================= IL/DL/ICH/DCH/ECH + wide orphan (probes) =================

    @Test fun ilDlBlankUseEraseProto() { // il_bg44 / dl_bg44
        val e = eng(s = arrayOf("~001b[44m~001b[4;1H~001b[1L"))
        assertEquals(0, e.buf.x); assertEquals(3, e.buf.y)
        assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e, 3, 0)); assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e, 3, 79))
        val e2 = eng(s = arrayOf("~001b[44m~001b[4;1H~001b[1M"))
        assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e2, 23, 0)); assertEquals(3, e2.buf.y)
    }

    @Test fun ilOutOfRegionOnlyRestricts() { // il_outofregion_pending: DECSTBM home (x→0) then guard = content untouched
        val e = eng(s = arrayOf("A".repeat(80), "~001b[5;20r~001b[3;1H~001b[L"))
        assertEquals(0, e.buf.x); assertEquals(2, e.buf.y)
        assertEquals(t("A", 1), tup(e, 0, 0)); assertEquals(t("A", 1), tup(e, 0, 79))
    }

    @Test fun dlOutOfRegionKeepsX() { // dl_outofregion_x: guard returns before x=0 → x stuck at 6
        val e = eng(s = arrayOf("a~000d~000ab~000d~000ac~001b[5;20r~001b[2;7H~001b[M"))
        assertEquals(6, e.buf.x); assertEquals(1, e.buf.y)
        assertEquals(t("a", 1), tup(e, 0, 0)); assertEquals(t("c", 1), tup(e, 2, 0))
    }

    @Test fun ilInRegionSetsXZero() { // il_inregion_x0
        val e = eng(s = arrayOf("~001b[1;1Habc~001b[2;1H~001b[3;8H~001b[L"))
        assertEquals(0, e.buf.x); assertEquals(2, e.buf.y)
        assertEquals(t("a", 1), tup(e, 0, 0)); assertEquals(DEF, tup(e, 2, 0))
    }

    @Test fun ichDchEchBlanks() { // ich_bg44 / dch_bg44 / ech_bg44 — blank = erase-proto (fg default!)
        val e = eng(s = arrayOf("~001b[44mABCD~001b[3;1H~001b[2@"))
        assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e, 2, 0)); assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e, 2, 1))
        assertEquals(DEF, tup(e, 2, 2)); assertEquals(0, e.buf.x); assertEquals(2, e.buf.y)
        val e2 = eng(s = arrayOf("~001b[44mABCD~001b[2;1H~001b[2P"))
        assertEquals(t("C", 1, -1, 0, 4, C16), tup(e2, 0, 2)) // r0 unaffected — DCH on r1 (empty) → tail 2 cells
        assertEquals(DEF, tup(e2, 1, 77)); assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e2, 1, 78)); assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e2, 1, 79))
        val e3 = eng(s = arrayOf("~001b[44mABCD~001b[2;1H~001b[2X"))
        assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e3, 1, 0)); assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e3, 1, 1))
        assertEquals(DEF, tup(e3, 1, 2))
    }

    @Test fun ichBetweenWideBreaksOrphanRight() { // wide_orphan_ich + ich_wide_split: [한,0,blank,한,...]
        val e = eng(s = arrayOf("한".repeat(10) + "~001b[1;3H~001b[1@"))
        assertEquals(t("한", 2), tup(e, 0, 0)); assertEquals(t(" ", 0), tup(e, 0, 1))
        assertEquals(t(" ", 1), tup(e, 0, 2)); assertEquals(t("한", 2), tup(e, 0, 3)); assertEquals(t(" ", 0), tup(e, 0, 4))
        assertEquals(2, e.buf.x)
    }

    @Test fun bsBetweenWideJustMoves() { // wide_orphan_bs: x=4 →+1+1-1 = 5, cells unchanged
        val e = eng(s = arrayOf("한한~001b[C~001b[C~001b[D"))
        assertEquals(5, e.buf.x)
        assertEquals(t(" ", 0), tup(e, 0, 3))
    }

    // ================= IRM (irm_blank = ICH blank / irm_print = shift-right) =================

    @Test fun ichInsertIsEraseProtoUnderIrm() { // irm_blank: IRM on + pen 31;42 — ICH = erase-proto (fg default, bg=pen)
        val e = eng(s = arrayOf("~001b[4h~001b[31;42mAB~001b[H~001b[2@"))
        assertEquals(t(" ", 1, -1, 0, 2, C16), tup(e, 0, 0)); assertEquals(t(" ", 1, -1, 0, 2, C16), tup(e, 0, 1))
        assertEquals(t("A", 1, 1, C16, 2, C16), tup(e, 0, 2)); assertEquals(0, e.buf.x)
    }

    @Test fun irmPrintInsertsPenCarriedCells() { // irm_print: XY + shift ABCD — every cell carries the pen (fg1 bg2)
        val e = eng(s = arrayOf("~001b[4h~001b[31;42mABCD~001b[1;1H~001b[4hXY"))
        assertEquals("XYABCD", (0 until 6).joinToString("") { cell(e, 0, it).ch })
        // every cell (XY typed itself + ABCD shifted) carries pen fg1/bg2; cell6 untouched = default
        "XYABCD".forEachIndexed { i, ch -> assertEquals(i.toString(), t(ch.toString(), 1, 1, C16, 2, C16), tup(e, 0, i)) }
        assertEquals(DEF, tup(e, 0, 6)); assertEquals(2, e.buf.x)
    }

    // ================= REP matrix (rep-clear.json — what clears / does not clear precedingJoinState) =================

    @Test fun repPlain() { // rep_plain
        val e = eng(s = arrayOf("Z~001b[2b"))
        assertEquals(3, e.buf.x)
        assertEquals(listOf(t("Z", 1), t("Z", 1), t("Z", 1)), (0..2).map { tup(e, 0, it) })
    }

    @Test fun repAfterCrNotCleared() { // rep_after_cr: CR does not clear state → overwrites B with ZZZ
        val e = eng(s = arrayOf("AB~000dZ~001b[2b"))
        assertEquals(3, e.buf.x); assertEquals("ZZZ", (0..2).joinToString("") { cell(e, 0, it).ch })
    }

    @Test fun repAfterCupNotCleared() { // rep_after_cup: CUP does not clear → ZZZZ + x=3
        val e = eng(s = arrayOf("ZZZZ~001b[1;1HZ~001b[2b"))
        assertEquals(3, e.buf.x)
        assertEquals(4, (0 until 80).count { cell(e, 0, it).ch == "Z" })
    }

    @Test fun repAfterBsNotCleared() { // rep_after_bs
        val e = eng(s = arrayOf("AB~0008~0008Z~001b[2b"))
        assertEquals(3, e.buf.x); assertEquals("ZZZ", (0..2).joinToString("") { cell(e, 0, it).ch })
    }

    @Test fun repClearedBySgrModeOsc() { // rep_after_sgr / rep_after_mode / rep_after_osc + rep_after_pen: x=1
        for (mid in listOf("~001b[31m", "~001b[?25h", "~001b]0;t~0007")) {
            val e = eng(s = arrayOf("Z", mid, "~001b[2b"))
            assertEquals(mid, 1, e.buf.x)
        }
    }

    @Test fun repRepeatsLastCellNotWholeRow() { // rep_after_print2: ZYY → ZYYYY (latest cell = Y)
        val e = eng(s = arrayOf("ZYY~001b[2b"))
        assertEquals(5, e.buf.x); assertEquals("ZYYYY", (0 until 5).joinToString("") { cell(e, 0, it).ch })
    }

    @Test fun repWide() { // rep_wide: the wide char's cell string → the whole block is reprinted (dummy follows)
        val e = eng(s = arrayOf("한~001b[2b"))
        assertEquals(6, e.buf.x)
        assertEquals(t("한", 2), tup(e, 0, 0)); assertEquals(t(" ", 0), tup(e, 0, 1))
        assertEquals(t("한", 2), tup(e, 0, 2)); assertEquals(t(" ", 0), tup(e, 0, 5))
    }

    @Test fun repSaraW1() { // rep_combining: U+0E32 (w1!) → A then three SARA_AA cells, x=4
        val e = eng(s = arrayOf("A~0e32~001b[2b"))
        assertEquals(4, e.buf.x)
        assertEquals(t("A", 1), tup(e, 0, 0))
        for (i in 1..3) assertEquals(i.toString(), t(SARA_AA, 1), tup(e, 0, i))
    }

    @Test fun repLoneCombiningNoop() { // rep_lone_combining: r = x-0 = x → empty cell = getString "" = no-op
        val e = eng(s = arrayOf("~0e48~001b[2b"))
        assertEquals(t(MAI_EK, 0), tup(e, 0, 0)); assertEquals(1, e.buf.x)
    }

    @Test fun repLwt() { // rep_lwt: Z CSI 3b → x=4
        val e = eng(s = arrayOf("Z~001b[3b"))
        assertEquals(4, e.buf.x)
        assertEquals(4, (0 until 4).count { cell(e, 0, it).ch == "Z" })
    }

    @Test fun repInheritsCurrentPen() { // rep_bg44: REP prints through the current print path → bg4 on all 6
        val e = eng(s = arrayOf("~001b[44m~001b[3;1Ha~001b[5b"))
        assertEquals(6, e.buf.x); assertEquals(2, e.buf.y)
        for (i in 0..5) assertEquals(i.toString(), t("a", 1, -1, 0, 4, C16), tup(e, 2, i))
    }

    // ================= reset taxonomy: real RIS vs DA1 (ESC c vs CSI c — do not confuse!) =================

    @Test fun risIsFullReset() { // ris_charset/ris_clear/ris_cursor (reset-scope) on a real RIS
        val e = eng(s = arrayOf("a~000d~000a".repeat(30), "~001b[5;8r~001b[3g~001b[?7l~001b[31;42m~001b(0", "~001bc", "q~000d~000aB".repeat(1)))
        assertEquals(24, e.buf.lines.size); assertEquals(0, e.buf.baseY)
        assertEquals(0, e.buf.scrollTop); assertEquals(23, e.buf.scrollBottom)
        assertFalse(e.modes.origin)
        assertEquals(t("q", 1), tup(e, 0, 0)) // charset = ascii, pen default
        assertEquals(9, eng(s = arrayOf("~001b[3g", "~001bc", "AB~0009C")).buf.x) // ris_tabstops2: tab stops restored
        val e3 = eng(s = arrayOf("~001b[?7l", "~001bc", "A".repeat(80)))
        assertEquals(80, e3.buf.x) // autowrap restored (ris_wrap)
    }

    @Test fun da1DoesNotResetAnything() { // ris_margins: `CSI c` = DA1 — margins/cursor kept, replies with an ID string
        val got = StringBuilder()
        val e = eng(s = arrayOf("~001b[10;20r"))
        e.onResponse = { got.append(it) }
        e.write(x("z~000d~000a".repeat(22) + "~001b[c").toByteArray(Charsets.UTF_8))
        assertEquals(9, e.buf.scrollTop); assertEquals(19, e.buf.scrollBottom)
        assertEquals(0, e.buf.x); assertEquals(19, e.buf.y)
        for (r in 0..18) assertEquals(r.toString(), t("z", 1), tup(e, r, 0))
        assertEquals(DEF, tup(e, 19, 0))
        assertEquals(ESC + "[?1;2c", got.toString())
    }

    @Test fun da1KeepsCharsetPenHistory() { // ris_history/ris_charset on DA1: resets nothing
        val e = eng(s = arrayOf("a~000d~000a".repeat(30), "~001b[c"))
        assertEquals(31, e.buf.lines.size); assertEquals(23, e.buf.y)
        val e2 = eng(s = arrayOf("~001b(0", "~001b[c", "q"))
        assertEquals("─", cell(e2, 0, 0).ch)
    }

    @Test fun decstrSoftResetScope() { // decstr_scope (reset-scope): no home, pen/margins/origin/saved-slot reset, history kept
        val e = eng(s = arrayOf("~001b[10;20r~001b[?6h~001b[1;41m~001b[!p", "A~000d~000a".repeat(1), "~000d~000a".repeat(19), "z"))
        assertEquals(1, e.buf.x); assertEquals(23, e.buf.y); assertEquals(6, e.buf.baseY); assertEquals(30, e.buf.lines.size)
        assertEquals(t("A", 1), tup(e, 3, 0)) // pen = default now (41m was cleared), r9 shifted up by 6 scrolls
        assertEquals(t("z", 1), tup(e, 23, 0))
        assertEquals(0, e.buf.scrollTop); assertEquals(23, e.buf.scrollBottom)
        assertFalse(e.modes.origin)
        assertTrue(e.modes.cursorVisible)
    }

    @Test fun decstrKeepsCursorHomeClearsOriginResetsCharsetKeepsHistory() { // decstr_home/origin/charset/history
        val e = eng(s = arrayOf("~001b[9;9H~001b[!p"))
        assertEquals(8, e.buf.x); assertEquals(8, e.buf.y)
        val e2 = eng(s = arrayOf("~001b[5;8r~001b[?6h~001b[!p~001b[H"))
        assertEquals(0, e2.buf.y) // origin off → CUP = whole screen
        val e3 = eng(s = arrayOf("~001b(0~001b[!pq"))
        assertEquals("q", cell(e3, 0, 0).ch) // charset reset
        val e4 = eng(s = arrayOf("a~000d~000a".repeat(30), "~001b[!p"))
        assertEquals(31, e4.buf.lines.size) // history kept (decstr_history)
    }

    @Test fun decscDecrcPenCharsetPosPending() { // decsc_pen/decsc_charset/decsc_pos/decrc_charset/decrc_pending
        val e = eng(s = arrayOf("~001b[1;41m~001b7~001b[m~001b8A"))
        assertEquals(t("A", 1, -1, 0, 1, C16, Cell.B_BOLD), tup(e, 0, 0)) // 41 = BG (do not confuse with 31)
        assertEquals("q", cell(eng(s = arrayOf("~001b(B~001b7~001b(A~001b8q")), 0, 0).ch) // decsc_charset: restore G0=B
        assertEquals("─", cell(eng(s = arrayOf("~001b(0~001b7~001b(A~001b8q")), 0, 0).ch) // decrc_charset: restore G0='0'
        val e2 = eng(s = arrayOf("~001b[5;7H~001b7~001b[1;1H~001b8"))
        assertEquals(6, e2.buf.x); assertEquals(4, e2.buf.y) // decsc_pos
        val e3 = eng(s = arrayOf("A".repeat(80), "~001b7~001b[9;9H~001b8"))
        assertEquals(79, e3.buf.x) // decrc_pending: raw savedX (80) then restrict → 79
    }

    // ================= DECALN (decaln_pen/dump/home/then_sgr) =================

    @Test fun decalnFillsWithPenAndHomes() { // decaln_pen: E + fg/bg words of the pen (attr bits live in the word — bundle)
        val e = eng(s = arrayOf("~001b[31;42m~001b#8"))
        assertEquals(t("E", 1, 1, C16, 2, C16), tup(e, 0, 0)); assertEquals(t("E", 1, 1, C16, 2, C16), tup(e, 23, 79))
        assertEquals(0, e.buf.x); assertEquals(0, e.buf.y)
    }

    @Test fun decalnDumpShape() { // decaln_dump / decaln_home: whole screen E default tuple, CUP 3;3
        val e = eng(s = arrayOf("~001b#8~001b[3;3H"))
        assertEquals(t("E", 1), tup(e, 0, 0)); assertEquals(t("E", 1), tup(e, 23, 79))
        assertEquals(2, e.buf.x); assertEquals(2, e.buf.y)
        val e2 = eng(s = arrayOf("~001b#8"))
        assertEquals(0, e2.buf.x); assertEquals(0, e2.buf.y)
    }

    @Test fun decalnThenEdUsesNewPen() { // decaln_then_sgr: ED2 (pen 41) overwrites the E across the whole screen
        val e = eng(s = arrayOf("~001b#8~001b[41m~001b[2J~001b[1;1H"))
        assertEquals(t(" ", 1, -1, 0, 1, C16), tup(e, 0, 0)); assertEquals(t(" ", 1, -1, 0, 1, C16), tup(e, 23, 0))
    }

    // ================= alt screen (altscreen_len + alt_* probes) =================

    @Test fun altScreenRoundTrip() { // altscreen_len: len=rows while in alt; exit = content+history+cursor restored (via DECRC slot)
        val e = eng(s = arrayOf("a~000d~000a".repeat(30), "~001b[?1049h"))
        assertTrue(e.altActive); assertEquals(24, e.buf.lines.size)
        e.write(x("hello~001b[?1049l").toByteArray(Charsets.UTF_8))
        assertFalse(e.altActive)
        assertEquals(31, e.buf.lines.size); assertEquals(7, e.buf.baseY)
        assertEquals(0, e.buf.x); assertEquals(23, e.buf.y)
        assertEquals(t("a", 1), tup(e, 0, 0)); assertEquals(t("a", 1), tup(e, 22, 0)); assertEquals(DEF, tup(e, 23, 0))
    }

    @Test fun altEnterCopiesCursorExitRestoresViaDecsc() { // alt_enter_cursor / alt_exit_restore
        val e = eng(s = arrayOf("~001b[10;20H~001b[?1049h"))
        assertEquals(19, e.buf.x); assertEquals(9, e.buf.y)
        val e2 = eng(s = arrayOf("~001b[3;5H~001b[?1049h~001b[9;9H~001b[?1049l"))
        assertEquals(4, e2.buf.x); assertEquals(2, e2.buf.y)
    }

    @Test fun altEntryBlanksUseEraseProto() { // alt_entry_pen: fillViewportRows(eraseAttrData)
        val e = eng(s = arrayOf("~001b[44m~001b[?1049h"))
        assertEquals(t(" ", 1, -1, 0, 4, C16), tup(e, 0, 0))
        val e2 = eng(s = arrayOf("abc~001b[?1049hxyz~001b[?1049l"))
        assertEquals("abc", (0 until 3).joinToString("") { cell(e2, 0, it).ch })
        assertEquals(3, e2.buf.x) // DECRC restores the cursor at entry
    }

    @Test fun risLeavesAltScreen() { // ris_alt: ESC c mid-alt = normal buffer, blank screen
        val e = eng(s = arrayOf("~001b[?1049hx~001bc~001b[H~001b[J"))
        assertFalse(e.altActive); assertEquals(24, e.buf.lines.size); assertEquals(0, e.buf.x); assertEquals(0, e.buf.y)
    }

    // ================= charset (dec-special.json + decsc probes) =================

    @Test fun decSpecialDesignationAndShift() { // G0='0' q→─ ; G1 UK '#'→£ (SO/SI switch GL) ; SI restores G0
        val e = eng(s = arrayOf("~001b(0q~001b)A~000e#~000fq"))
        assertEquals("─", cell(e, 0, 0).ch)
        assertEquals("£", cell(e, 0, 1).ch) // SO → GL=G1 (UK)
        assertEquals("─", cell(e, 0, 2).ch) // SI → GL=G0 (DEC)
    }

    @Test fun unknownDesignatorKeepsPrevious() {
        val e = eng(s = arrayOf("~001b(0~001b(9q"))
        assertEquals("─", cell(e, 0, 0).ch)
    }

    // ================= responses / OSC / misc =================

    @Test fun dsrResponses() { // dsr6_pending: pending 80 reports col 81
        val got = StringBuilder()
        val e = eng(); e.onResponse = { got.append(it) }
        e.write(x("~001b[5n" + "A".repeat(80) + "~001b[6n").toByteArray(Charsets.UTF_8))
        assertEquals(ESC + "[0n" + ESC + "[1;81R", got.toString())
    }

    @Test fun oscStashed() {
        val titles = ArrayList<Pair<Int, String>>()
        val e = eng(); e.onOsc = { c, t -> titles.add(c to t) }
        e.write(x("~001b]0;hello title~0007").toByteArray(Charsets.UTF_8))
        assertEquals(listOf(0 to "hello title"), titles)
    }

    @Test fun decscnmIsStateOnly() { // q5_scope: ?5 resets nothing / does not touch the grid
        val e = eng(s = arrayOf("abc~001b[?5h"))
        assertEquals("abc", (0 until 3).joinToString("") { cell(e, 0, it).ch })
        assertTrue(e.modes.screenModeReverse)
    }

    // ================= resize / dump shape =================

    @Test fun resizeKeepsScreenBottom() {
        val e = eng(s = arrayOf("abc"))
        e.resize(40, 10)
        assertEquals(40, e.cols); assertEquals(10, e.rows)
        // shrink = bottom-anchored (this case has no wrapped lines to reflow): top content rows fall off screen, cursor stays put
        e.write("Z".toByteArray(Charsets.UTF_8))
        assertEquals("Z", cell(e, 0, 3).ch) // cursor originally x=3 (after "abc") → Z at col 3
        e.write("A".repeat(40).toByteArray(Charsets.UTF_8))
        assertEquals(4, e.buf.x); assertEquals(1, e.buf.y) // wrap per the new cols
    }

    @Test fun gridDumpShapeAndCode0Normalization() {
        val e = eng(s = arrayOf("AB"))
        val j = GridDump.dump(e)
        assertTrue(j.startsWith("{\"cols\":80,\"rows\":24,\"x\":2,\"y\":0,\"baseY\":0,\"vp\":0,\"len\":24,\"cells\":["))
        assertTrue(j.contains("[\"A\",1,-1,0,-1,0,0,0,0,0,0,0,0,0,0]"))
        assertTrue(j.endsWith("]]}"))
        // row0 = 2 cells (tail-trim), row1 = [] (all-default)
        val row0 = j.substringAfter("cells\":[[").substringBefore("]]")
        assertEquals(2, row0.split("],[").size)
        // code0 blank (erase) → dump ch " " + bg pen — must never appear as ""
        val j2 = GridDump.dump(eng(s = arrayOf("~001b[44mAB~001b[H~001b[0J")))
        assertTrue(j2.startsWith("{\"cols\":80,\"rows\":24,\"x\":0,\"y\":0,\"baseY\":0,\"vp\":0,\"len\":24,\"cells\":[[[\" \",1,-1,0,4,16777216,0,0,0,0,0,0,0,0,0]"))
    }

    // ================= DECSCA / DECSED / DECSEL (gold-pinned: g11u12 — bundle selectProtected/replaceCells(protect)) =================
    // x placeholders: ~0022 = `"` (DECSCA's intermediate byte); DECSCA on = `CSI 1 " q`

    @Test fun decscaDecsed2KeepsProtected() {
        val e = eng(c = 6, s = arrayOf("AB~001b[1~0022qCD~001b[0~0022qEF~001b[?2J"))
        assertEquals(DEF, tup(e, 0, 0)); assertEquals(DEF, tup(e, 0, 1))
        assertEquals(t("C", 1, at = 512), tup(e, 0, 2)) // survivor still carries the protect bit (xterm: cell attrs kept)
        assertEquals(t("D", 1, at = 512), tup(e, 0, 3))
        assertEquals(DEF, tup(e, 0, 4)); assertEquals(DEF, tup(e, 0, 5))
    }

    @Test fun decsel1And2SkipProtected() {
        // DECSEL1 at col3: erase [0,3) — protected A,B remain; C (col2, unprotected) is erased
        val e = eng(c = 6, s = arrayOf("~001b[1~0022qAB~001b[0~0022qCD~001b[1;3H~001b[?1K"))
        // row = A(0) B(1) C(2) D(3); DECSEL1 = erase [0, x+1=3): A,B (protect) remain, C gone, D (out of range) kept
        assertEquals(t("A", 1, at = 512), tup(e, 0, 0)); assertEquals(t("B", 1, at = 512), tup(e, 0, 1))
        assertEquals(DEF, tup(e, 0, 2))
        assertEquals(t("D", 1), tup(e, 0, 3))
        e.write(x("~001b[?2K").toByteArray(Charsets.UTF_8)) // DECSEL2 whole row: A,B remain, D gone
        assertEquals(t("A", 1, at = 512), tup(e, 0, 0)); assertEquals(t("B", 1, at = 512), tup(e, 0, 1))
        assertEquals(DEF, tup(e, 0, 2)); assertEquals(DEF, tup(e, 0, 3))
    }

    @Test fun decsed0InclusiveSkipsProtected() {
        val e = eng(c = 4, s = arrayOf("~001b[1~0022qA~001b[0~0022qBC~001b[1;2H~001b[?0J"))
        assertEquals(t("A", 1, at = 512), tup(e, 0, 0)) // [1,4): B,C erased, A (col0) is outside the range
        assertEquals(DEF, tup(e, 0, 1)); assertEquals(DEF, tup(e, 0, 2))
    }

    @Test fun sgr0ClearsProtect() { // _processSGR0: bg=DEFAULT (clears bit29 of the whole word) — protect gone
        val e = eng(c = 4, s = arrayOf("~001b[1~0022q~001b[mA~001b[?2J"))
        assertEquals(DEF, tup(e, 0, 0))
    }

    @Test fun decscaBareParamClears() { // xterm Params: missing param → params[0]=0 → clear (selectProtected)
        val e = eng(c = 4, s = arrayOf("~001b[1~0022q~001b[~0022qA~001b[?2J"))
        assertEquals(DEF, tup(e, 0, 0))
    }

    @Test fun decscaOtherValueNoop() { // Ps=3 (ISO 7bit) — bundle: no set, no clear
        val e = eng(c = 4, s = arrayOf("~001b[1~0022q~001b[3~0022qA~001b[?2J"))
        assertEquals(t("A", 1, at = 512), tup(e, 0, 0))
    }
}
