plugins {
    base
    kotlin("jvm") version "2.4.0" apply false
    kotlin("multiplatform") version "2.4.0" apply false
    kotlin("android") version "2.4.0" apply false
    id("com.android.application") version "9.1.0" apply false
    id("com.android.kotlin.multiplatform.library") version "9.1.0" apply false
    id("org.jetbrains.kotlinx.rpc.plugin") version "0.11.0-grpc-189" apply false
    id("com.google.protobuf") version "0.9.5" apply false
}

tasks.register("spikeCheck") {
    group = "verification"
    description = "Runs every host-executable spike check."
    dependsOn(
        ":android-client:assembleDebug",
        ":android-client:assembleDebugAndroidTest",
        ":client-core:compileAndroidMain",
        ":client-core:compileKotlinIosSimulatorArm64",
        ":client-core:jvmTest",
        ":server:test",
        ":windows-client:test",
    )
}
