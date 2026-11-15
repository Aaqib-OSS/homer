package homer.app.di

import homer.app.infra.DefaultLangfuseClient
import homer.app.infra.LangfuseClient
import homer.app.infra.LlmCostResolver
import homer.app.infra.OtelTelemetryService
import homer.app.infra.TelemetryService
import homer.app.models.AppConfig
import homer.app.services.CooldownGate
import homer.app.services.CostMeter
import homer.app.services.LlmTelemetryService
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/**
 * Tracing, the Langfuse client and the cost accounting built on them (ADR-0035, ADR-0061).
 * Split out of [appModule] so each subject's bindings stay in one file (root CLAUDE.md §2);
 * the boot loads it beside the other subject modules.
 */
internal val telemetryModule =
    module {
        single<TelemetryService> {
            val appConfig = get<AppConfig>()
            OtelTelemetryService(appConfig.langfuse, appConfig.openTelemetryExport)
        }

        single<LangfuseClient> {
            DefaultLangfuseClient(get<AppConfig>().langfuse, get())
        }

        single {
            LlmTelemetryService(
                telemetryService = get<TelemetryService>(),
                logger = get { parametersOf("homer.app.services.LlmTelemetryService") },
            )
        }

        single {
            LlmCostResolver(get<AppConfig>().llmPrice?.sessionSpend)
        }

        single {
            CostMeter(repository = get(), limits = get<AppConfig>().costLimits)
        }

        single {
            CooldownGate(repository = get())
        }
    }
