pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
        // Pleos Connect Maven repository (if hosted internally or via local/remote repo)
        maven { url = java.net.URI("https://jitpack.io") }
    }
}

rootProject.name = "RentalCarAdaptationCoach"
include(":app")

