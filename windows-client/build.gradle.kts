plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":client-core"))
    implementation(project(":protocol-kmp"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-grpc-client:0.11.0-grpc-189")
    runtimeOnly("io.grpc:grpc-netty:1.81.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "dev.sharedlists.spike.windows.MainKt"
}
