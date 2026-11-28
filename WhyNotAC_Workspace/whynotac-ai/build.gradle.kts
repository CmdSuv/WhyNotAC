plugins {
    id("whynotac.java-conventions")
}

description = "Statistical/design triage, personal baselines, account-shift detection, and sequential e-value probe scheduling (AI-00..AI-05; strictly non-punitive)."

dependencies {
    api(project(":whynotac-analysis"))
    api(project(":whynotac-verdict"))
    api(project(":whynotac-math"))
    testImplementation(project(":whynotac-recording"))
}
