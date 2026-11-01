package dev.aterminal.engine
// Portions derived from xterm.js (MIT), see THIRD_PARTY_NOTICES.md

import dev.aterminal.engine.term.Cell
import dev.aterminal.engine.term.Charsets
import dev.aterminal.engine.term.ModeState
import dev.aterminal.engine.term.Reflow
import dev.aterminal.engine.term.ResizeState
import dev.aterminal.engine.term.ScreenBuffer
import dev.aterminal.engine.term.TermLine
import dev.aterminal.engine.term.SgrCodec
import dev.aterminal.engine.term.Unicode
import dev.aterminal.engine.vt.VtConsumer
import dev.aterminal.engine.vt.VtParser

/**
 * The real VT engine (pure Kotlin, zero Android deps) — a VtConsumer that translates events → buffer
 *
 * SEMANTICS: every rule mirrors the oracle code @xterm/headless 5.5.0 (direct bundle grep) + recorded probes — principle: "never be smarter than the oracle":
 *  - no deferred-wrap flag: overflow check `x + w > cols` at print time; the cursor may rest at x==cols
 *    and DUMP exposes that real value (ed0_pending evidence)
 *  - wrap: writes blanks (ch=' ', w1, full pen) over [x, cols) of the old row (visible only for a wide char at the last col), then drops to the next line
 *  - erase blank tuple = (ch=' ', w1, fg=-1/0, bg=pen.bg/bgMode, attrs=0) — the bundle's _eraseAttrData
 *  - SD/CSI-T blank = pure DEFAULT (getBlankLine(DEFAULT_ATTR_DATA)) — unlike SU, which uses the erase attr
 *  - history: appended only by scrollUp over the full-screen region from lineFeed/wrap; SU/SD = region splice, history untouched (su_full)
 *  - ESC c = full reset (sync); CSI c = DA1 (no-op to state); DECSTR = pen/margins/origin/charset + saved-slot
 *    reset, cursor does NOT home, history kept, tabs untouched (body matches the bundle)
 *  - DECOM toggle → home (origin-aware); DECSCNM/DECSCUSR/LNM/SRM = state-only
 *  - DECSTBM: validate bottom>top then home via setCursor(0,0) (origin-aware)
 *  - cursor movement = literal copy of the bundle's _moveCursor/_setCursor — including origin-mode where y gets scrollTop added again
 *    (xterm.js's real behavior — mirrored); CUU/CUD clamp the distance from the region-relative position first
 *  - DECRC: x = raw savedX (no clamp — a pending 80 comes back as pending), y = max(savedAbs - ybase, 0) (no upper clamp)
 *  - IL/DL: always restrict() first (side effect even out-of-region, then return before x=0 — bundle guard)
 *  - IRM (4h): print = insertCells blank erase-proto; the wide char's right-half dummy carries the pen
 *  - alt (?47/?1047 = same case fall-through): entry = refill alt with erase-proto + copy cursor in;
 *    exit = copy cursor back + clear alt; 1049 = save/restore via the DECSC slot (the same one as ESC 7/CSI s/u) on both on and off
 *  - HTS is real (ESC H / C1 0x88); LCT/RCT: no-op when pending; REP = re-print the most recent cell string with the current pen
 *
 * caller must serialize calls (main thread); write() never throws on any input (FuzzNoThrowTest)
 */
class TerminalEngine(cols0: Int = 80, rows0: Int = 24, val historySize: Int = 9001) : VtConsumer {
    var cols = cols0; private set
    var rows = rows0; private set

    private var normalB = ScreenBuffer(cols0, rows0, historySize)
    private var altB = ScreenBuffer(cols0, rows0, 0)
    var buf = normalB; private set
    val altActive: Boolean get() = buf === altB

    val modes = ModeState()

    // ---- pen (curAttr) ----
    var attrs = 0; private set
    var penFg = Cell.DEFAULT_COLOR; private set
    var penFgMode = SgrCodec.MODE_DEFAULT; private set
    var penBg = Cell.DEFAULT_COLOR; private set
    var penBgMode = SgrCodec.MODE_DEFAULT; private set

    // ---- charset: G0..G3 designators + GL (SO=0x0E→G1, SI=0x0F→G0); 'B' = ascii ----
    val gsets = CharArray(4) { 'B' }
    var gl = 0; private set

    /** parser/oracle's precedingJoinState reduced to its width: base width of the last printed cluster (0 = none or lone combining) — CSI/ESC/OSC are what clear it (probe rep-clear) */
    var joinWidth = 0; private set

    // callbacks (app layer; replay/conformance do not consume — no effect on the dump)
    var onResponse: (String) -> Unit = {}
    var onOsc: (code: Int, text: String) -> Unit = { _, _ -> }
    var onBell: () -> Unit = {}

    private val parser = VtParser(this)
    private val saved = Array(2) { SavedCursor() } // 0=normal, 1=alt — DECSC per buffer (bundle: savedX lives on the buffer)

    /** DECSC slot: raw x (may be == cols = pending) + the ABSOLUTE line (savedY = ybase + y per the bundle) */
    private class SavedCursor {
        var x = 0; var absY = 0
        var attrs = 0; var fg = Cell.DEFAULT_COLOR; var fgMode = 0
        var bg = Cell.DEFAULT_COLOR; var bgMode = 0
        val gsets = CharArray(4) { 'B' }
        fun toDefaults(buf: ScreenBuffer) {
            x = 0; absY = buf.baseY // DECSTR: savedY = ybase (bundle) → DECRC lands at viewport row 0
            attrs = 0; fg = Cell.DEFAULT_COLOR; fgMode = 0; bg = Cell.DEFAULT_COLOR; bgMode = 0
            gsets.fill('B')
        }
    }

