package dev.aterminal.engine.vt

// VT byte-stream state machine (pure) — cuts bytes into events via VtConsumer
// Semantics referenced from @xterm/headless 5.5.0 (oracle) — every rule the fixtures check:
//  - ESC mid-sequence = restart (cancels the in-flight sequence) — not append
//  - CAN/SUB (0x18/0x1A) = abort sequence, a C0 no-op on the engine side
//  - DEL (0x7F) = ignored in every state except inside a string (OSC/DCS absorb)
//  - param '-' (ABSENT) differs from 0 — the engine must apply defaults per op (xterm.js param(i, first, def))
//  - ':' = SUBPARAM of the preceding param (xterm.js Params sub-arrays — not ';'!) — underline_color probe:
//    `CSI 4:31m` = underline + subparam 31 → fg stays default; flattening to [4,31] = red fg = oracle violation
//    flat array + subparams[i]=true (flag that param i is a sub of param i-1) — the engine consumes that code's subparams
//  - marker ?<> = accepted only at the first CSI position (after a param → IGNORE)
//  - C1 (0x80-0x9F) accepted as equivalent to the 7-bit form (0x9B=CSI, 0x9D=OSC, 0x90=DCS, 0x9C=ST)
interface VtConsumer {
    fun print(codepoint: Int)
    fun control(b: Int)                                  // lone C0/C1 (incl. SO/SI/CAN/SUB)
    fun esc(intermediates: IntArray, final: Int)
    /** subparams[i] = true → params[i] is a subparam of params[i-1] (came after ':', not ';') */
    fun csi(marker: Int, params: IntArray, subparams: BooleanArray, intermediates: IntArray, final: Int) // marker: 0 | '?', '>', '<', '='
    fun osc(data: ByteArray)
    fun stringAbsorb(kind: Int, data: ByteArray)         // DCS 'P' / SOS 'X' / PM '^' / APC '_' — MVP ignores but must consume correctly
}

class VtParser(private val out: VtConsumer) {
    private val utf8 = Utf8()

    private enum class S { GROUND, ESCAPE, ESCAPE_INTER, CSI_ENTRY, CSI_PARAM, CSI_INTER, CSI_IGNORE, OSC, STRING }

    private var state = S.GROUND
    private var marker = 0
    private var inter = IntArray(4); private var interLen = 0
    private var params = IntArray(24); private var nParams = 0
    private var subs = BooleanArray(24)   // parallel to params: true = that entry is a subparam (after ':')
    private var cur = -1          // param being read (-1 = no digit yet)
    private var curSub = false    // flag of the current param (set by ':', cleared by ';')
    private var stringKind = 0
    private val sbuf = ArrayList<Byte>(64)

    fun reset() { state = S.GROUND; utf8.reset(); sbuf.clear() }

    fun feed(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off) {
        for (i in off until off + len) step(bytes[i].toInt() and 0xFF)
    }

    private fun pushParam(v: Int, sub: Boolean) {
        if (nParams >= MAX_PARAMS) return // over cap = drop the excess (xterm.js ~32767 — fixtures never reach it)
        if (nParams == params.size) { params = params.copyOf(params.size * 2); subs = subs.copyOf(params.size) }
        params[nParams] = v; subs[nParams] = sub; nParams++
    }

    private fun finishCur() { pushParam(if (cur < 0) -1 else cur, curSub); cur = -1 }

    private fun pushInter(b: Int) {
        if (interLen < inter.size) inter[interLen++] = b // more than 4 intermediates = insane, drop (xterm.js truncates too)
    }

    private fun dispatchCsi(final: Int) {
        val empty = cur < 0 && nParams == 0
        if (!empty) finishCur()
        val ps = if (empty) IntArray(0) else params.copyOf(nParams)
        val ss = if (empty) BooleanArray(0) else subs.copyOf(nParams)
        val isx = inter.copyOf(interLen)
        out.csi(marker, ps, ss, isx, final)
        state = S.GROUND; marker = 0; interLen = 0; nParams = 0; cur = -1; curSub = false
    }

    private fun dispatchEsc(final: Int) {
        out.esc(inter.copyOf(interLen), final)
        state = S.GROUND; interLen = 0
    }

    private fun endString(emit: Boolean) {
        if (emit) {
            val data = ByteArray(sbuf.size) { sbuf[it] }
            if (stringKind == K_OSC) out.osc(data) else out.stringAbsorb(stringKind, data)
        }
        sbuf.clear(); state = S.GROUND
    }

    /** ESC found mid-sequence = cancel it and start a fresh ESC state (xterm.js parser rules) */
    private fun enterEscape() { state = S.ESCAPE; interLen = 0; nParams = 0; cur = -1; curSub = false; marker = 0; utf8.reset() }

    /** C0/C1 interrupting = discard any pending utf8 partial (xterm.js: sequence broken, next byte starts fresh) */
    private fun exec(b: Int) { utf8.reset(); out.control(b) }

