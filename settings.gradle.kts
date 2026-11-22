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
        // Tesseract4Android (Bengali OCR fallback) is published on JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "OfflineGemma"
include(":app")
