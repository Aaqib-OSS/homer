plugins {
    // The Kotlin DSL plugin; convention plugins are `*.gradle.kts` files in src/main/kotlin.
    `kotlin-dsl`
    // buildSrc cannot apply its own `buildsrc.convention.kotlin-jvm`, so ktlint is applied
    // directly here and configured to match that plugin's block.
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(25)
}

ktlint {
    version.set("1.8.0")
    android.set(false)
    ignoreFailures.set(false)
    filter {
        exclude { entry -> entry.file.path.contains("/build/") }
    }
}

dependencies {
    // The CRAP report's PSI parser (ADR-0081): never `implementation`, since the Kotlin Gradle plugin's
    // relocated compiler classes must not share its classloader; declared first so it wins over them.
    compileOnly(libs.kotlinCompilerEmbeddable)
    testImplementation(libs.kotlinCompilerEmbeddable)
    // Add a dependency on the Kotlin Gradle plugin, so that convention plugins can apply it.
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.ktlintGradlePlugin)
    implementation(libs.koverGradlePlugin)
    implementation(libs.detektGradlePlugin)

    // Unit tests for the plain-Kotlin build logic in `src/main/kotlin` (e.g. ThirdPartyNotices).
    testImplementation(libs.kotlinTest)
    testImplementation(libs.kotlinTestJunit5)
    testImplementation(libs.junitJupiter)
}

tasks.test {
    useJUnitPlatform()
}