    private fun step(b: Int) {
        when (state) {
            S.GROUND -> when {
                // incomplete UTF-8 (chunk cut mid-sequence): every byte ≥0x80, incl. C1-range continuation bytes, must enter the decoder
                utf8.inProgress && b >= 0x80 -> utf8.feed(b) { out.print(it) }
                utf8.inProgress -> { utf8.reset(); step(b) } // C0/ESC cutting a partial = drop the sequence (xterm: reset utf8 then reprocess)
                b == 0x1b -> enterEscape() // ESC must be checked before b<0x20 (otherwise it becomes control 0x1b!)
                b == 0x00 -> {}            // NUL ignored (xterm.js)
                b < 0x20 -> exec(b)
                b == 0x7f -> {} // DEL ignore
                b < 0x80 -> out.print(b)                  // incl. 0x20 space (print, not control)
                b == 0x90 -> { stringKind = K_DCS.code; state = S.STRING }
                b == 0x9b -> enterCsi()
                b == 0x9d -> { stringKind = K_OSC; state = S.OSC }
                b in 0x98..0x9a -> { stringKind = if (b == 0x98) K_SOS.code else if (b == 0x99) K_PM.code else K_APC.code; state = S.STRING }
                b in 0x9c..0x9f -> {}                     // ST/SPDC while idle = no-op
                b < 0xA0 -> exec(b)                       // lone C1 (IND/NEL/HTS/RI/…)
                else -> utf8.feed(b) { out.print(it) }    // UTF-8 (0xA0–0xFF): decode to a codepoint, then print
            }
            S.ESCAPE, S.ESCAPE_INTER -> when {
                b == 0x1b -> enterEscape()
                b == 0x18 || b == 0x1a -> { exec(b); state = S.GROUND } // CAN/SUB = abort sequence (xterm.js)
                b < 0x20 -> exec(b)                // C0 mid ESC seq = execute then continue (xterm.js)
                b == 0x7f -> {}
                b == 0x5b -> enterCsi()            // ESC [ = CSI (7-bit form — xterm.js ESC table)
                b == 0x5d -> { stringKind = K_OSC; state = S.OSC }  // ESC ] = OSC
                b == 0x50 -> { stringKind = K_DCS.code; state = S.STRING } // ESC P = DCS
                b in 0x20..0x2f -> { pushInter(b); state = S.ESCAPE_INTER }
                b in 0x30..0x7e -> dispatchEsc(b)
                else -> state = S.GROUND                  // C1 mid ESC seq = cancel (xterm.js: ignore)
            }
            S.CSI_ENTRY, S.CSI_PARAM -> when {
                b == 0x1b -> enterEscape()
                b == 0x18 || b == 0x1a -> { exec(b); state = S.GROUND } // CAN/SUB = abort sequence (xterm.js)
                b < 0x20 -> exec(b)
                b == 0x7f -> {}
                b in 0x30..0x39 -> {                      // digit — capped at 65535 (xterm.js clamp)
                    cur = if (cur < 0) b - 0x30 else if (cur > 6553) 65535 else minOf(cur * 10 + (b - 0x30), 65535)
                    state = S.CSI_PARAM
                }
                b == 0x3b -> { finishCur(); curSub = false; state = S.CSI_PARAM } // ';' = new top-level param
                b == 0x3a -> { finishCur(); curSub = true; state = S.CSI_PARAM }  // ':' = next param is a subparam
                b in 0x3c..0x3f -> if (nParams == 0 && cur < 0 && interLen == 0) { marker = b; state = S.CSI_PARAM } else state = S.CSI_IGNORE
                b in 0x20..0x2f -> { pushInter(b); state = S.CSI_INTER }
                b in 0x40..0x7e -> dispatchCsi(b)
                else -> state = S.CSI_IGNORE              // C1 mid CSI = ignore the whole sequence (xterm.js)
            }
            S.CSI_INTER -> when {
                b == 0x18 || b == 0x1a -> { exec(b); state = S.GROUND } // CAN/SUB = abort sequence (xterm.js)
                b < 0x20 -> exec(b)
                b == 0x1b -> enterEscape()
                b == 0x7f -> {}
                b in 0x20..0x2f -> pushInter(b)
                b in 0x40..0x7e -> dispatchCsi(b)
                else -> state = S.CSI_IGNORE              // digit/marker after an intermediate = error
            }
            S.CSI_IGNORE -> when {
                b == 0x1b -> enterEscape()
                b in 0x40..0x7e -> { state = S.GROUND; marker = 0; interLen = 0; nParams = 0; cur = -1 } // final byte = swallow ends
            }
            S.OSC -> when {
                b == 0x07 -> endString(true)              // BEL terminates (xterm.js)
                b == 0x1b -> { endString(true); step(b) } // ESC = ST — hand ESC back to GROUND to start a new escape (data passes through — xterm.js also dispatches the OSC first)
                b == 0x9c || b in 0x9e..0x9f -> endString(true)
                b == 0x18 || b == 0x1a -> endString(false) // CAN/SUB = abort, no dispatch
                b == 0x7f -> {}
                else -> if (sbuf.size < MAX_STRING) sbuf.add(b.toByte())
            }
            S.STRING -> when {                           // DCS/SOS/PM/APC absorb
                b == 0x1b -> { endString(false); step(b) } // ESC = abort, no dispatch (the ESC-\ ST form has no clear in-string form)
                b == 0x9c || b in 0x9e..0x9f -> endString(true) // ST/SOS/PM end normally → dispatch stringAbsorb
                b == 0x18 || b == 0x1a -> endString(false)
                else -> if (sbuf.size < MAX_STRING) sbuf.add(b.toByte())
            }
        }
    }

    private fun enterCsi() { state = S.CSI_ENTRY; marker = 0; interLen = 0; nParams = 0; cur = -1; curSub = false; utf8.reset() }

    companion object {
        private const val MAX_STRING = 65536 // xterm.js ~ MAX_STRING_SIZE — beyond it, keep absorbing but drop the content
        private const val MAX_PARAMS = 256
        private const val K_OSC = 0x4F // 'O'
        private const val K_DCS = 'P'
        private const val K_SOS = 'X'
        private const val K_PM = '^'
        private const val K_APC = '_'
    }
}
