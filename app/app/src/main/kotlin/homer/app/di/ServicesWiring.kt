package homer.app.di

import homer.app.executors.llm.ClaudeHarness
import homer.app.executors.llm.CodexHarness
import homer.app.executors.llm.OpencodeHarness
import homer.app.infra.AgentInputResolver
import homer.app.infra.GraphStateRunner
import homer.app.infra.ProcessGraphStateRunner
import homer.app.infra.QuestionsWorkspaceSetup
import homer.app.infra.TextCompiler
import homer.app.models.AppConfig
import homer.app.models.RunnerConfig
import homer.app.runners.RunnerRegistry
import homer.app.runners.local.LocalRunner
import homer.app.services.AgentFilesService
import homer.app.services.AgentsService
import homer.app.services.ArtifactService
import homer.app.services.ContinuationService
import homer.app.services.ContinuationSweepService
import homer.app.services.CronSchedulerService
import homer.app.services.DispatchSweepService
import homer.app.services.GraphInspectRunner
import homer.app.services.LangGraphInspectService
import homer.app.services.LangGraphRunStatusService
import homer.app.services.LlmEventStreamService
import homer.app.services.LogTailService
import homer.app.services.MaintenanceModeService
import homer.app.services.McpToolFactory
import homer.app.services.ProcessGraphInspectRunner
import homer.app.services.QuestionsMcpToolFactory
import homer.app.services.QuestionsService
import homer.app.services.ResultTokenService
import homer.app.services.RunMessagesService
import homer.app.services.StartupReconciliation
import homer.app.services.WorkspacePurgeService
import homer.app.services.persistence.ServerMetaRepository
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import kotlin.io.path.Path

/**
 * The Services layer's wiring: agent loading and inspection, the maintenance gate, the
 * questions/messages/dispatch/continuation stack with its sweeps, the MCP factories and the
 * remaining long-running services. Split out of [appModule] so each subject's bindings stay in
 * one file (root CLAUDE.md §2); the boot loads it beside the other subject modules.
 */
