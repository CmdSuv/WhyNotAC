plugins {
    id("whynotac.java-conventions")
}

description = "Dependency-grouped correlation, independent P-A second verification, evidence certificates, circuit breakers, false-ban budgets, and ActionPolicy-gated enforcement."

dependencies {
    api(project(":whynotac-analysis"))
    implementation(project(":whynotac-recording"))
}
