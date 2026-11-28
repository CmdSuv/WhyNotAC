plugins {
    id("whynotac.java-conventions")
}

description = "The client's world as the client holds it: block-state data, chunk decoding and a replica of the client's chunk storage. Platform-agnostic."

dependencies {
    api(project(":whynotac-protocol"))
}
