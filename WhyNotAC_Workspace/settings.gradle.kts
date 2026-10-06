/*
 * WhyNotAC — server-side anomaly detection for Minecraft Java Edition 1.21.11.
 *
 * Module graph (arrows point at dependencies):
 *
 *   whynotac-paper ──► whynotac-recording ──► whynotac-protocol ──► whynotac-math
 *   whynotac-conformance ──► whynotac-math (+ vanilla 1.21.11 server code, test-only)
 *
 * Planned layers (folders exist with a README describing their contract; they are added to the
 * build when their phase starts): whynotac-physics, whynotac-analysis, whynotac-ai,
 * whynotac-verdict, whynotac-storage.
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
    "whynotac-paper",
    "whynotac-conformance",
)
