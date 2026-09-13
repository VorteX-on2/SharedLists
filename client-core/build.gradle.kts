plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "dev.sharedlists.spike.client"
        compileSdk = 36
        minSdk = 26
    }
    iosArm64()
    iosSimulatorArm64()
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol-kmp"))
            implementation("org.jetbrains.kotlinx:kotlinx-rpc-grpc-client:0.11.0-grpc-189")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}