    private fun slot(): SavedCursor = saved[if (altActive) 1 else 0]

    fun write(bytes: ByteArray) = parser.feed(bytes)

    // ---------- proto cells ----------
    /**
     * the bundle's _eraseAttrData → getNullCell: code0 blank (ch="") with default fg + bg = pen bg (color+mode) + attrs cleared
     * ch="" (NULL_CELL_CODE=0), NOT " " — probe rep-clear: REP over an erase blank = no-op
     */
    private fun eraseProto(): Cell? =
        if (penBg == Cell.DEFAULT_COLOR && penBgMode == 0) null
        else Cell("", 1, Cell.DEFAULT_COLOR, 0, penBg, penBgMode, 0)

    /** full-pen blank (wrap-fill/orphan fix — setCellFromCodepoint(i, NULL_CELL_CODE, 1, f) in the bundle = code0) */
    private fun penFill(): Cell = Cell("", 1, penFg, penFgMode, penBg, penBgMode, attrs)

    private fun copyPenInto(c: Cell) {
        c.fg = penFg; c.fgMode = penFgMode; c.bg = penBg; c.bgMode = penBgMode; c.attrs = attrs
    }

    // ================= print =================
    override fun print(codepoint: Int) {
        var cp = codepoint
        if (cp < 0x7F) {
            // charset GL translation — bundle: table[char]; then s = s.charCodeAt(0) (keeps only the first char)
            val m = Charsets.TABLES[gsets[gl]]?.get(cp.toChar())
            if (m != null) cp = m[0].code
        }
        printCp(cp)
    }

    /** print a codepoint directly (already translated by print(); REP goes through print() instead, see rep()) */
    private fun printCp(cp: Int) {
        val b = buf
        val w = Unicode.width(cp)
        val str = cpToString(cp)
        // (1) combining join — the bundle's gate = shouldJoin property: w==0 && joinState (the last printed char) has width>0
        // (NOT "the left cell is a char" — after CSI/OSC cleared the state, a combining char becomes its own lone w0 cell)
        if (w == 0 && b.x > 0 && joinWidth != 0) {
            val line = b.line(b.y)
            val e = if (line.get(b.x - 1).width != 0) 1 else 2
            line.mutableAt(b.x - e).ch += str
            // joinWidth stays the base width (charProperties keeps oldWidth): stacked marks keep joining, REP re-prints the whole cluster
            // bundle: the post-loop runs after every print (including join) — a dummy under the cursor = code0 blank w1 + pen
            postBlankFix(line)
            return
        }
        // (2) overflow / wrap (bundle: x + r > o)
        if (b.x + w > cols) {
            if (modes.autowrap) {
                val t = b.x
                if (t < cols) b.line(b.y).fill(t, cols, penFill()) // wide split at the last col → fill with pen blanks
                b.x = 0
                if (b.y == b.scrollBottom) {
                    b.scrollUp(b.scrollTop, b.scrollBottom, 1, eraseProto(), fullScreenHistory = true)
                } else {
                    b.y++
                    if (b.y >= rows) b.y = rows - 1
                    b.line(b.y).isWrapped = true
                }
            } else {
                b.x = cols - 1
                if (w == 2) { // no-wrap + wide at the edge = skip the char (bundle: continue) — the post-loop still runs after continue
                    postBlankFix(b.line(b.y))
                    return
                }
            }
        }
        // (2b) w0 (combining/lone) at the pending edge: x+0 > cols is false → the bundle (JS array) writes past the edge with no visible effect
        //      — engine = no-op (a beyond-cols cell does not exist; no probe pins this — recorded as a documented divergence)
        if (b.x >= cols) return
        val line = b.line(b.y)
        // (3) orphan fix (pre-loop): the left cell is wide → its left half becomes a code0 pen blank (bundle: NULL_CELL_CODE)
        if (b.x > 0 && line.get(b.x - 1).width == 2) {
            val c = line.mutableAt(b.x - 1)
            c.ch = ""; c.width = 1; copyPenInto(c)
        }
        // (4) IRM: insert null-cell(f=curAttr) — bundle literal: getNullCell(this._curAttrData)
        //     = code0 w1 + the pen's full fg/bg WORDs (attr bits come along too, since they live in the fg/bg words)
        //     (differs from ICH: getNullCell(eraseAttrData) → fg word = default, attrs cleared — see eraseProto)
        if (modes.insertMode && w > 0) {
            line.insertCells(b.x, w, penFill())
            // the bundle's right-edge fix lives in TermLine.insertCells (same proto)
        }
        // (5) write the cell (lone combining at x=0 → w0 cell + x++, per the bundle)
        run {
            val c = line.mutableAt(b.x)
            c.ch = str; c.width = w; copyPenInto(c)
        }
        b.x++
        if (w == 2) {
            if (b.x < cols) { // right-half dummy: ch="", w=0 + pen (wide probes: the dummy carries the pen)
                val d = line.mutableAt(b.x)
                d.ch = ""; d.width = 0; copyPenInto(d)
            }
            b.x++
        }
        joinWidth = w
        // (6) post-loop: the dummy under the cursor (orphan pair) → code0 blank w1 with the pen (bundle: setCellFromCodepoint(x,0,1,f))
        postBlankFix(line)
    }

