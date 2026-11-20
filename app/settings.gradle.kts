// The settings file is the entry point of every Gradle build.
// Its primary purpose is to define the subprojects.
// It is also used for some aspects of project-wide configuration, like managing plugins, dependencies, etc.
// https://docs.gradle.org/current/userguide/settings_file_basics.html

pluginManagement {
    repositories {
        // Same gate as gradle/artifactory.settings.gradle.kts — pluginManagement
        // must be the first block and cannot be fed from an applied script.
        val artifactoryUrl = providers.environmentVariable("ARTIFACTORY_URL").orNull?.takeIf { it.isNotBlank() }
        val artifactoryUser = providers.environmentVariable("ARTIFACTORY_USER").orNull?.takeIf { it.isNotBlank() }
        val artifactoryToken = providers.environmentVariable("ARTIFACTORY_TOKEN").orNull?.takeIf { it.isNotBlank() }
        if (artifactoryUrl != null && artifactoryUser != null && artifactoryToken != null) {
            require(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?").matches(artifactoryUrl)) { "ARTIFACTORY_URL must be https://<host>[:port]" }
            maven {
                name = "artifactory"
                // CWE-319: TLS only — never isAllowInsecureProtocol here.
                url = uri("$artifactoryUrl/artifactory/maven-all/")
                credentials {
                    username = artifactoryUser
                    password = artifactoryToken
                }
                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }
        gradlePluginPortal()
    }
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Optional: resolve dependencies through Artifactory when the ARTIFACTORY_* environment
// variables are set. GitHub CI sets none of them and resolves from Maven Central (ADR-0084).
apply(from = "gradle/artifactory.settings.gradle.kts")

dependencyResolutionManagement {
    // Use Maven Central as the default repository (where Gradle will download dependencies) in all subprojects.
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

// Read-only remote build cache in Artifactory, on only when all three ARTIFACTORY_* variables
// are set; GitHub CI sets none and caches through setup-gradle (ADR-0084).
buildCache {
    val artifactoryUrl = providers.environmentVariable("ARTIFACTORY_URL").orNull?.takeIf { it.isNotBlank() }
    val artifactoryUser = providers.environmentVariable("ARTIFACTORY_USER").orNull?.takeIf { it.isNotBlank() }
    val artifactoryToken = providers.environmentVariable("ARTIFACTORY_TOKEN").orNull?.takeIf { it.isNotBlank() }
    if (artifactoryUrl != null && artifactoryUser != null && artifactoryToken != null) {
        require(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?").matches(artifactoryUrl)) { "ARTIFACTORY_URL must be https://<host>[:port]" }
        remote<HttpBuildCache> {
            // CWE-319: TLS only — never isAllowInsecureProtocol here.
            url = uri("$artifactoryUrl/artifactory/gradle-build/homer/")
            credentials {
                username = artifactoryUser
                password = artifactoryToken
            }
        }
    }
}

// Include the `app` and `utils` subprojects in the build.
// If there are changes in only one of the projects, Gradle will rebuild only the one that has changed.
// Learn more about structuring projects with Gradle - https://docs.gradle.org/8.7/userguide/multi_project_builds.html
include(":app")
include(":agentdebug")
include(":homer-runner")
include(":e2e")
include(":architecture")
//include(":utils")

rootProject.name = "Homer"
