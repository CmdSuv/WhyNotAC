import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    id("whynotac.java-conventions")
    alias(libs.plugins.paperweight.userdev)
}

description = "Test-only Paper plugin for the live E2E: sends and cancels knockback below WhyNotAC, the way PacketEvents does. Never shipped."

paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

// paperweight declares project repositories, which take precedence over settings.gradle.kts.
repositories {
    maven("https://repo.codemc.io/repository/maven-releases/") {
        name = "CodeMC"
        content { includeGroup("com.github.retrooper") } // PacketEvents only
    }
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.dev.bundle.get())
    // Provided at runtime by the PacketEvents plugin (interop variant only); loaded lazily.
    compileOnly(libs.packetevents.api) { isTransitive = false }
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}
