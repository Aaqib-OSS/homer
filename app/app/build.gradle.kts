import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Apply the Application plugin to add support for building an executable JVM application.
    application
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.dependencyCheck)
    alias(libs.plugins.licenseReport)
    alias(libs.plugins.pitest)
}

dependencies {
//    implementation(project(":utils"))

    implementation(libs.bundles.ktor)
    implementation(libs.bundles.ktorClient)
    implementation(libs.bundles.opentelemetry)
    implementation(libs.bundles.koin)
    implementation(libs.logbackClassic)
    implementation(libs.langfuseClient) {
        // Its POM puts JUnit in compile scope; keep test frameworks off the runtime classpath
        // and out of the shadow JAR (W-005).
        exclude(group = "org.junit")
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
        exclude(group = "org.opentest4j")
    }
    implementation(libs.kotlinXSetializationYaml)
    implementation(libs.jsonPath)
    implementation(libs.cronUtils)
    implementation(libs.kubernetesClient)
    implementation(libs.bcrypt)
    implementation(libs.jsonSchemaValidator)
    implementation(libs.mcpKotlinSdkServer)
    implementation(libs.bundles.persistence)
    implementation(libs.bundles.sshd)
    implementation(libs.commonsCompress)

    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotestProperty)
    testImplementation(libs.mockwebserver3)

    // Lifts every Netty module above the advisory-flagged releases Ktor and the AWS SDK resolve (W-006, W-011).
    implementation(platform(libs.nettyBom))
    constraints {
        // Above what langfuse-java, client-java and json-schema-validator resolve (W-007, W-008, W-012).
        implementation(libs.jackson2Databind)
        implementation(libs.jackson3Databind)
        // Above the bcpkix client-java resolves (W-009).
        implementation(libs.bcprov)
        implementation(libs.bcpkix)
        implementation(libs.bcutil)
        // Above what the AWS SDK's apache5-client resolves (W-010).
        implementation(libs.httpclient5)
        implementation(libs.httpcore5)
        implementation(libs.httpcore5H2)
        // Above the guava client-java's Google auth client resolves (W-024).
        implementation(libs.guava)
        // Kept at the project's Kotlin version: without it Ktor and Exposed resolve a 2.3.x kotlin-reflect
        // that predates an advisory fix (W-013, W-014).
        implementation(libs.kotlinReflect)
        // Pinned for provenance, not patch level (W-003, W-004).
        implementation(libs.okhttp)
        implementation(libs.okio)
    }
}

// kotlinx-coroutines-test's virtual-time API (runCurrent, advanceTimeBy, currentTime) is
// ExperimentalCoroutinesApi; opt the tests in once instead of per class. Main code opts in per use.
tasks.named<KotlinJvmCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

// Bundles app/schemas/ as classpath resources the server serves. It stays inside the project root
// so a Docker build with its context scoped to app/ still reaches it.
tasks.processResources {
    val schemasDir = rootProject.layout.projectDirectory.dir("schemas")
    doFirst {
        val files = schemasDir.asFile.listFiles { f -> f.name.endsWith(".json") }
        require(!files.isNullOrEmpty()) {
            "Schema source directory is empty or missing: ${schemasDir.asFile}. " +
                "Expected homer-agent.schema.json / homer-config.schema.json. " +
                "If you reorganise the tree, update this path — a silent empty " +
                "bundle is what caused /api/v1/schemas/agent to 404 at runtime."
        }
    }
    from(schemasDir) {
        include("homer-agent.schema.json", "homer-config.schema.json")
        into("schemas")
    }
}

