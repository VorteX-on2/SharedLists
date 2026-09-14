plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":protocol"))
    implementation("io.grpc:grpc-kotlin-stub:1.5.0")
    implementation("io.grpc:grpc-netty:1.81.0")
    implementation("io.grpc:grpc-stub:1.81.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("com.nimbusds:nimbus-jose-jwt:10.8")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation(kotlin("test"))
    testImplementation(project(":shared-client"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "dev.sharedlists.server.MainKt"
}

tasks.withType<Test>().configureEach {
    dependsOn(tasks.installDist)
    useJUnitPlatform()
}