internal val servicesModule =
    module {
        single {
            val appConfig = get<AppConfig>()
            AgentsService(
                agentsSearchPath = appConfig.agentsSearchPath,
                logger = get { parametersOf("homer.app.services.AgentsService") },
                broadcaster = get(),
                maxConfigFileBytes = appConfig.server.maxConfigFileBytes,
                inputSchemaValidator = get(),
                inputResolver = get(),
                runnerAliases = setOf(LocalRunner.ALIAS) + get<AppConfig>().runners.keys,
                agentSysEnvNames = appConfig.agentSysEnvNames,
                runnerTagSets = {
                    val cfg = get<AppConfig>()
                    buildList {
                        if (cfg.server.localRunner.tags
                                .isNotEmpty()
                        ) {
                            add(
                                cfg.server.localRunner.tags
                                    .toSet(),
                            )
                        }
                        cfg.runners.values.forEach { r ->
                            when (r) {
                                is RunnerConfig.Kubernetes -> {
                                    if (r.tags.isNotEmpty()) add(r.tags.toSet())
                                }

                                is RunnerConfig.Ssh -> {
                                    if (r.tags.isNotEmpty()) add(r.tags.toSet())
                                }

                                is RunnerConfig.External -> {
                                    r.allowedTags?.let { add(it.toSet()) }
                                }
                            }
                        }
                        get<RunnerRegistry>().externalDomains.forEach { addAll(it.instanceTagSets()) }
                    }
                },
            )
        }

        single {
            AgentInputResolver(
                inputSchemaValidator = get(),
            )
        }

        single {
            val appConfig = get<AppConfig>()
            AgentFilesService(
                agentsService = get(),
                logger = get { parametersOf("homer.app.services.AgentFilesService") },
                maxConfigFileBytes = appConfig.server.maxConfigFileBytes,
            )
        }

        single<GraphInspectRunner> { ProcessGraphInspectRunner() }

        single { LangGraphInspectService(runner = get()) }

        single<GraphStateRunner> { ProcessGraphStateRunner() }

        single {
            LangGraphRunStatusService(
                fileOperations = get(),
                stateRunner = get(),
                appConfig = get(),
                continuationRepository = get(),
                processManager = get(),
            )
        }

        single {
            val repository = get<ServerMetaRepository>()
            // The persisted flag is read once, blocking, while the service is built — before
            // traffic, after migrate(). See ADR-0020, ADR-0092.
            MaintenanceModeService(
                repository = repository,
                config = get<AppConfig>().server.maintenance,
                initialEnabled = repository.readMaintenanceMode(),
                logger = get { parametersOf("homer.app.services.MaintenanceModeService") },
                // The one shutdown flag: a start during the drain is refused (or admitted from a
                // live parent) instead of parked. See ADR-0102.
                isDraining = homer.app.isDraining::get,
            )
        }

        single {
            StartupReconciliation(
                processRepository = get(),
                questionsService = get(),
                runMessagesService = get<RunMessagesService>(),
                dispatchService = get(),
                continuationService = get(),
                hookService = get(),
                agentsService = get(),
                logger = get { parametersOf("homer.app.services.StartupReconciliation") },
            )
        }

        single {
            McpToolFactory(
                agentsService = get(),
                authorizer = get(),
                agentInputResolver = get(),
                agentRunStarter = get(),
                processManager = get(),
                artifactService = get(),
                processEventBroadcaster = get(),
                mcpConfig = get<AppConfig>().server.mcp,
                logger = get { parametersOf("homer.app.services.McpToolFactory") },
            )
        }

        single {
            QuestionsService(
                repository = get(),
                logger = get { parametersOf("homer.app.services.QuestionsService") },
                broadcaster = get(),
                config = get<AppConfig>().server.questions,
            )
        }

        single {
            RunMessagesService(
                repository = get(),
                broadcaster = get(),
                config = get<AppConfig>().server.messages,
                logger = get { parametersOf("homer.app.services.RunMessagesService") },
            )
        }

        single { buildDispatchGroupService() }

        single { buildDispatchService() }

        single {
            DispatchSweepService(
                dispatchService = get(),
                intervalSec = get<AppConfig>().server.dispatch.sweepIntervalSec,
                logger = get { parametersOf("homer.app.services.DispatchSweepService") },
            )
        }

        single {
            ContinuationService(
                repository = get(),
                questionsService = get(),
                agentsService = get(),
                authorizer = get(),
                agentRunStarter = get(),
                agentInputResolver = get(),
                processManager = get(),
                dispatchService = get(),
                resultTokenService = get(),
                logger = get { parametersOf("homer.app.services.ContinuationService") },
                config = get<AppConfig>().server.dispatch,
                fileOperations = get(),
            )
        }

        single {
            ResultTokenService(
                repository = get(),
                logger = get { parametersOf("homer.app.services.ResultTokenService") },
                config = get<AppConfig>().server.dispatch,
            )
        }

        single {
            ContinuationSweepService(
                continuationService = get(),
                resultTokenService = get(),
                hookService = get(),
                intervalSec = get<AppConfig>().server.dispatch.sweepIntervalSec,
                logger = get { parametersOf("homer.app.services.ContinuationSweepService") },
            )
        }

        single {
            QuestionsWorkspaceSetup(mcpEnabled = get<AppConfig>().server.mcp.enabled)
        }

        single {
            QuestionsMcpToolFactory(
                questionsService = get(),
                questionsConfig = get<AppConfig>().server.questions,
                dispatchService = get(),
                dispatchGroupService = get(),
                runMessagesService = get(),
                logger = get { parametersOf("homer.app.services.QuestionsMcpToolFactory") },
            )
        }

        single {
            LogTailService(
                processManager = get(),
                logger = get { parametersOf("homer.app.services.LogTailService") },
            )
        }

        single {
            LlmEventStreamService(
                processManager = get(),
                // Translation reads no adapter config, so bare adapters suffice here.
                harnesses = listOf(ClaudeHarness(), CodexHarness(), OpencodeHarness()).associateBy { it.id },
                logger = get { parametersOf("homer.app.services.LlmEventStreamService") },
                pollIntervalMs =
                    get<AppConfig>()
                        .server.claudeStreamPollMs
                        .coerceIn(50, 2000)
                        .toLong(),
            )
        }

        single {
            val appConfig = get<AppConfig>()
            ArtifactService(
                processManager = get(),
                fileOperations = get(),
                logger = get { parametersOf("homer.app.services.ArtifactService") },
                maxArtifactBytes = appConfig.server.maxArtifactBytes,
            )
        }

        single {
            CronSchedulerService(
                agentsService = get(),
                agentRunStarter = get(),
                agentInputResolver = get(),
                maintenanceMode = get(),
                logger = get { parametersOf("homer.app.services.CronSchedulerService") },
            )
        }

        single {
            val config = get<AppConfig>()
            WorkspacePurgeService(
                processManager = get(),
                questionsService = get(),
                workspaceRoot = Path(get<TextCompiler>().replaceArguments(config.workspaceRootPath, config.globalEnv)),
                maxRetainedArtifactBytes = config.server.maxRetainedArtifactBytes,
                logger = get { parametersOf("homer.app.services.WorkspacePurgeService") },
            )
        }
    }
