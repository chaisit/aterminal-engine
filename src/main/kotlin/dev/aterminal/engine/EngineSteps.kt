package dev.aterminal.engine

import java.io.File
import java.util.Base64

/**
 * harness entrypoint: `./gradlew -q engineSteps -Psteps=<file> -Pout=<json>`
 * reads the step-script format of the conformance harness:
 *   INIT            → fresh engine (≡ term.reset) + resize 80×24 + PREAMBLE
 *   B <base64>      → write bytes
 *   R <cols> <rows> → resize
 * comments = lines starting with # / blank lines
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: EngineSteps <steps-file> <out-json> [history=9001]" }
    val steps = File(args[0])
    val out = File(args[1])
    val hist = args.getOrNull(2)?.toIntOrNull() ?: 9001
    val pre = object {}.javaClass.getResourceAsStream("/preamble.raw")?.readBytes()
        ?: error("missing resource preamble.raw (engine/src/main/resources/)")

    var e = TerminalEngine(80, 24, hist)
    for (raw in steps.readLines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val parts = line.split(Regex("\\s+"))
        when (parts[0]) {
            "INIT" -> { e = TerminalEngine(80, 24, hist); e.write(pre) }
            "B" -> e.write(Base64.getDecoder().decode(parts[1]))
            "R" -> e.resize(parts[1].toInt(), parts[2].toInt())
            else -> error("unknown step op: $line")
        }
    }
    out.parentFile?.mkdirs()
    out.writeText(GridDump.dump(e), Charsets.UTF_8)
}
