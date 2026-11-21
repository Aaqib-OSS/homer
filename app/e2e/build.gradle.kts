plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    // Deliberately NO project(":app") dependency: the suite re-declares response shapes
    // as its own DTOs, so :app serialization regressions fail here (README.md).
    testImplementation(libs.kotlinTest)
    testImplementation(libs.kotlinTestJunit5)
    testImplementation(libs.junitJupiter)
    testImplementation(libs.kotlinxSerialization)
    testImplementation(libs.kotlinxCoroutines)
    testImplementation(libs.bundles.ktorClient)
    testImplementation(libs.ktorSerializationKotlinxJson)
    testImplementation(libs.slf4jSimple)
}

tasks.test {
    // Live-server tests: results depend on the server under test, never on
    // task inputs — always rerun.
    outputs.upToDateWhen { false }
    val baseUrl = System.getenv("E2E_BASE_URL") ?: ""
    environment("E2E_BASE_URL", baseUrl)
    environment("E2E_TOKEN", System.getenv("E2E_TOKEN") ?: "")
    environment("E2E_REQUIRED", System.getenv("E2E_REQUIRED") ?: "")
    doFirst {
        if (baseUrl.isBlank()) logger.warn("E2E_BASE_URL is not set: every black-box E2E test is skipped (app/e2e/README.md).")
    }
}
