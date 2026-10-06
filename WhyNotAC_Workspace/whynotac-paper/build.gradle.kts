import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    id("whynotac.java-conventions")
    alias(libs.plugins.paperweight.userdev)
}

description = "Paper 1.21.11 platform: NMS/Netty packet interceptor and plugin bootstrap."

// Paper 1.20.5+ runs on Mojang mappings, so the jar we build is the jar we ship.
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.api.get())
    implementation(project(":whynotac-protocol"))
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString(), "minecraft" to libs.versions.minecraft.get())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

// The plugin jar must carry the platform-agnostic modules (they are not provided by the server).
tasks.jar {
    dependsOn(":whynotac-protocol:jar", ":whynotac-math:jar")
    from(configurations.runtimeClasspath.map { cp ->
        cp.filter { it.name.startsWith("whynotac-") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
