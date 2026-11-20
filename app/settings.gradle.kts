// The entry point of every Gradle build: the subprojects and project-wide plugin and repository configuration.

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Use Maven Central as the default repository (where Gradle will download dependencies) in all subprojects.
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

include(":app")
include(":agentdebug")
include(":homer-runner")
include(":e2e")
include(":architecture")
//include(":utils")

rootProject.name = "Homer"
