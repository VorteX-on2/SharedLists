plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
        }
        jvmMain.dependencies {
            implementation(project(":protocol"))
            implementation("io.grpc:grpc-netty:1.81.0")
            implementation("io.grpc:grpc-stub:1.81.0")
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
