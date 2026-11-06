plugins {
    id("whynotac.java-conventions")
}

description = "Platform-agnostic packet model, per-player ordered pipeline and latency (transaction) tracking."

dependencies {
    api(project(":whynotac-math"))
}