    /** bundle print() post-loop: `x<cols && getWidth(x)===0 && !hasContent(x)` → setCellFromCodepoint(x, 0, 1, f) */
    private fun postBlankFix(line: dev.aterminal.engine.term.TermLine) {
        val b = buf
        if (b.x < cols && line.get(b.x).width == 0 && line.get(b.x).ch.isEmpty()) {
            val c = line.mutableAt(b.x); c.ch = ""; c.width = 1; copyPenInto(c)
        }
    }

    // ================= C0 / C1 =================
    override fun control(bb: Int) {
        val b = buf
        when (bb) {
            0x07 -> onBell()
            0x08 -> { b.restrictCursor(modes.origin); if (b.x > 0) b.x-- } // BS: cancel pending first (bs_pending)
            0x09 -> if (b.x < cols) b.x = b.nextTabFrom(b.x) // HT pending = no-op; nextStop ≥ x+1, else cols-1
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x0D -> b.x = 0 // CR — LNM = state-only per the oracle (no LF)
            0x0E -> gl = 1 // SO
            0x0F -> gl = 0 // SI
            0x18, 0x1A -> {} // CAN/SUB — the parser already uses them to abort
            0x84 -> lineFeed()                       // IND
            0x85 -> { b.x = 0; lineFeed() }          // NEL = CR + LF
            0x88 -> if (b.x < cols) b.tabs[b.x] = true // HTS (C1 form of ESC H)
            0x8D -> reverseIndex()                   // RI
            else -> {}
        }
        joinWidth = 0 // parser case-3: every C0/C1 execute clears precedingJoinState (bundle)
    }

    /** the bundle's index(): _restrictCursor() first (cancels pending!), then y++ / scroll at bottom+1 */
    private fun lineFeed() {
        val b = buf
        b.restrictCursor(modes.origin)
        b.y++
        if (b.y == b.scrollBottom + 1) {
            b.y--
            b.scrollUp(b.scrollTop, b.scrollBottom, 1, eraseProto(), fullScreenHistory = true)
        } else if (b.y >= rows) {
            b.y = rows - 1
        } else {
            b.line(b.y).isWrapped = false // bundle: LF into a normal row = clear the target row's isWrapped
        }
    }

    /** reverseIndex(): restrict first; at scrollTop → scrollDown the whole region with erase-proto; else y-- then a closing restrict (bundle) */
    private fun reverseIndex() {
        val b = buf
        b.restrictCursor(modes.origin)
        if (b.y == b.scrollTop) b.scrollDown(b.scrollTop, b.scrollBottom, 1, eraseProto())
        else { b.y--; b.restrictCursor(modes.origin) }
    }

    // ================= ESC =================
    override fun esc(intermediates: IntArray, final: Int) {
        val b = buf
        joinWidth = 0 // ESC sequences clear precedingJoinState (probe rep-clear)
        if (intermediates.size == 1) {
            when (intermediates[0]) {
                '('.code -> designate(0, final)
                ')'.code -> designate(1, final)
                '*'.code -> designate(2, final)
                '+'.code -> designate(3, final)
                '#'.code -> if (final == '8'.code) decaln() // #3/#4/#5/#6: ignore
            }
            return // % etc: ignore (always UTF-8, per the oracle)
        }
        when (final) {
            '7'.code -> saveCursor()
            '8'.code -> restoreCursor()
            'D'.code -> lineFeed()              // IND
            'E'.code -> { b.x = 0; lineFeed() } // NEL
            'H'.code -> if (b.x < cols) b.tabs[b.x] = true // HTS — real (registerEscHandler final:"H")
            'M'.code -> reverseIndex()          // RI
            'c'.code -> fullReset()             // RIS
            else -> {}                          // =/> keypad, ST: ignore
        }
    }

    private fun designate(idx: Int, designator: Int) {
        val d = designator.toChar()
        if (Charsets.TABLES.containsKey(d)) gsets[idx] = d // unknown designator = designate fails, previous value kept (probe)
    }

    /**
     * screenAlignmentPattern (bundle literal): a fresh CellData — content='E' (w1), e.fg=curAttr.fg, e.bg=curAttr.bg
     * — the attr bits (bold/italic/dim/underline legacy/blink/invisible/strike/overline) live in the fg/bg WORDs
     * (isBold=1<<27&fg, isItalic=1<<26&bg …) → copyPenInto matches the bundle exactly (e's extended attrs = fresh:
     * a 4:x extended-style underline is not carried — no mixed probe, no fixture covers it)
     * + _setCursor(0,0) before and after the fill (origin-aware)
     */
    private fun decaln() {
        val b = buf
        setCursor(0, 0) // bundle: _setCursor(0,0) before the loop
        for (r in 0 until rows) {
            val line = b.line(r)
            for (x in 0 until cols) {
                val c = line.mutableAt(x)
                c.ch = "E"; c.width = 1; copyPenInto(c)
            }
            line.isWrapped = false // bundle: s.isWrapped=!1 for every row
        }
        setCursor(0, 0) // decaln_home evidence (origin-aware via setCursor)
    }

    /** ESC c — empirically pinned: sync FULL reset — screen+history+margins+tabs+pen+charset+cursor, normal buffer */
    fun fullReset() {
        parser.reset()
        normalB = ScreenBuffer(cols, rows, historySize)
        altB = ScreenBuffer(cols, rows, 0)
        buf = normalB
        modes.resetDefaults()
        resetPen()
        gsets.fill('B'); gl = 0
        joinWidth = 0
        saved[0].toDefaults(normalB); saved[1].toDefaults(altB)
    }

