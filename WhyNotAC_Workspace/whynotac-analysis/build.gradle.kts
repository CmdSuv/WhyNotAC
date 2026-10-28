plugins {
    id("whynotac.java-conventions")
}

description = "Checks, evidence and admissibility, the action policy (docs/POLICY.md) and the verdict layer. Platform-agnostic."

dependencies {
    api(project(":whynotac-protocol"))
    api(project(":whynotac-world")) // the replicas the checks read
    implementation(project(":whynotac-physics")) // the on-foot step the movement check runs
    implementation(project(":whynotac-recording")) // the verify tool re-runs checks over recordings
    testImplementation(libs.fastutil) // the modifier tables are checked against the client's own hash map
}

// The policy test compares the code's matrix with the table in docs/POLICY.md.
tasks.withType<Test>().configureEach {
    systemProperty("whynotac.policyDoc", rootProject.file("docs/POLICY.md").absolutePath)
    inputs.file(rootProject.file("docs/POLICY.md"))
}

// The replay gate (RegressionReplayTest) replays every check over the honest sessions in regression/.
tasks.withType<Test>().configureEach {
    systemProperty("whynotac.regressionCorpus", rootProject.file("regression").absolutePath)
    inputs.dir(rootProject.file("regression"))
}
