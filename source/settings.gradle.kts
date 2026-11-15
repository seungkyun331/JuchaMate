pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Connect SDK (Vehicle, TextToSpeech)
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            // Only the Pleos SDKs are looked up here, with what they bring along
            // (ai.pleos.playground and its sub-groups, such as ai.pleos.playground.security).
            content { includeGroupByRegex("ai\\.pleos\\..*") }
        }
    }
}

rootProject.name = "ParkCoach"
include(":app")
