plugins {
    id("whynotac.java-conventions")
}

description = "Hash-chained audit ledger (STO-01..03, PROD-13), persistent subject profiles, and four-eyes review workflow."

dependencies {
    api(project(":whynotac-verdict"))
    implementation(project(":whynotac-recording"))
}
