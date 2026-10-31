plugins {
    id("whynotac.java-conventions")
}

description = "The client's own movement, re-implemented bit for bit from the decompiled 1.21.11 client: collisions and the movement step. Platform-agnostic."

dependencies {
    api(project(":whynotac-world")) // block states and their collision shapes, the client's world
    api(project(":whynotac-math"))
}
