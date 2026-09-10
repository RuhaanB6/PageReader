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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // tesseract4android is not published to Maven Central (repo1 404s);
        // JitPack serves it. FAIL_ON_PROJECT_REPOS above forbids declaring
        // this at module level, so it has to live here.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PageReader"
include(":app")
include(":collector")
