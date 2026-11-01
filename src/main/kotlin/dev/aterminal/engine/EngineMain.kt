package dev.aterminal.engine

import java.io.File

/**
 * conformance entrypoint: `./gradlew :engine:dumpGrid -Praw=<raw> -Pout=<json> [-Pcols= -Prows= -Phistory=]`
 * Replayed side-by-side against replay-headless.mjs: fresh engine (≡ term.reset) → PREAMBLE (resource
 * byte-identical with common.mjs) → body → dump
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: EngineMain <raw> <out> [cols=80] [rows=24] [history=9001]" }
    val raw = File(args[0])
    val out = File(args[1])
    val cols = args.getOrNull(2)?.toIntOrNull() ?: 80
    val rows = args.getOrNull(3)?.toIntOrNull() ?: 24
    val hist = args.getOrNull(4)?.toIntOrNull() ?: 9001

    val e = TerminalEngine(cols, rows, hist)
    val pre = object {}.javaClass.getResourceAsStream("/preamble.raw")?.readBytes()
        ?: error("missing resource preamble.raw (engine/src/main/resources/)")
    e.write(pre)
    e.write(raw.readBytes())
    out.parentFile?.mkdirs()
    out.writeText(GridDump.dump(e), Charsets.UTF_8)
}
