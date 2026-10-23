import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    id("whynotac.java-conventions")
    alias(libs.plugins.paperweight.userdev)
}

description = "Paper 1.21.11 platform: NMS/Netty packet interceptor and plugin bootstrap."

// Paper 1.20.5+ runs on Mojang mappings, so the jar we build is the jar we ship.
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.dev.bundle.get())
    implementation(project(":whynotac-protocol"))
    implementation(project(":whynotac-recording"))
    implementation(project(":whynotac-world"))
    implementation(project(":whynotac-analysis"))
    testImplementation(project(":whynotac-physics")) // conformance of the movement engine's shapes against the game's
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString(), "minecraft" to libs.versions.minecraft.get())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

// The plugin jar must carry the platform-agnostic modules (they are not provided by the server).
tasks.jar {
    dependsOn(":whynotac-analysis:jar", ":whynotac-physics:jar", ":whynotac-recording:jar", ":whynotac-world:jar", ":whynotac-protocol:jar", ":whynotac-math:jar")
    // The plugin jar doubles as the offline tool: java -jar WhyNotAC.jar inspect|dump|find|forget|verify ...
    manifest.attributes("Main-Class" to "dev.whynotac.analysis.cli.Tool")
    from(configurations.runtimeClasspath.map { cp ->
        cp.filter { it.name.startsWith("whynotac-") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
