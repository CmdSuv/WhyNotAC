plugins {
    id("whynotac.java-conventions")
    alias(libs.plugins.paperweight.userdev)
}

description = "JMH micro-benchmarks of the hot paths (never shipped). Run: ./gradlew :whynotac-bench:jmh"

// JMH without a Gradle plugin: the annotation processor generates the harness, JavaExec runs it.
sourceSets {
    create("jmh") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

configurations.named("jmhImplementation") { extendsFrom(configurations.compileClasspath.get()) }
configurations.named("jmhRuntimeOnly") { extendsFrom(configurations.runtimeClasspath.get()) }

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.api.get())
    "jmhImplementation"(project(":whynotac-paper"))
    "jmhImplementation"(project(":whynotac-recording"))
    "jmhImplementation"(libs.jmh.core)
    "jmhAnnotationProcessor"(libs.jmh.annprocess)
}

tasks.named<JavaCompile>("compileJmhJava") {
    // JMH's generated sources trigger lint warnings we do not control.
    options.compilerArgs.removeAll(listOf("-Werror"))
    options.compilerArgs.add("-Xlint:none")
}

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs all JMH benchmarks with the GC profiler; JSON results in build/jmh/results.json"
    dependsOn("jmhClasses")
    classpath = sourceSets["jmh"].runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    val results = layout.buildDirectory.file("jmh/results.json")
    outputs.file(results)
    doFirst { results.get().asFile.parentFile.mkdirs() }
    args = listOf("-prof", "gc", "-rf", "json", "-rff", results.get().asFile.absolutePath) +
        (project.findProperty("jmhArgs")?.toString()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList())
}
