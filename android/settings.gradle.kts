pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "expenses-android"

// Pulls the shared DTO module (com.bnuprst.expenses:shared) from the repository root build.
includeBuild("..")
include(":app")