    private fun resetPen() {
        attrs = 0; penFg = Cell.DEFAULT_COLOR; penFgMode = 0; penBg = Cell.DEFAULT_COLOR; penBgMode = 0
        ulLegacy = false; ulStyle = 0
    }

    // underline = legacy bit (bare `4`) OR extended style (`4:style`) — xterm.js isUnderline getter
    // (`4m` then `4:0m` = style 0 but the legacy bit is still set → underline stays ON, per the oracle; attrs has a single bit, so keep two fields)
    private var ulLegacy = false
    private var ulStyle = 0

    // ================= CSI =================
    override fun csi(marker: Int, params: IntArray, subparams: BooleanArray, intermediates: IntArray, final: Int) {
        // absent param (-1 sentinel from the parser) ≡ 0 — xterm.js converts 0→1 per op via n1 (`params[i]||1`)
        val ps = IntArray(params.size) { if (params[it] < 0) 0 else params[it] }
        when {
            marker == '?'.code -> csiDec(ps, intermediates, final)
            marker != 0 -> {} // <,>,=: no handler → swallow (but case-7 still clears joinState)
            intermediates.isNotEmpty() -> when {
                intermediates[0] == ' '.code && final == 'q'.code -> {} // DECSCUSR: state-only (no renderer here)
                intermediates[0] == '"'.code && final == 'q'.code -> decsca(ps) // DECSCA (g11u12 gold-pinned)
                intermediates[0] == '!'.code && final == 'p'.code -> decstr()
                else -> {} // $p DECRQM, SP T etc: ignore (the response does not affect the dump)
            }
            else -> csiPlain(ps, subparams, final)
        }
        joinWidth = 0 // parser case-7: clear AFTER handler — REP reads state inside the handler (probe rep-clear + bundle)
    }

    private fun csiPlain(ps: IntArray, subs: BooleanArray, final: Int) {
        val b = buf
        when (final) {
            // ICH/DCH/ECH: the bundle's handler calls _restrictCursor() first (cancels pending)
            '@'.code -> { b.restrictCursor(modes.origin); b.line(b.y).insertCells(b.x, minOf(n1(ps, 0), cols), eraseProto()) } // ICH
            'P'.code -> { b.restrictCursor(modes.origin); b.line(b.y).deleteCells(b.x, minOf(n1(ps, 0), cols), eraseProto()) } // DCH
            'X'.code -> { b.restrictCursor(modes.origin); b.line(b.y).eraseCells(b.x, n1(ps, 0), eraseProto()) }               // ECH
            // IL/DL: always restrict() first; out-of-region = restrict side effect only (the guard leaves x unchanged — bundle)
            'L'.code -> {
                b.restrictCursor(modes.origin)
                if (b.y in b.scrollTop..b.scrollBottom) { b.insertLines(b.y, minOf(n1(ps, 0), rows), eraseProto()); b.x = 0 }
            }
            'M'.code -> {
                b.restrictCursor(modes.origin)
                if (b.y in b.scrollTop..b.scrollBottom) { b.deleteLines(b.y, minOf(n1(ps, 0), rows), eraseProto()); b.x = 0 }
            }
            'S'.code -> b.scrollUp(b.scrollTop, b.scrollBottom, minOf(n1(ps, 0), rows), eraseProto(), fullScreenHistory = false) // SU: the splice does not touch history (su_full)
            'T'.code -> b.scrollDown(b.scrollTop, b.scrollBottom, minOf(n1(ps, 0), rows), null) // SD blank = DEFAULT (bundle)
            // cursor family — literal from the bundle (see the class-header comment)
            'A'.code -> cursorUp(n1(ps, 0))
            'B'.code -> cursorDown(n1(ps, 0))
            'C'.code -> moveCursor(n1(ps, 0), 0)
            'D'.code -> moveCursor(-n1(ps, 0), 0)
            'E'.code -> { cursorDown(n1(ps, 0)); b.x = 0 } // CNL = cursorDown then x=0
            'F'.code -> { cursorUp(n1(ps, 0)); b.x = 0 }   // CPL = cursorUp then x=0
            'G'.code, '`'.code -> setCursor(n1(ps, 0) - 1, b.y) // HPA/charPosAbsolute — bundle: setCursor(p-1, y)
            'd'.code -> setCursor(b.x, n1(ps, 0) - 1)            // VPA linePosAbsolute
            'a'.code -> moveCursor(n1(ps, 0), 0)                 // HPR
            'e'.code -> moveCursor(0, n1(ps, 0))                 // VPR
            'H'.code, 'f'.code -> setCursor(if (ps.size >= 2) n1(ps, 1) - 1 else 0, n1(ps, 0) - 1) // CUP: single param → col 0
            // ED/EL: bare CSI J/K = mode 0 — PINNED BY PROBE el_midpen (`ESC[35;46m ESC[K` = erase bg6 for the whole tail)
            // (xterm.js Params: a missing param → 0, not -1; our -1 sentinel arises only in empty slots between ';' — parser test)
            'J'.code -> ed(if (ps.isEmpty()) 0 else p(ps, 0))
            'K'.code -> el(if (ps.isEmpty()) 0 else p(ps, 0))
            'r'.code -> decstbm(ps)
            // tabClear (bundle literal): `switch(e.params[0])` — a missing param (bare CSI g) = undefined = no-op; 5 = unsupported
            'g'.code -> if (ps.isNotEmpty()) when (p(ps, 0)) {
                0 -> if (b.x < cols) b.tabs[b.x] = false
                3 -> b.tabs.fill(false)
            }
            'h'.code -> setModeAnsi(ps, true)
            'l'.code -> setModeAnsi(ps, false)
            'm'.code -> if (ps.isEmpty()) resetPen() else sgr(ps, subs) // bare CSI m = SGR 0 (ANSI default; pinned by probe)
            'n'.code -> when (p(ps, 0)) { // DSR (raw x/y — a pending 80 reports as 81)
                5 -> onResponse("\u001b[0n")
                6 -> onResponse("\u001b[${b.y + 1};${b.x + 1}R")
            }
            'c'.code -> if (p(ps, 0) == 0) onResponse("\u001b[?1;2c") // DA1 — state unchanged (ris_* DA probes confirm)
            'b'.code -> rep(n1(ps, 0))
            's'.code -> saveCursor()
            'u'.code -> restoreCursor()
            'I'.code -> if (b.x < cols) repeat(n1(ps, 0)) { b.x = b.nextTabFrom(b.x) } // LCT
            'Z'.code -> if (b.x < cols) repeat(n1(ps, 0)) { b.x = b.prevTabFrom(b.x) } // RCT
            else -> {} // unknown final = swallow
        }
    }

