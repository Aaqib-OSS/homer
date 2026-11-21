plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.kotlinTest)
    testImplementation(libs.kotlinTestJunit5)
    testImplementation(libs.junitJupiter)
}

// A rule tagged report-only still has findings: it runs in architectureReportOnly, which never fails
// the build, and the warn-mode check architecture-backend-pending lists it (ADR-0082).
val reportOnlyTag = "report-only"

tasks.withType<Test>().configureEach {
    val repoRoot = rootDir.parentFile
    systemProperty("homer.repoRoot", repoRoot.absolutePath)
    // Konsist parses every backend source into one JVM; Gradle's 512m default ran out of heap in CI.
    maxHeapSize = "1g"
    // The rules read other modules' sources and the backend guide; undeclared, editing them would replay a cached result.
    inputs
        .files(
            fileTree(repoRoot) {
                include("app/app/src/**/*.kt", "app/agentdebug/src/**/*.kt", "app/homer-runner/src/**/*.kt", "app/CLAUDE.md")
            },
        ).withPropertyName("scannedSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.test {
    useJUnitPlatform { excludeTags(reportOnlyTag) }
}

val architectureReportOnly =
    tasks.register<Test>("architectureReportOnly") {
        group = "verification"
        description = "Runs the report-only architecture rules; their findings never fail the build (ADR-0082)."
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags(reportOnlyTag) }
        ignoreFailures = true
        // An empty lane (every rule promoted) is not an error.
        failOnNoDiscoveredTests = false
        reports.junitXml.outputLocation = layout.buildDirectory.dir("test-results/architectureReportOnly")
        shouldRunAfter(tasks.test)
    }

tasks.check { dependsOn(architectureReportOnly) }
