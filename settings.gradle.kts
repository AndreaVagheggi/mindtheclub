pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // google() carries the androidx libraries (Room, SQLite, paging).
        google()
        mavenCentral()
    }
}

rootProject.name = "mtc-desktop"