    private fun p(ps: IntArray, i: Int): Int = ps.getOrElse(i) { 0 }
    private fun n1(ps: IntArray, i: Int): Int = p(ps, i).let { if (it == 0) 1 else it } // `params[i]||1`

    /** the bundle's _moveCursor: restrict() first, then setCursor(position + delta) */
    private fun moveCursor(dx: Int, dy: Int) {
        buf.restrictCursor(modes.origin)
        setCursor(buf.x + dx, buf.y + dy)
    }

    /** the bundle's _setCursor: origin → y = scrollTop + arg (then restrict); non-origin → direct */
    private fun setCursor(cx: Int, cy: Int) {
        val b = buf
        b.x = cx
        b.y = if (modes.origin) b.scrollTop + cy else cy
        b.restrictCursor(modes.origin)
    }

    /** the bundle's cursorUp: clamp the distance from the region-relative position first (cursor above the region = move the full n) */
    private fun cursorUp(n: Int) {
        val t = buf.y - buf.scrollTop
        moveCursor(0, -if (t >= 0) minOf(t, n) else n)
    }

    private fun cursorDown(n: Int) {
        val t = buf.scrollBottom - buf.y
        moveCursor(0, if (t >= 0) minOf(t, n) else n)
    }

    // ---- ED (probes: inclusive at the cursor, the cursor does not move, history kept; ED1 under origin mode still starts at row 0, not scrollTop; ED3 = clears history only) ----
    // protect=true = DECSED (CSI ? Ps J — bundle: eraseInDisplay(e,!0) enters the same handler, every op skips protected cells)
    private fun ed(mode: Int, protect: Boolean = false) {
        val b = buf
        val proto = eraseProto()
        when (mode) {
            0 -> {
                if (protect) b.line(b.y).replaceProtected(b.x, cols, proto) else b.line(b.y).replaceCells(b.x, cols, proto)
                for (r in b.y + 1 until rows) {
                    if (protect) b.line(r).replaceProtected(0, cols, proto) else b.line(r).fill(0, cols, proto)
                    b.line(r).isWrapped = false
                }
            }
            1 -> {
                // bundle: `for(i=y;i--;) this._resetBufferLine(i,t)` — every row above the cursor down to ROW 0 (origin does NOT start at scrollTop — ed1_origin probe)
                for (r in 0 until b.y) {
                    if (protect) b.line(r).replaceProtected(0, cols, proto) else b.line(r).fill(0, cols, proto)
                    b.line(r).isWrapped = false
                }
                if (protect) b.line(b.y).replaceProtected(0, b.x + 1, proto) else b.line(b.y).replaceCells(0, b.x + 1, proto)
                if (b.x + 1 >= cols && b.y + 1 < rows) b.line(b.y + 1).isWrapped = false // bundle literal
            }
            2 -> for (r in 0 until rows) {
                if (protect) b.line(r).replaceProtected(0, cols, proto) else b.line(r).fill(0, cols, proto)
                b.line(r).isWrapped = false
            }
            3 -> while (b.lines.size > rows) b.lines.removeAt(0) // ed3_history: len→rows, baseY→0 (protect has no effect — history splice)
        }
    }

    private fun el(mode: Int, protect: Boolean = false) {
        val b = buf
        val line = b.line(b.y)
        val proto = eraseProto()
        when (mode) {
            0 -> if (protect) line.replaceProtected(b.x, cols, proto) else line.replaceCells(b.x, cols, proto)
            1 -> if (protect) line.replaceProtected(0, b.x + 1, proto) else line.replaceCells(0, b.x + 1, proto)
            2 -> if (protect) line.replaceProtected(0, cols, proto) else line.fill(0, cols, proto)
        }
    }

    /** DECSTBM (setScrollRegion): validate bottom>top then home via setCursor (origin-aware) */
    private fun decstbm(ps: IntArray) {
        val b = buf
        val top = n1(ps, 0) // params[0]||1
        val bottom = if (ps.size < 2 || p(ps, 1) == 0 || p(ps, 1) > rows) rows else p(ps, 1)
        if (bottom > top) {
            b.scrollTop = top - 1
            b.scrollBottom = bottom - 1
            setCursor(0, 0)
        }
    }

