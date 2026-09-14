plugins {
    kotlin("jvm") version "2.4.0" apply false
    kotlin("multiplatform") version "2.4.0" apply false
    id("com.android.application") version "9.1.0" apply false
}

tasks.register("hostCheck") {
    group = "verification"
    description = "Compiles and tests every target supported on this host."
    dependsOn(
        ":shared-client:jvmTest",
        ":shared-client:compileKotlinIosSimulatorArm64",
        ":android-client:assembleDebug",
        ":server:test",
        ":windows-client:test",
    )
}
