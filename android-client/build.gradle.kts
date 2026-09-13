plugins {
    id("com.android.application")
}

android {
    namespace = "dev.sharedlists.spike.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.sharedlists.spike.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes += setOf(
        "META-INF/INDEX.LIST",
        "META-INF/io.netty.versions.properties",
    )
}

dependencies {
    implementation(project(":client-core"))
    implementation(project(":protocol-kmp"))
    implementation("io.grpc:grpc-okhttp:1.81.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