    /** DECSCA (CSI Ps " q) — bundle selectProtected: Ps==1 → set; Ps==0|2 → clear; anything else = no-op
     *  a missing param = 0 (xterm Params params[0]→0) → clear; SGR0/RIS/DECSTR clear the pen = off, per the bundle (bg=0 across the whole word) */
    private fun decsca(ps: IntArray) {
        val t = if (ps.isEmpty()) 0 else p(ps, 0)
        if (t == 1) attrs = attrs or Cell.B_PROTECT
        else if (t == 0 || t == 2) attrs = attrs and Cell.B_PROTECT.inv()
    }

    /** DECSTR — matches the bundle: cursor show, margins full, pen default, charset reset, saved slot = (0, ybase, default),
     *  origin off; does NOT home, does NOT clear history, does NOT touch tabs */
    private fun decstr() {
        val b = buf
        modes.cursorVisible = true
        b.scrollTop = 0; b.scrollBottom = rows - 1
        resetPen()
        gsets.fill('B'); gl = 0
        slot().toDefaults(b)
        modes.origin = false
    }

    // ---- DECSC / DECRC (slot per buffer; absY per the bundle) ----
    private fun saveCursor() {
        val b = buf
        val s = slot()
        s.x = b.x; s.absY = b.baseY + b.y
        s.attrs = attrs; s.fg = penFg; s.fgMode = penFgMode; s.bg = penBg; s.bgMode = penBgMode
        for (i in 0 until 4) s.gsets[i] = gsets[i]
    }

    private fun restoreCursor() {
        val b = buf
        val s = slot()
        b.x = s.x                                  // raw savedX (bundle)
        b.y = maxOf(s.absY - b.baseY, 0)           // no upper clamp on the line — bundle
        attrs = s.attrs; penFg = s.fg; penFgMode = s.fgMode; penBg = s.bg; penBgMode = s.bgMode
        for (i in 0 until 4) gsets[i] = s.gsets[i]
        b.restrictCursor(modes.origin)             // decrc_pending evidence: pending 80 → 79 (a restrict runs at the end of restore)
    }

    // ---------- modes ----------
    private fun setModeAnsi(ps: IntArray, on: Boolean) {
        for (i in 0 until ps.size) when (p(ps, i)) {
            2 -> modes.sendReceive = on  // SRM state-only
            4 -> modes.insertMode = on   // IRM — really affects print (irm_print/irm_blank)
            20 -> modes.lnm = on         // LNM state-only per the oracle
        }
    }

    private fun csiDec(ps: IntArray, inter: IntArray, final: Int) {
        if (inter.isNotEmpty()) return // ?$p DECRQM etc → ignore (the response does not affect the dump)
        // CSI ? J / CSI ? K = DECSED/DECSEL → the same handler as ED/EL but protect=true (bundle: eraseInDisplay(e,!0))
        // — skips cells set by DECSCA (g11u12 gold); bare = mode 0 per probe el_midpen
        if (final == 'J'.code) { ed(if (ps.isEmpty()) 0 else p(ps, 0), true); return }
        if (final == 'K'.code) { el(if (ps.isEmpty()) 0 else p(ps, 0), true); return }
        val on = final == 'h'.code
        if (final != 'h'.code && final != 'l'.code) return
        for (i in 0 until ps.size) when (p(ps, i)) {
            1 -> modes.appCursorKeys = on
            3 -> modes.altCols = on             // state-only — geometry does not change
            5 -> modes.screenModeReverse = on   // DECSCNM state-only (q5_scope)
            6 -> { modes.origin = on; setCursor(0, 0) } // toggle = home (origin-aware) — NOT a reset (probes)
            7 -> modes.autowrap = on
            12 -> modes.cursorBlink = on
            25 -> modes.cursorVisible = on
            45 -> modes.reverseWraparound = on  // state-only — no fixtures
            47 -> activateAlt(on)               // bundle: case 47/1047 share one fall-through
            1047 -> activateAlt(on)
            1048 -> if (on) saveCursor() else restoreCursor() // unconditional both ways (bundle)
            1049 -> { if (on) saveCursor(); activateAlt(on); if (!on) restoreCursor() } // same slot as ESC 7; restores every time
            2004 -> modes.bracketedPaste = on
            1000 -> modes.mouseClick = on
            1002 -> modes.mouseDrag = on
            1003 -> modes.mouseAny = on
            1006 -> modes.mouseSgr = on
            1015 -> modes.mouseUrxvt = on
            2026 -> {} // sync — state-only
            else -> {}
        }
    }

    /** the bundle's activateAltBuffer/activateNormalBuffer (see the class-header comment) */
    private fun activateAlt(on: Boolean) {
        if (on) {
            if (!altActive) {
                altB.refillBlank(eraseProto()) // fillViewportRows(eraseAttrData)
                altB.x = buf.x; altB.y = buf.y // copy the cursor into alt (bundle)
                buf = altB
            }
        } else {
            if (altActive) {
                normalB.x = altB.x; normalB.y = altB.y // copy the cursor back (bundle)
                altB.clearAll() // exit = alt.clear() — DEFAULT blank
                buf = normalB
            }
        }
    }

