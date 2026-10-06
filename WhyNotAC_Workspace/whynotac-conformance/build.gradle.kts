plugins {
    id("whynotac.java-conventions")
    alias(libs.plugins.paperweight.userdev)
}

description = "Differential test suite: WhyNotAC numerics vs. the real Minecraft 1.21.11 classes (test-only, never shipped)."

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.api.get())
    testImplementation(project(":whynotac-math"))
}
