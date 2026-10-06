plugins {
    id("whynotac.java-conventions")
}

description = "Lossless session recording (crash-safe block format), pseudonymisation, replay and offline inspection."

dependencies {
    api(project(":whynotac-protocol"))
}