    // ---------- REP ----------
    /**
     * the bundle's repeatPrecedingCharacter: t=precedingJoinState (empty=return), r = x - width(t),
     * n = line.getString(r) — the STRING of the single cell at r (combined string included; empty/code0 cell = "")
     * → re-print the whole n-chunk i times via this.print (charset translation applies — not printCp!)
     */
    private fun rep(n: Int) {
        if (joinWidth == 0) return
        val b = buf
        val r = b.x - joinWidth
        if (r < 0 || r >= cols) return
        val cellStr = b.line(b.y).raw(r)?.ch ?: return // an unwritten cell = null = "" → no-op (oracle getString)
        if (cellStr.isEmpty()) return
        repeat(n) {
            var i = 0
            while (i < cellStr.length) {
                val c = cellStr.codePointAt(i)
                print(c) // bundle: this.print(a,0,h) — GL translation uses the current table
                i += Character.charCount(c)
            }
        }
    }

    // ---------- SGR ----------
    // subparams (after ':') always belong to the preceding code — probe underline_color: `4:31` = underline style 31,
    // fg stays default (treating 31 as a code = smeared color, wrong vs the oracle); `4:0` = style 0 = underline off
    private fun sgr(ps: IntArray, subs: BooleanArray) {
        var i = 0
        while (i < ps.size) {
            val v = p(ps, i)
            val hasSub = i + 1 < ps.size && subs[i + 1]
            when (v) {
                0 -> resetPen()
                1 -> attrs = attrs or Cell.B_BOLD
                2 -> attrs = attrs or Cell.B_DIM
                3 -> attrs = attrs or Cell.B_ITALIC
                // underline: bare `4` = legacy; `4:style` = extended (style 0 = off). isUnderline = legacy OR style!=0
                4 -> if (hasSub) { ulStyle = p(ps, i + 1); attrs = if (ulStyle != 0 || ulLegacy) attrs or Cell.B_UNDERLINE else attrs and Cell.B_UNDERLINE.inv() }
                else { ulLegacy = true; attrs = attrs or Cell.B_UNDERLINE }
                5, 6 -> attrs = attrs or Cell.B_BLINK
                7 -> attrs = attrs or Cell.B_INVERSE
                8 -> attrs = attrs or Cell.B_INVISIBLE
                9 -> attrs = attrs or Cell.B_STRIKE
                22 -> attrs = attrs and (Cell.B_BOLD or Cell.B_DIM).inv()
                23 -> attrs = attrs and Cell.B_ITALIC.inv()
                24 -> attrs = attrs and Cell.B_UNDERLINE.inv()
                25 -> attrs = attrs and Cell.B_BLINK.inv()
                27 -> attrs = attrs and Cell.B_INVERSE.inv()
                28 -> attrs = attrs and Cell.B_INVISIBLE.inv()
                29 -> attrs = attrs and Cell.B_STRIKE.inv()
                53 -> attrs = attrs or Cell.B_OVERLINE
                55 -> attrs = attrs and Cell.B_OVERLINE.inv()
                in 30..37 -> { penFg = v - 30; penFgMode = SgrCodec.MODE_C16 }
                in 90..97 -> { penFg = v - 90 + 8; penFgMode = SgrCodec.MODE_C16 }
                in 40..47 -> { penBg = v - 40; penBgMode = SgrCodec.MODE_C16 }
                in 100..107 -> { penBg = v - 100 + 8; penBgMode = SgrCodec.MODE_C16 }
                38, 48, 58 -> {
                    val k = p(ps, i + 1)
                    if (k == 5 && ps.size >= i + 3) {
                        if (v != 58) setColor(v, p(ps, i + 2), SgrCodec.MODE_C256) // n<16 does not downgrade (probe)
                        i += 2
                    } else if (k == 2 && ps.size >= i + 4) {
                        if (v != 58) setColor(v, SgrCodec.rgb(p(ps, i + 2), p(ps, i + 3), p(ps, i + 4)), SgrCodec.MODE_RGB)
                        i += 4
                    } // other type: consume a single code, then keep parsing the next param (mirror the oracle)
                }
                39 -> { penFg = Cell.DEFAULT_COLOR; penFgMode = 0 }
                49 -> { penBg = Cell.DEFAULT_COLOR; penBgMode = 0 }
                59 -> {} // underline colour — the dump has no field for it (58 is consumed in the branch above)
                else -> {} // 21 (double-underline), 10-19 (fonts) etc.: ignore per the oracle
            }
            // consume this code's subparams (they belong to the preceding code, not the new one) — xterm.js Params sub-array
            while (i + 1 < ps.size && subs[i + 1]) i++
            i++
        }
    }

    private fun setColor(v: Int, color: Int, mode: Int) {
        when (v) {
            38 -> { penFg = color; penFgMode = mode }
            48 -> { penBg = color; penBgMode = mode }
        }
    }

    // ---------- OSC ----------
    override fun osc(data: ByteArray) {
        joinWidth = 0 // OSC clears the join state (probe rep_after_osc)
        val text = String(data, java.nio.charset.StandardCharsets.UTF_8)
        val semi = text.indexOf(';')
        val code = (if (semi >= 0) text.substring(0, semi) else text).toIntOrNull() ?: return
        onOsc(code, if (semi >= 0) text.substring(semi + 1) else "") // onward stash (title/4/10/11) — no effect on the dump
    }

    // DCS/SOS/PM/APC: consumed then discarded (no fixture has any dump effect) — but unhook/end = parser case-14 → clears joinState
    override fun stringAbsorb(kind: Int, data: ByteArray) { joinWidth = 0 }

