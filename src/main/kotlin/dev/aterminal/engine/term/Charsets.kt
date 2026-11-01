package dev.aterminal.engine.term
// Portions derived from xterm.js (MIT), see THIRD_PARTY_NOTICES.md

// DEC/NAT character sets — tables copied verbatim from xterm.js 5.5 (CHARSETS in the lib-headless bundle, 2026-10-29)
// + confirmed by probe (non-vacuous) — never edit from memory
// semantics (probe-confirmed): a designator takes effect immediately (no shift needed), SO→GL=G1, SI→GL=G0, persists across CRLF,
// an unknown designator ('1','2','3','U'...) = designate fails = keep the previous charset (xterm.js designateCharset → false)
object Charsets {
    private fun m(vararg pairs: Pair<Char, String>): Map<Char, String> = mapOf(*pairs)

    private val DEC_SPECIAL = m(
        '`' to "◆", 'a' to "▒", 'b' to "␉", 'c' to "␌", 'd' to "␍", 'e' to "␊",
        'f' to "°", 'g' to "±", 'h' to "␤", 'i' to "␋", 'j' to "┘", 'k' to "┐",
        'l' to "┌", 'm' to "└", 'n' to "┼", 'o' to "⎺", 'p' to "⎻", 'q' to "─",
        'r' to "⎼", 's' to "⎽", 't' to "├", 'u' to "┤", 'v' to "┴", 'w' to "┬",
        'x' to "│", 'y' to "≤", 'z' to "≥", '{' to "π", '|' to "≠", '}' to "£", '~' to "·",
    )
    private val SWEDISH = m('[' to "Ä", '\\' to "Ö", ']' to "Å", '^' to "Ü", '`' to "é", '{' to "ä", '|' to "ö", '}' to "å", '~' to "ü") // xterm.js: C = 5
    private val DANISH = m('@' to "Ä", '[' to "Æ", '\\' to "Ø", ']' to "Å", '^' to "Ü", '`' to "ä", '{' to "æ", '|' to "ø", '}' to "å", '~' to "ü")        // E = 6
    private val APPLE = m('@' to "É", '[' to "Ä", '\\' to "Ö", ']' to "Å", '^' to "Ü", '`' to "é", '{' to "ä", '|' to "ö", '}' to "å", '~' to "ü")        // H = 7

    /** designator → map (byte ' '..'~' → string occupying 1 cell); null = ascii/identity; missing key = unknown (designate fails) */
    val TABLES: Map<Char, Map<Char, String>?> = mapOf(
        '0' to DEC_SPECIAL,
        'A' to m('#' to "£"),
        'B' to null,
        '4' to m('#' to "£", '@' to "¾", '[' to "ij", '\\' to "½", ']' to "|", '{' to "¨", '|' to "f", '}' to "¼", '~' to "´"), // 'ij' = 2 letters in 1 cell, exactly as the bundle really has it
        'C' to SWEDISH, '5' to SWEDISH,
        'R' to m('#' to "£", '@' to "à", '[' to "°", '\\' to "ç", ']' to "§", '{' to "é", '|' to "ù", '}' to "è", '~' to "¨"),
        'Q' to m('@' to "à", '[' to "â", '\\' to "ç", ']' to "ê", '^' to "î", '`' to "ô", '{' to "é", '|' to "ù", '}' to "è", '~' to "û"),
        'K' to m('@' to "§", '[' to "Ä", '\\' to "Ö", ']' to "Ü", '{' to "ä", '|' to "ö", '}' to "ü", '~' to "ß"),
        'Y' to m('#' to "£", '@' to "§", '[' to "°", '\\' to "ç", ']' to "é", '`' to "ù", '{' to "à", '|' to "ò", '}' to "è", '~' to "ì"),
        'E' to DANISH, '6' to DANISH,
        'Z' to m('#' to "£", '@' to "§", '[' to "¡", '\\' to "Ñ", ']' to "¿", '{' to "°", '|' to "ñ", '}' to "ç"),
        'H' to APPLE, '7' to APPLE,
        '=' to m('#' to "ù", '@' to "à", '[' to "é", '\\' to "ç", ']' to "ê", '^' to "î", '_' to "è", '`' to "ô", '{' to "ä", '|' to "ö", '}' to "ü", '~' to "û"),
    )
}
