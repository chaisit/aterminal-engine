package dev.aterminal.engine

import java.io.File

/**
 * batch conformance entrypoint: ONE JVM for all units — list file, one line per job:
 *   <raw path>\t<out path>[\tcols\trows\thistory]   (default 80/24/9001; comment lines start with '#')
 * semantics per job = EngineMain as before (fresh engine ≡ term.reset+resize → PREAMBLE → body → dump)
 * exit != 0 + prints the jobs that threw (fuzz lesson: write never throws — if it throws it's a bug, the raw name must be visible)
 */
fun main(args: Array<String>) {
    require(args.size >= 1) { "usage: EngineBatch <listfile>" }
    val jobs = File(args[0]).readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
    val pre = object {}.javaClass.getResourceAsStream("/preamble.raw")?.readBytes()
        ?: error("missing resource preamble.raw")
    var bad = 0
    for ((i, line) in jobs.withIndex()) {
        val f = line.split('\t')
        require(f.size >= 2) { "bad list line ${i + 1}: $line" }
        val raw = File(f[0]); val out = File(f[1])
        val cols = f.getOrNull(2)?.toIntOrNull() ?: 80
        val rows = f.getOrNull(3)?.toIntOrNull() ?: 24
        val hist = f.getOrNull(4)?.toIntOrNull() ?: 9001
        try {
            val e = TerminalEngine(cols, rows, hist)
            e.write(pre)
            e.write(raw.readBytes())
            out.parentFile?.mkdirs()
            out.writeText(GridDump.dump(e), Charsets.UTF_8)
        } catch (t: Throwable) {
            System.err.println("JOB-FAIL ${f[0]}: $t")
            t.printStackTrace(System.err)
            bad++
        }
    }
    println("EngineBatch: ${jobs.size - bad}/${jobs.size} ok")
    if (bad > 0) kotlin.system.exitProcess(1)
}
