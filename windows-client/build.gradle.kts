plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":shared-client"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "dev.sharedlists.windows.MainKt"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