// Refreshes the authoring plugin's bundled copies of AGENT.md and the schemas; AuthoringPluginBundleTest
// fails on drift, so run it with any change to either.
tasks.register<Copy>("syncAuthoringPlugin") {
    val pluginRoot = rootProject.layout.projectDirectory.dir("../plugins/homer-authoring")
    into(pluginRoot)
    from(rootProject.layout.projectDirectory.file("AGENT.md")) {
        into("skills/homer-agent-authoring/reference")
    }
    from(rootProject.layout.projectDirectory.dir("schemas")) {
        include("homer-agent.schema.json", "homer-config.schema.json")
        into("schemas")
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    jvmArgs("-Dnet.bytebuddy.experimental=true")
    // DescriptorOpen's FFM downcalls; without this the JDK warns on the restricted methods.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
    // Parallel forks; test capture files are per worker.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)

    // Tests resolve repo files against this (homer.app.fixtures.repoRoot), under the name the
    // architecture module uses.
    val repoRoot = rootDir.parentFile
    systemProperty("homer.repoRoot", repoRoot.absolutePath)
    // The CI pipeline file: CI_CONFIG_PATH's, else the single root-level `.<forge>-ci.yml`. None
    // (the published tree) skips its guards; a missing named file or several candidates warns.
    val namedCiConfig =
        providers
            .environmentVariable("CI_CONFIG_PATH")
            .orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { repoRoot.resolve(it) }
    val rootCiConfigs =
        repoRoot
            .listFiles { f -> f.isFile && Regex("""\.[a-z]+-ci\.yml""").matches(f.name) }
            .orEmpty()
            .toList()
    val ciConfig = if (namedCiConfig != null) namedCiConfig.takeIf { it.isFile } else rootCiConfigs.singleOrNull()
    if (namedCiConfig != null && ciConfig == null) {
        logger.warn("CI_CONFIG_PATH names $namedCiConfig, which does not exist: the CI pipeline guards are skipped")
    } else if (namedCiConfig == null && rootCiConfigs.size > 1) {
        logger.warn("Several root-level CI pipeline files ${rootCiConfigs.map { it.name }}: the CI pipeline guards are skipped")
    }
    ciConfig?.let { systemProperty("homer.ciConfig", it.absolutePath) }

    // A repo file a test reads outside :app must be declared here: undeclared, a change to only that
    // file replays a cached pass.
    inputs
        .files(
            listOfNotNull(ciConfig),
            repoRoot.resolve("app/Dockerfile"),
            repoRoot.resolve("app/e2e/Dockerfile"),
            repoRoot.resolve("app/entrypoint.sh"),
            repoRoot.resolve("app/homer-runner.sh"),
            repoRoot.resolve("app/agentdebug.sh"),
            repoRoot.resolve("app/require-java4shell-fix.sh"),
            repoRoot.resolve("app/settings.gradle.kts"),
            repoRoot.resolve("app/buildSrc/settings.gradle.kts"),
            // DependencyVerificationGateTest reads these (ADR-0115). The keyrings are files, not
            // directories, so a key added to either one is part of the cache key too.
            repoRoot.resolve("app/gradle/libs.versions.toml"),
            repoRoot.resolve("app/gradle/verification-metadata.xml"),
            repoRoot.resolve("app/gradle/verification-keyring.keys"),
            repoRoot.resolve("app/buildSrc/gradle/verification-metadata.xml"),
            repoRoot.resolve("app/buildSrc/gradle/verification-keyring.keys"),
            // Every Dockerfile and gradle.properties DependencyVerificationGateTest scans (ADR-0115); build
            // outputs are skipped as in its walk, since they are other tasks' outputs.
            fileTree(repoRoot) {
                include("**/Dockerfile", "**/gradle.properties")
                exclude("**/.git/**", "**/node_modules/**", "**/build/**", "**/.gradle/**", "**/.next/**")
            },
            // Every workflow (either extension GitHub Actions accepts): DependencyVerificationGateTest
            // scans them all, so a new one is part of the cache key too.
            fileTree(repoRoot.resolve(".github/workflows")) { include("*.yml", "*.yaml") },
            fileTree(repoRoot) { include("docker-compose*.yml") },
            fileTree(repoRoot.resolve("example/agents")),
            fileTree(repoRoot.resolve("example/config")),
            fileTree(repoRoot.resolve("plugins/homer-authoring")),
            fileTree(repoRoot.resolve(".github/ISSUE_TEMPLATE")),
            repoRoot.resolve(".github/pull_request_template.md"),
            repoRoot.resolve("app/AGENT.md"),
            repoRoot.resolve("app/SERVER.md"),
            repoRoot.resolve("README.md"),
            repoRoot.resolve("DOCKER.md"),
            repoRoot.resolve("CHANGELOG.md"),
            repoRoot.resolve("SECURITY.md"),
            repoRoot.resolve("console/DEPLOYMENT.md"),
            repoRoot.resolve("docs/homer-feature-atlas.html"),
            repoRoot.resolve(".env.example"),
            // Inside :app, but not compiled into any test input.
            repoRoot.resolve("app/app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md"),
            // ConstraintsNameTheirTestsTest reads every backend guide under app/.
            fileTree(repoRoot.resolve("app")) {
                include("**/CLAUDE.md")
                exclude("**/build/**", "**/.gradle/**", "**/node_modules/**")
            },
            // The other modules' test sources, which ConstraintsNameTheirTestsTest resolves names against.
            fileTree(repoRoot.resolve("app")) {
                include("architecture/src/test/kotlin/**", "homer-runner/src/test/kotlin/**", "agentdebug/src/test/kotlin/**")
            },
            // LangGraphRunStatusServiceTest pins the state cache TTL against the console's poll interval.
            repoRoot.resolve("console/lib/hooks/use-process-lang-graph-view.ts"),
        ).withPropertyName("guardedRepoFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // DtoContractTest writes the console DTO contract here for the dto-contract check (ADR-0082).
    // Declared as an output so a test run restored from the build cache restores the file too.
    val dtoContractDir = layout.buildDirectory.dir("reports/dto-contract")
    outputs.dir(dtoContractDir).withPropertyName("dtoContract")
    systemProperty("homer.dtoContract.dir", dtoContractDir.get().asFile.absolutePath)

    // QueryPlanCapture collects production SQL here for queryPlanReport (check `query-plans`,
    // ADR-0082); emptied first, as the capture only ever appends.
    val queryPlansDir = layout.buildDirectory.dir("reports/query-plans/captured")
    outputs.dir(queryPlansDir).withPropertyName("queryPlansCapture")
    systemProperty("homer.queryPlans.dir", queryPlansDir.get().asFile.absolutePath)
    val queryPlansCaptureDir = queryPlansDir.get().asFile
    doFirst {
        queryPlansCaptureDir.deleteRecursively()
        queryPlansCaptureDir.mkdirs()
    }

    // MockCallBudget (ADR-0082, `mock-calls`) is the only autodetected extension: mockk-jvm registers its
    // own MockKExtension, which stays opt-in per class.
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    systemProperty("junit.jupiter.extensions.autodetection.include", "homer.app.fixtures.MockCallBudget")
    val mockCallsDir = layout.buildDirectory.dir("reports/mock-calls")
    outputs.dir(mockCallsDir).withPropertyName("mockCallsReport")
    systemProperty("homer.mockCalls.dir", mockCallsDir.get().asFile.absolutePath)
    val mockCallsReportDir = mockCallsDir.get().asFile
    doFirst {
        mockCallsReportDir.deleteRecursively()
        mockCallsReportDir.mkdirs()
    }
}

// Plans every statement :app:test captured and lists full scans of growing tables (ADR-0082).
tasks.register<JavaExec>("queryPlanReport") {
    description = "EXPLAIN QUERY PLAN for the SQL production code ran in :app:test"
    group = "verification"
    dependsOn(tasks.test)
    val captured = layout.buildDirectory.dir("reports/query-plans/captured")
    val report = layout.buildDirectory.file("reports/query-plans/query-plans.json")
    inputs.dir(captured).withPropertyName("captured")
    outputs.file(report).withPropertyName("report")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "homer.app.fixtures.QueryPlanReport"
    args(captured.get().asFile.absolutePath, report.get().asFile.absolutePath)
}

// Mutation testing (ADR-0082), outside `check`. `targetTests` spans every backend package, so code tested
// from another package is not reported uncovered.
pitest {
    pitestVersion = libs.versions.pitest
    junit5PluginVersion = libs.versions.pitestJunit5
    // -PpitestTargetClasses=<glob>,… narrows the scope to a weekly shard or a pull request's classes.
    targetClasses = providers.gradleProperty("pitestTargetClasses").map { it.split(",").toSet() }.orElse(setOf("homer.app.*"))
    excludedClasses = setOf("*\$\$serializer")
    targetTests = setOf("homer.app.*")
    // These fail PIT's green-suite phase but stay enforced by :app:test: MockK constructor mocks cannot be
    // instrumented, and MockCallBudgetTest needs the test task's system properties.
    excludedTestClasses =
        setOf("homer.app.MaintenanceShutdownWiringTest", "homer.app.ShutdownWiringTest", "homer.app.fixtures.MockCallBudgetTest")
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    failWhenNoMutations = false
}

application {
    // Define the Fully Qualified Name for the application main class
    mainClass = "homer.app.ApplicationKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencyCheck {
    failBuildOnCVSS = 7.0f
    // What the backend ships, like the console's `npm audit --omit=dev`: build tools (compiler,
    // detekt, ktlint) never reach production. :homer-runner runs the same check (ADR-0088).
    scanConfigurations = listOf("runtimeClasspath")
    suppressionFile = file("dependency-check-suppression.xml").absolutePath
    nvd {
        // The dependency-check project's own NVD mirror instead of the NVD API (ADR-0088). With a
        // data feed set, the plugin never calls the API, so no API key is needed.
        datafeedUrl = "https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz"
    }
}

// Runtime-dependency license gate; policy and rationale: see ADR-0056.
licenseReport {
    configurations = arrayOf("runtimeClasspath")
    // Explicit type parameters keep the runtime arrays widened (ADR-0056).
    filters = arrayOf<DependencyFilter>(LicenseBundleNormalizer())
    renderers = arrayOf<ReportRenderer>(TextReportRenderer("THIRD-PARTY-NOTICES.txt"))
    allowedLicensesFile = rootProject.layout.projectDirectory.file("config/allowed-licenses.json")
}

// Committed attributions (NOTICE, THIRD-PARTY-LICENSES.md; ADR-0056). Task actions read locals: a `doLast` reading
// a script-level property captures the build script, which the configuration cache cannot serialize.
val repoRoot = rootProject.layout.projectDirectory.dir("..")
val noticeFile = repoRoot.file("NOTICE").asFile
val inventoryFile = repoRoot.file("THIRD-PARTY-LICENSES.md").asFile
val backendRegionBegin = "<!-- BEGIN GENERATED: backend (./gradlew :app:generateThirdPartyNotices) -->"
val backendRegionEnd = "<!-- END GENERATED: backend -->"
val homerNoticeHeader =
    """
    Homer (Hub for Orchestrating Managed Execution Runs)
    Copyright 2026 Livesport s.r.o.
    """.trimIndent()
val licenseReportDirs =
    listOf(
        layout.buildDirectory.dir("reports/dependency-license"),
        project(":agentdebug").layout.buildDirectory.dir("reports/dependency-license"),
        project(":homer-runner").layout.buildDirectory.dir("reports/dependency-license"),
    ).map { it.get().asFile }
val licenseReportFiles = licenseReportDirs.map { it.resolve(ThirdPartyNotices.REPORT_FILE_NAME) }

tasks.register("generateThirdPartyNotices") {
    group = "documentation"
    description = "Regenerates NOTICE and the backend region of THIRD-PARTY-LICENSES.md."
    dependsOn(":app:checkLicense", ":agentdebug:checkLicense", ":homer-runner:checkLicense")
    inputs.files(licenseReportFiles)
    outputs.files(noticeFile, inventoryFile)

    val reportDirs = licenseReportDirs
    val notice = noticeFile
    val inventory = inventoryFile
    val regionBegin = backendRegionBegin
    val regionEnd = backendRegionEnd
    val header = homerNoticeHeader
    doLast {
        val rendered = ThirdPartyNotices.render(reportDirs, inventory.readText(), regionBegin, regionEnd, header)
        notice.writeText(rendered.notice)
        inventory.writeText(rendered.inventoryDocument)
    }
}

tasks.register("checkThirdPartyNotices") {
    group = "verification"
    description = "Fails if NOTICE or the backend region of THIRD-PARTY-LICENSES.md is out of date."
    dependsOn(":app:checkLicense", ":agentdebug:checkLicense", ":homer-runner:checkLicense")
    inputs.files(licenseReportFiles)
    inputs.files(noticeFile, inventoryFile)
    outputs.dir(layout.buildDirectory.dir("third-party-notices"))

    val reportDirs = licenseReportDirs
    val notice = noticeFile
    val inventory = inventoryFile
    val regionBegin = backendRegionBegin
    val regionEnd = backendRegionEnd
    val header = homerNoticeHeader
    val renderedDir =
        layout.buildDirectory
            .dir("third-party-notices")
            .get()
            .asFile
    doLast {
        val committedInventory = inventory.readText()
        val rendered = ThirdPartyNotices.render(reportDirs, committedInventory, regionBegin, regionEnd, header)
        renderedDir.mkdirs()
        renderedDir.resolve("NOTICE").writeText(rendered.notice)
        renderedDir.resolve("THIRD-PARTY-LICENSES.md").writeText(rendered.inventoryDocument)
        val stale =
            listOfNotNull(
                "NOTICE".takeIf { rendered.notice != notice.readText() },
                "THIRD-PARTY-LICENSES.md (backend region)".takeIf { rendered.inventoryDocument != committedInventory },
            )
        require(stale.isEmpty()) {
            "Out of date with the dependency license report: ${stale.joinToString(" and ")}. " +
                "Run ./gradlew :app:generateThirdPartyNotices and commit the result."
        }
    }
}

tasks.check {
    dependsOn("checkLicense", "checkThirdPartyNotices")
}

tasks.shadowJar {
    archiveBaseName.set("homer")
    archiveClassifier.set("all")
    archiveVersion.set("0.0.2")
    manifest {
        attributes["Main-Class"] = "homer.app.ApplicationKt"
        // Grants `java -jar` the native access DescriptorOpen's FFM downcalls need, whatever the launcher.
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
    // Exclude signature files to avoid conflicts
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    from(tasks.named("generateLicenseReport")) {
        include("THIRD-PARTY-NOTICES.txt")
        into("META-INF")
    }
}
