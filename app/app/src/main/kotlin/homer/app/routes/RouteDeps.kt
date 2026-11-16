package homer.app.routes

import homer.app.executors.ParallelRunsManager
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.infra.AgentInputResolver
import homer.app.infra.FileOperations
import homer.app.infra.PasswordVerifier
import homer.app.infra.TelemetryService
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.runners.RunnerRegistry
import homer.app.runners.kubernetes.SweepStatus
import homer.app.services.AgentFilesService
import homer.app.services.AgentRunStarter
import homer.app.services.AgentsService
import homer.app.services.ArtifactService
import homer.app.services.Authorizer
import homer.app.services.ContinuationService
import homer.app.services.CostMeter
import homer.app.services.CronSchedulerService
import homer.app.services.DispatchGroupService
import homer.app.services.DispatchService
import homer.app.services.LangGraphInspectService
import homer.app.services.LangGraphRunStatusService
import homer.app.services.MaintenanceModeService
import homer.app.services.McpToolFactory
import homer.app.services.ProcessManager
import homer.app.services.QuestionsMcpToolFactory
import homer.app.services.QuestionsService
import homer.app.services.ResultTokenService
import homer.app.services.RunMessagesService
import java.util.concurrent.atomic.AtomicBoolean

/*
 * One dependency class per route file: what that file's handlers need, and nothing else, built from
 * the wiring root ApiDeps by the `xxxDeps()` builder below it. A handler still reads its fields
 * unqualified through `with(deps) { ... }`, so a route body names only its own file's surface.
 *
 * Field names match ApiDeps, so moving a field between the two is a mechanical change.
 */

/** What `CallbackRoutes.kt` needs: the token-scoped services behind the `/api/v1/callback` endpoints. */
internal data class CallbackDeps(
    val processManager: ProcessManager,
    val agentsService: AgentsService,
    val artifactService: ArtifactService,
    val fileOperations: FileOperations,
    val questionsService: QuestionsService,
    val runMessagesService: RunMessagesService,
    val dispatchService: DispatchService,
    val dispatchGroupService: DispatchGroupService,
    val continuationService: ContinuationService,
    val resultTokenService: ResultTokenService,
)

/** What `AgentRoutes.kt` needs: the agent catalog, its files, tooling, cron fires and debug runs. */
internal data class AgentRouteDeps(
    val authorizer: Authorizer,
    val serverId: String,
    val processManager: ProcessManager,
    val agentsService: AgentsService,
    val agentFilesService: AgentFilesService,
    val langGraphInspectService: LangGraphInspectService,
    val parallelRunsManager: ParallelRunsManager,
    val agentInputResolver: AgentInputResolver,
    val telemetryService: TelemetryService,
    val processFactory: ProcessFactory,
    val processExecutor: ProcessExecutor,
    val cronSchedulerService: CronSchedulerService,
    val maintenanceModeService: MaintenanceModeService,
)

/** What `ProcessRoutes.kt` needs: run status and kill, the process list and its artifacts. */
internal data class ProcessRouteDeps(
    val config: AppConfig,
    val authorizer: Authorizer,
    val processManager: ProcessManager,
    val agentsService: AgentsService,
    val artifactService: ArtifactService,
    val fileOperations: FileOperations,
    val questionsService: QuestionsService,
)

/** What `RunRoutes.kt` needs: starting, retrying, resuming and introspecting a run. */
internal data class RunRouteDeps(
    val authorizer: Authorizer,
    val serverId: String,
    val processManager: ProcessManager,
    val agentsService: AgentsService,
    val langGraphRunStatusService: LangGraphRunStatusService,
    val agentInputResolver: AgentInputResolver,
    val agentRunStarter: AgentRunStarter,
    val telemetryService: TelemetryService,
    val processFactory: ProcessFactory,
    val processExecutor: ProcessExecutor,
    val maintenanceModeService: MaintenanceModeService,
    val costMeter: CostMeter,
)

/** What `DispatchContinuationRoutes.kt` needs: listing, reading and cancelling dispatches and continuations. */
internal data class DispatchContinuationDeps(
    val authorizer: Authorizer,
    val agentsService: AgentsService,
    val dispatchService: DispatchService,
    val continuationService: ContinuationService,
)

/** What `ServerRoutes.kt` needs: server state, the runner registry, cost statistics and maintenance. */
internal data class ServerRouteDeps(
    val config: AppConfig,
    val authorizer: Authorizer,
    val processManager: ProcessManager,
    val agentsService: AgentsService,
    val maintenanceModeService: MaintenanceModeService,
    val sweepStatus: SweepStatus,
    val runnerRegistry: RunnerRegistry,
    val costMeter: CostMeter,
)

/** What `MaintenanceRoutes.kt` needs: the admin maintenance flag. */
internal data class MaintenanceDeps(
    val authorizer: Authorizer,
    val maintenanceModeService: MaintenanceModeService,
)

