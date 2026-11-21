plugins {
    id("whynotac.java-conventions")
}

description = "Velocity 3.x proxy companion plugin: authenticated client-edge reporting and cross-server profile/verdict relay (NET-01 / NET-22)."

dependencies {
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
    implementation(project(":whynotac-protocol"))
    implementation(project(":whynotac-math"))
    testImplementation(libs.velocity.api)
}

tasks.jar {
    dependsOn(":whynotac-protocol:jar", ":whynotac-math:jar")
    from(configurations.runtimeClasspath.map { cp ->
        cp.filter { it.name.startsWith("whynotac-") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
