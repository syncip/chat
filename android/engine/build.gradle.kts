import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val coreDir = rootDir.resolve("../core")
val bindingsDir = layout.buildDirectory.dir("generated/uniffi")

// JNA: auf Android liefert die App das AAR (mit nativen Dispatch-Bibliotheken); auf der JVM (Tests) das JAR.
val jnaJar: Configuration by configurations.creating

configurations.compileOnly { extendsFrom(jnaJar) }
configurations.testImplementation { extendsFrom(jnaJar) }

dependencies {
    jnaJar("net.java.dev.jna:jna:5.15.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("com.squareup.okhttp3:okhttp:4.12.0") // Teil der öffentlichen Engine-Signatur (Engine(http = …))

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// Rust-Kern für den Host bauen (Tests) und Kotlin-Bindings erzeugen.
val buildCoreHost = tasks.register<Exec>("buildCoreHost") {
    workingDir = coreDir
    commandLine("cargo", "build", "--features", "uniffi-cli")
    inputs.files(fileTree(coreDir.resolve("src")), coreDir.resolve("Cargo.toml"))
    outputs.file(coreDir.resolve("target/debug/libchat_core.so"))
}

val generateBindings = tasks.register<Exec>("generateBindings") {
    dependsOn(buildCoreHost)
    workingDir = coreDir
    commandLine(
        "cargo", "run", "--features", "uniffi-cli", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", "target/debug/libchat_core.so", "--language", "kotlin",
        "--no-format", "--out-dir", bindingsDir.get().asFile.absolutePath,
    )
    inputs.file(coreDir.resolve("target/debug/libchat_core.so"))
    outputs.dir(bindingsDir)
}

sourceSets.main { kotlin.srcDir(bindingsDir) }
tasks.named("compileKotlin") { dependsOn(generateBindings) }

tasks.test {
    useJUnitPlatform()
    dependsOn(buildCoreHost)
    // JNA findet libchat_core.so hier.
    systemProperty("jna.library.path", coreDir.resolve("target/debug").absolutePath)
    // Server-Binary für Integrationstests (optional): -Pchatd=/pfad/chatd
    (findProperty("chatd") as String?)?.let { systemProperty("chatd.path", it) }
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = TestExceptionFormat.FULL }
    maxHeapSize = "1g"
}

// Interop-Gegenstelle (siehe e2e/e2e-android-interop.mjs)
tasks.register<JavaExec>("runPeer") {
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath + jnaJar
    mainClass.set("chat.engine.tools.PeerKt")
    systemProperty("jna.library.path", coreDir.resolve("target/debug").absolutePath)
    args = (findProperty("peerArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}
