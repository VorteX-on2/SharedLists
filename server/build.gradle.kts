plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "dev.sharedlists.server.MainKt"
}
