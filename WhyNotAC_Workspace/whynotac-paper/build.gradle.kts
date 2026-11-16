import io.papermc.paperweight.userdev.ReobfArtifactConfiguration
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

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
    implementation(project(":whynotac-verdict"))
    implementation(project(":whynotac-storage"))
    implementation(project(":whynotac-ai"))
    testImplementation(project(":whynotac-physics")) // conformance of the movement engine's shapes against the game's
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString(), "minecraft" to libs.versions.minecraft.get())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

// The plugin jar must carry the platform-agnostic modules (they are not provided by the server).
tasks.jar {
    dependsOn(":whynotac-ai:jar", ":whynotac-storage:jar", ":whynotac-verdict:jar", ":whynotac-analysis:jar", ":whynotac-physics:jar", ":whynotac-recording:jar", ":whynotac-world:jar", ":whynotac-protocol:jar", ":whynotac-math:jar")
    // The plugin jar doubles as the offline tool: java -jar WhyNotAC.jar inspect|dump|find|forget|verify ...
    manifest.attributes("Main-Class" to "dev.whynotac.analysis.cli.Tool")
    from(configurations.runtimeClasspath.map { cp ->
        cp.filter { it.name.startsWith("whynotac-") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

abstract class GenerateCycloneDxSbom : DefaultTask() {
    @get:Input abstract val componentName: Property<String>
    @get:Input abstract val componentVersion: Property<String>
    @get:Input abstract val artifactsMap: MapProperty<String, String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        val sorted = artifactsMap.get().toSortedMap()
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"bomFormat\": \"CycloneDX\",\n")
        sb.append("  \"specVersion\": \"1.5\",\n")
        sb.append("  \"version\": 1,\n")
        sb.append("  \"metadata\": {\n")
        sb.append("    \"component\": {\n")
        sb.append("      \"type\": \"application\",\n")
        sb.append("      \"name\": \"").append(componentName.get()).append("\",\n")
        sb.append("      \"version\": \"").append(componentVersion.get()).append("\"\n")
        sb.append("    }\n")
        sb.append("  },\n")
        sb.append("  \"components\": [\n")
        var first = true
        for ((coord, sha256) in sorted) {
            val parts = coord.split(":")
            val grp = parts.getOrElse(0) { "" }
            val name = parts.getOrElse(1) { coord }
            val ver = parts.getOrElse(2) { "" }
            if (!first) sb.append(",\n")
            first = false
            sb.append("    {\n")
            sb.append("      \"type\": \"library\",\n")
            sb.append("      \"group\": \"").append(grp).append("\",\n")
            sb.append("      \"name\": \"").append(name).append("\",\n")
            sb.append("      \"version\": \"").append(ver).append("\",\n")
            sb.append("      \"hashes\": [{ \"alg\": \"SHA-256\", \"content\": \"").append(sha256).append("\" }]\n")
            sb.append("    }")
        }
        sb.append("\n  ]\n}\n")
        out.writeText(sb.toString())
    }
}

val generateSbom = tasks.register<GenerateCycloneDxSbom>("generateSbom") {
    description = "Generates a canonical CycloneDX 1.5 SBOM with SHA-256 checksums for reproducible releases (REL-03)."
    group = "verification"
    dependsOn(tasks.jar)
    componentName.set("whynotac-paper")
    componentVersion.set(project.version.toString())
    artifactsMap.set(provider {
        val md = MessageDigest.getInstance("SHA-256")
        val map = linkedMapOf<String, String>()
        configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.forEach { art ->
            val id = art.moduleVersion.id
            val hash = md.digest(art.file.readBytes()).joinToString("") { "%02x".format(it) }
            map["${id.group}:${id.name}:${id.version}"] = hash
        }
        map
    })
    outputFile.set(layout.buildDirectory.file("reports/whynotac-sbom.cdx.json"))
}

tasks.check {
    dependsOn(generateSbom)
}
