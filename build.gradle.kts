plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("maven-publish")
}

group = "dev.aterminal"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// no runtime deps beyond kotlin-stdlib — conformance bridge runs via JavaExec (dumpGrid), not instrumented tests
dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging { events("passed", "failed", "skipped") }
    // FloodEvictTest must prove bounded memory — keep the heap deliberately tight
    maxHeapSize = "256m"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    withSourcesJar()
}

// MIT requires the notices to travel with every copy, including published jars
tasks.withType<Jar>().configureEach {
    from(rootDir) {
        include("LICENSE", "THIRD_PARTY_NOTICES.md")
        into("META-INF")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("aterminal-engine")
                description.set("Pure-Kotlin VT/xterm terminal engine (parser, grid and reflow), developed against @xterm/headless 5.5.0")
                url.set("https://github.com/chaisit/aterminal-engine")
                licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
                developers { developer { id.set("chaisit"); name.set("chaisit"); url.set("https://github.com/chaisit") } }
                scm {
                    url.set("https://github.com/chaisit/aterminal-engine")
                    connection.set("scm:git:https://github.com/chaisit/aterminal-engine.git")
                    developerConnection.set("scm:git:ssh://git@github.com/chaisit/aterminal-engine.git")
                }
            }
        }
    }
}

// batch: gradle -q dumpGridBatch -Plist=<listfile> — ONE JVM for all units
tasks.register<JavaExec>("dumpGridBatch") {
    group = "conformance"
    mainClass.set("dev.aterminal.engine.EngineBatchKt")
    classpath = sourceSets["main"].runtimeClasspath
    val list = project.findProperty("list") as String? ?: error("usage: -Plist=<listfile>")
    args(list)
}

// step-script harness: gradle -q engineSteps -Psteps=<steps file> -Pout=<grid json>
tasks.register<JavaExec>("engineSteps") {
    group = "conformance"
    mainClass.set("dev.aterminal.engine.EngineStepsKt")
    classpath = sourceSets["main"].runtimeClasspath
    val steps = project.findProperty("steps") as String? ?: error("usage: -Psteps=<file> -Pout=<file>")
    val out = project.findProperty("out") as String? ?: error("usage: -Psteps=<file> -Pout=<file>")
    args(steps, out)
}

// conformance bridge: gradle -q dumpGrid -Praw=<raw bytes> -Pout=<grid json>
tasks.register<JavaExec>("dumpGrid") {
    group = "conformance"
    mainClass.set("dev.aterminal.engine.EngineMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    val raw = project.findProperty("raw") as String? ?: error("usage: -Praw=<path raw> -Pout=<path out>")
    val out = project.findProperty("out") as String? ?: error("usage: -Praw=<path raw> -Pout=<path out>")
    val cols = (project.findProperty("cols") as String?)?.toIntOrNull() ?: 80
    val rows = (project.findProperty("rows") as String?)?.toIntOrNull() ?: 24
    val hist = (project.findProperty("history") as String?)?.toIntOrNull() ?: 9001
    args(raw, out, cols.toString(), rows.toString(), hist.toString())
}
