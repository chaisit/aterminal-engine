package dev.aterminal.engine.term
// Portions derived from xterm.js (MIT), see THIRD_PARTY_NOTICES.md

// MODE/ANSI modes — all semantics pinned from the oracle bundle + probes:
//  - IRM (4) really takes effect at print time (insertCells) — xterm.js implements it (irm_print evidence)
//  - DECSCNM (?5) state-only (q5_scope: resets nothing at all — the renderer alone flips the colors)
//  - LNM/SRM state-only per xterm.js (no effect on the grid the dump shows)
class ModeState {
    var origin = false           // ?6  DECOM — toggling homes the cursor (NOT a full reset)
    var autowrap = true          // ?7  DECAWM (xterm.js decPrivateModes.wraparound default TRUE)
    var cursorVisible = true     // ?25 DECTCEM
    var cursorBlink = true       // ?12 (this round's renderer blinks at 530ms)
    var appCursorKeys = false    // ?1  DECCKM (used by the input layer)
    var screenModeReverse = false// ?5  DECSCNM (state only)
    var bracketedPaste = false   // ?2004
    var mouseClick = false       // ?1000
    var mouseDrag = false        // ?1002
    var mouseAny = false         // ?1003
    var mouseSgr = false         // ?1006
    var mouseUrxvt = false       // ?1015
    var altCols = false          // ?3  (132col — state only, geometry unchanged)
    var lnm = false              // 20  LNM (state only per the oracle)
    var sendReceive = false      // 2   SRM (state only)
    var insertMode = false       // 4   IRM — print = insert cells
    var reverseWraparound = false// ?45 (state only — BS floats up a line; no fixtures for this)

    fun resetDefaults() {
        origin = false; autowrap = true; cursorVisible = true; cursorBlink = true
        appCursorKeys = false; screenModeReverse = false; bracketedPaste = false
        mouseClick = false; mouseDrag = false; mouseAny = false; mouseSgr = false
        mouseUrxvt = false; altCols = false; lnm = false; sendReceive = false
        insertMode = false; reverseWraparound = false
    }
}
