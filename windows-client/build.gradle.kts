plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":shared-client"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "dev.sharedlists.windows.MainKt"
}
