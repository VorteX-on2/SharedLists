plugins {
    kotlin("jvm")
    application
    id("com.google.protobuf")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.grpc:grpc-kotlin-stub:1.5.0")
    implementation("io.grpc:grpc-netty:1.81.0")
    implementation("io.grpc:grpc-protobuf:1.81.0")
    implementation("io.grpc:grpc-stub:1.81.0")
    implementation("com.google.protobuf:protobuf-kotlin:4.33.5")
    implementation("com.nimbusds:nimbus-jose-jwt:10.8")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test"))
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

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "dev.sharedlists.spike.server.MainKt"
}