/** What `McpRoutes.kt` needs: the two MCP tool factories and the callback-token registry. */
internal data class McpRouteDeps(
    val mcpToolFactory: McpToolFactory,
    val questionsService: QuestionsService,
    val questionsMcpToolFactory: QuestionsMcpToolFactory,
)

/** What `RunMessageRoutes.kt` needs: a run's operator-message inbox. */
internal data class RunMessageDeps(
    val authorizer: Authorizer,
    val processManager: ProcessManager,
    val runMessagesService: RunMessagesService,
)

/** What `QuestionRoutes.kt` needs: the operator's question inbox and its answer-triggered dispatch. */
internal data class QuestionRouteDeps(
    val authorizer: Authorizer,
    val processManager: ProcessManager,
    val agentsService: AgentsService,
    val questionsService: QuestionsService,
    val dispatchService: DispatchService,
)

/** What `UnsecuredRoutes.kt` needs: the drain flag and the password scheme the session handshake uses. */
internal data class UnsecuredDeps(
    val isDraining: AtomicBoolean,
    val passwordScheme: AuthConfig.Password?,
    val passwordVerifier: PasswordVerifier,
)

internal fun ApiDeps.callbackDeps(): CallbackDeps =
    CallbackDeps(
        processManager = processManager,
        agentsService = agentsService,
        artifactService = artifactService,
        fileOperations = fileOperations,
        questionsService = questionsService,
        runMessagesService = runMessagesService,
        dispatchService = dispatchService,
        dispatchGroupService = dispatchGroupService,
        continuationService = continuationService,
        resultTokenService = resultTokenService,
    )

internal fun ApiDeps.agentRouteDeps(): AgentRouteDeps =
    AgentRouteDeps(
        authorizer = authorizer,
        serverId = serverId,
        processManager = processManager,
        agentsService = agentsService,
        agentFilesService = agentFilesService,
        langGraphInspectService = langGraphInspectService,
        parallelRunsManager = parallelRunsManager,
        agentInputResolver = agentInputResolver,
        telemetryService = telemetryService,
        processFactory = processFactory,
        processExecutor = processExecutor,
        cronSchedulerService = cronSchedulerService,
        maintenanceModeService = maintenanceModeService,
    )

internal fun ApiDeps.processRouteDeps(): ProcessRouteDeps =
    ProcessRouteDeps(
        config = config,
        authorizer = authorizer,
        processManager = processManager,
        agentsService = agentsService,
        artifactService = artifactService,
        fileOperations = fileOperations,
        questionsService = questionsService,
    )

internal fun ApiDeps.runRouteDeps(): RunRouteDeps =
    RunRouteDeps(
        authorizer = authorizer,
        serverId = serverId,
        processManager = processManager,
        agentsService = agentsService,
        langGraphRunStatusService = langGraphRunStatusService,
        agentInputResolver = agentInputResolver,
        agentRunStarter = agentRunStarter,
        telemetryService = telemetryService,
        processFactory = processFactory,
        processExecutor = processExecutor,
        maintenanceModeService = maintenanceModeService,
        costMeter = costMeter,
    )

internal fun ApiDeps.dispatchContinuationDeps(): DispatchContinuationDeps =
    DispatchContinuationDeps(
        authorizer = authorizer,
        agentsService = agentsService,
        dispatchService = dispatchService,
        continuationService = continuationService,
    )

internal fun ApiDeps.serverRouteDeps(): ServerRouteDeps =
    ServerRouteDeps(
        config = config,
        authorizer = authorizer,
        processManager = processManager,
        agentsService = agentsService,
        maintenanceModeService = maintenanceModeService,
        sweepStatus = sweepStatus,
        runnerRegistry = runnerRegistry,
        costMeter = costMeter,
    )

internal fun ApiDeps.maintenanceDeps(): MaintenanceDeps =
    MaintenanceDeps(
        authorizer = authorizer,
        maintenanceModeService = maintenanceModeService,
    )

internal fun ApiDeps.mcpRouteDeps(): McpRouteDeps =
    McpRouteDeps(
        mcpToolFactory = mcpToolFactory,
        questionsService = questionsService,
        questionsMcpToolFactory = questionsMcpToolFactory,
    )

internal fun ApiDeps.runMessageDeps(): RunMessageDeps =
    RunMessageDeps(
        authorizer = authorizer,
        processManager = processManager,
        runMessagesService = runMessagesService,
    )

internal fun ApiDeps.questionRouteDeps(): QuestionRouteDeps =
    QuestionRouteDeps(
        authorizer = authorizer,
        processManager = processManager,
        agentsService = agentsService,
        questionsService = questionsService,
        dispatchService = dispatchService,
    )

internal fun ApiDeps.unsecuredDeps(): UnsecuredDeps =
    UnsecuredDeps(
        isDraining = isDraining,
        passwordScheme = passwordScheme,
        passwordVerifier = passwordVerifier,
    )
