import kotlinx.rpc.protoc.*

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlinx.rpc.plugin")
}

kotlin {
    android {
        namespace = "dev.sharedlists.spike.protocol"
        compileSdk = 36
        minSdk = 26
    }
    iosArm64()
    iosSimulatorArm64()
    jvm()

    sourceSets {
        commonMain {
            proto.srcDir(rootProject.layout.projectDirectory.dir("proto"))
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-rpc-grpc-core:0.11.0-grpc-189")
                api("org.jetbrains.kotlinx:kotlinx-rpc-protobuf:0.11.0-grpc-189")
            }
        }
    }
}

rpc {
    protoc()
}
