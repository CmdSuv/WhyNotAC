/*
 * WhyNotAC — server-side anomaly detection for Minecraft Java Edition 1.21.11.
 *
 * Module graph (arrows point at dependencies):
 *
 *   whynotac-paper ──► whynotac-recording ──► whynotac-protocol ──► whynotac-math
 *   whynotac-paper ──► whynotac-world ──► whynotac-protocol (the client's world: block data, chunks, replica)
 *   whynotac-physics ──► whynotac-world, whynotac-math (the client's own movement, bit for bit)
 *   whynotac-analysis ──► whynotac-protocol, whynotac-world, whynotac-physics, whynotac-recording
 *   whynotac-verdict ──► whynotac-analysis, whynotac-recording (correlation, certificates, second verifier, ActionPolicy gate)
 *   whynotac-storage ──► whynotac-verdict, whynotac-recording (hash-chained audit ledger, profile persistence, four-eyes review)
 *   whynotac-paper ──► whynotac-storage, whynotac-verdict, whynotac-analysis, whynotac-world, whynotac-recording, whynotac-protocol
 *   whynotac-conformance ──► whynotac-math (+ vanilla 1.21.11 server code, test-only)
 *   whynotac-bench ──► everything (JMH micro-benchmarks, never shipped)
 *   whynotac-interop-probe: test-only Paper plugin for the live interop E2E (never shipped)
 *
 * Planned layers (folders exist with a README describing their contract; they are added to the
 * build when their phase starts): whynotac-ai.
 */
pluginManagement {
    includeBuild("build-logic")
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        gradlePluginPortal()
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencyResolutionManagement {
    repositories {
        // Google's official Maven Central mirror first: repo.maven.apache.org rate-limits shared CI egress.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "MavenCentralGoogleMirror"
            mavenContent { releasesOnly() }
        }
        mavenCentral { mavenContent { releasesOnly() } }
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "whynotac"

include(
    "whynotac-math",
    "whynotac-protocol",
    "whynotac-recording",
    "whynotac-world",
    "whynotac-physics",
    "whynotac-analysis",
    "whynotac-verdict",
    "whynotac-storage",
    "whynotac-paper",
    "whynotac-conformance",
    "whynotac-bench",
    "whynotac-interop-probe",
)
