pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://redirector.kotlinlang.org/maven/kxrpc-grpc")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://redirector.kotlinlang.org/maven/kxrpc-grpc")
    }
}

rootProject.name = "sharedlists-transport-auth-spike"

include(":client-core")
include(":android-client")
include(":protocol-kmp")
include(":server")
include(":windows-client")
