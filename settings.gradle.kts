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

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    maven {
      url = uri("https://maven-central.storage-download.googleapis.com/maven2")
      content {
        includeModule("org.robolectric", "android-all-instrumented")
      }
    }
    mavenCentral()
  }
}

rootProject.name = "QuantChat"

include(":app")
include(":engine-core")
include(":engine-llamacpp")
include(":hardware-manager")
include(":resource-manager")
include(":model-manager")
include(":session-manager")
include(":local-server")
include(":system-integration")
