/*
 * Shared Java conventions for every WhyNotAC module.
 *
 * - Java 21 (the runtime Minecraft 1.21.11 / Paper requires).
 * - strictfp is implicit since Java 17 (JEP 306): all float/double arithmetic is IEEE-754 strict on
 *   every JVM, which is what makes bit-exact replication of the vanilla client possible at all.
 * - Warnings are errors: numerical code tolerates no silent lossy conversions.
 * - Reproducible builds: snapshot dependencies are pinned to one timestamped build, and any other
 *   snapshot fails resolution (archives are already reproducible by default since Gradle 9).
 */
plugins {
    `java-library`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial", "-Werror", "-parameters"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

val pinnedSnapshots = mapOf(
    "io.papermc.paper:paper-api" to libs.findVersion("pinned-paper-api").get().requiredVersion,
    "com.velocitypowered:velocity-native" to libs.findVersion("pinned-velocity-native").get().requiredVersion,
)
configurations.configureEach {
    resolutionStrategy.eachDependency {
        val pin = pinnedSnapshots["${requested.group}:${requested.name}"]
        if (pin != null) {
            useVersion(pin)
            because("snapshot pinned to one timestamped build (gradle/libs.versions.toml)")
        } else if (requested.version.orEmpty().endsWith("-SNAPSHOT") && requested.group != project.group.toString()) {
            throw GradleException("Unpinned snapshot dependency $requested: pin it to a timestamped build in gradle/libs.versions.toml")
        }
    }
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
