plugins {
    id("com.google.protobuf")
    kotlin("jvm")
}

dependencies {
    api("com.google.protobuf:protobuf-kotlin:4.33.5")
    api("io.grpc:grpc-kotlin-stub:1.5.0")
    api("io.grpc:grpc-protobuf:1.81.0")
    api("io.grpc:grpc-stub:1.81.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        proto.srcDir(rootProject.layout.projectDirectory.dir("proto"))
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.33.5"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.81.0"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.5.0:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("kotlin")
            }
            plugins {
                create("grpc")
                create("grpckt")
            }
        }
    }
}