    // ---------- resize (port of Buffer.resize + BufferSet.resize — reflow gated by _isReflowEnabled ≡ has scrollback) ----------
    // oracle: Terminal.resize → BufferSet.resize (normal+alt + setupTabStops(newCols)) — ours = a fresh ScreenBuffer (tabs %8 fresh)
    // bug-compatible ≡ oracle (deliberate): a cursor in a wrapped line = skips the group → shrink data loss exactly as it really happens
    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        val wasAlt = altActive
        cols = newCols; rows = newRows
        normalB = resizeBuffer(normalB, historySize, reflow = true, slot = saved[0])
        altB = resizeBuffer(altB, 0, reflow = false, slot = saved[1]) // alt: _hasScrollback=false → no reflow (probe 09)
        buf = if (wasAlt) altB else normalB
    }

    /**
     * Buffer.resize literal — every step in source order (xterm.js src/common/buffer/Buffer.ts):
     * grow-cols → rows loop → cap trim → cursor clamp → margins → reflow → shrink-cols
     * the engine differs from the oracle at one point (observationally equivalent): ybase is normally derived = lines.size - rows;
     * here we use an explicit local and self-assert via the bottom-pinned invariant
     */
    private fun resizeBuffer(old: ScreenBuffer, hist: Int, reflow: Boolean, slot: SavedCursor): ScreenBuffer {
        val newCols = cols; val newRows = rows
        val lines = ArrayList<TermLine>(old.lines.size)
        for (i in old.lines.indices) lines.add(old.lines[i]) // refs — never recycle
        var ybase = old.lines.size - old.rows
        var x = old.x; var y = old.y
        var savedY = slot.absY; var savedX = slot.x

        // (0) Increase max length BEFORE adjustments
        val cap0 = old.rows + hist // the original lines.maxLength
        var cap = cap0
        val newCap = newRows + hist // _getCorrectBufferLength(newRows) (MAX_BUFFER_SIZE irrelevant)
        if (newCap > cap) cap = newCap

        // (1) cols grow (shrink happens after reflow — literal)
        if (old.cols < newCols) for (i in lines.indices) lines[i] = lines[i].copyToWidth(newCols)

        // (2) rows loop in both directions (windowsMode/windowsPty branch = false per headless defaults)
        var addToY = 0
        if (old.rows < newRows) {
            var yy = old.rows
            while (yy < newRows) {
                if (lines.size < newRows + ybase) {
                    if (ybase > 0 && lines.size <= ybase + y + addToY + 1) {
                        ybase--; addToY++ // room to scroll up (the viewport shifts down with the content)
                    } else {
                        lines.add(TermLine(newCols)) // fresh blank at the bottom of the screen
                    }
                }
                yy++
            }
        } else {
            var yy = old.rows
            while (yy > newRows) {
                if (lines.size > newRows + ybase) {
                    if (lines.size > ybase + y + 1) lines.removeAt(lines.size - 1) // blank below the cursor → trim it
                    else ybase++ // the cursor's own line → the viewport shifts down
                }
                yy--
            }
        }

        // (3) Reduce max length AFTER adjustments — trim the head + adjust ybase/savedY (literal)
        if (newCap < cap) {
            val amountToTrim = lines.size - newCap
            if (amountToTrim > 0) {
                repeat(amountToTrim) { lines.removeAt(0) }
                ybase = maxOf(ybase - amountToTrim, 0)
                savedY = maxOf(savedY - amountToTrim, 0)
            }
            cap = newCap
        }

        // (4) cursor clamp (pending x==cols dies here — probe 05) + addToY + savedX
        x = minOf(x, newCols - 1)
        y = minOf(y, newRows - 1)
        if (addToY != 0) y += addToY
        savedX = minOf(savedX, newCols - 1)

        // (5) margins: scrollTop=0, scrollBottom=newRows-1 (DECSTBM reset — no restrictCursor in the oracle)
        val st = ResizeState(ybase, y, savedY)

        // (6) reflow (only when cols change) + (7) shrink-cols at the end — reflow-enabled only
        if (reflow && old.cols != newCols) {
            if (newCols > old.cols) Reflow.reflowLarger(lines, old.cols, newCols, newRows, st)
            else Reflow.reflowSmaller(lines, old.cols, newCols, newRows, cap, st)
            if (old.cols > newCols) for (i in lines.indices) lines[i] = lines[i].copyToWidth(newCols)
        } else if (old.cols > newCols) {
            // alt/no-reflow: the oracle does not trim (cells beyond cols = invisible in the dump) — the engine requires line.cols == buf.cols
            for (i in lines.indices) lines[i] = lines[i].copyToWidth(newCols)
        }

        // rebuild ScreenBuffer (tabs = setupTabStops(newCols) ≡ %8 fresh per BufferSet.resize)
        val nb = ScreenBuffer(newCols, newRows, hist)
        nb.lines.clear()
        // materialize the slot the oracle leaves undefined (viewport beyond content — rare case, dump ≡ empty rows)
        while (lines.size < st.ybase + newRows) lines.add(TermLine(newCols))
        for (l in lines) nb.lines.add(l)
        nb.x = x // reflow does not touch x (nor does the oracle — clamped once at step (4))
        nb.y = st.y
        nb.scrollTop = 0
        nb.scrollBottom = newRows - 1
        slot.x = savedX
        slot.absY = st.savedY // reflow adjusts savedY per group (min/+linesToAdd) — must read it back from st, not the pre-reflow local
        return nb
    }

    companion object {
        fun cpToString(cp: Int): String =
            if (cp in 1..0xFFFF) String(charArrayOf(cp.toChar()))
            else if (cp <= 0) "" // NUL never enters the print path (the parser guards it) — defensive
            else String(Character.toChars(cp))
    }
}
