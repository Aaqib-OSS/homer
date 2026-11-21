package homer.app.infra

import homer.app.models.CostSource
import homer.app.models.ResolvedCost
import homer.app.models.SESSION_ID_PATTERN
import homer.app.models.SessionSpendConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException
import java.nio.channels.UnresolvedAddressException

/**
 * Prices one Claude attempt from the session-spend endpoint, degrading any failure to the CLI
 * figure with a reason; requests are bounded by [SessionSpendConfig.timeoutMs] and [MAX_BODY_BYTES].
 * Polls on [DEFAULT_POLL_OFFSETS_MS] when `callsPath` is set (ADR-0061, ADR-0070).
 */
class LlmCostResolver(
    private val config: SessionSpendConfig?,
    engine: HttpClientEngine = CIO.create(),
    private val pollOffsetsMs: List<Long> = DEFAULT_POLL_OFFSETS_MS,
) {
    private val logger = LoggerFactory.getLogger(LlmCostResolver::class.java)

    val configured: Boolean get() = config != null

    private val client: HttpClient? =
        config?.let { cfg ->
            HttpClient(engine) {
                expectSuccess = false
                // A followed redirect would send the spend-API credential to its target. See ADR-0061.
                followRedirects = false
                install(HttpTimeout) {
                    requestTimeoutMillis = cfg.timeoutMs
                    connectTimeoutMillis = cfg.timeoutMs
                    socketTimeoutMillis = cfg.timeoutMs
                }
            }
        }

    private val pricing: SessionSpendPricing? = config?.let(::SessionSpendPricing)

    suspend fun resolve(
        sessionId: String?,
        cliCostUsd: Double,
        expectedCalls: Int?,
        startedAtMs: Long? = null,
        endedAtMs: Long? = null,
    ): ResolvedCost {
        val responsePricing = pricing ?: return ResolvedCost(cliCostUsd, CostSource.CLI)
        val http = client ?: return ResolvedCost(cliCostUsd, CostSource.CLI)
        if (sessionId == null) return fallback(cliCostUsd, "no session id in output")
        // The id comes from a file the agent wrote: gate its charset before it reaches the URL.
        // See ADR-0061, ADR-0047 rule 2.
        if (!SESSION_ID_PATTERN.matches(sessionId)) return fallback(cliCostUsd, "invalid session id")
        val url = responsePricing.config.url.replace(SessionSpendConfig.SESSION_ID_PLACEHOLDER, sessionId.encodeURLPathPart())

        return pollForCost(cliCostUsd, expectedCalls) { attempt(responsePricing, http, url, expectedCalls, startedAtMs, endedAtMs) }
    }

    /**
     * Runs [attemptOnce] on the poll schedule until a figure is complete or settled, and
     * charges the best partial figure — or the CLI's — once the schedule runs out.
     */
    private suspend fun pollForCost(
        cliCostUsd: Double,
        expectedCalls: Int?,
        attemptOnce: suspend () -> SessionSpendOutcome,
    ): ResolvedCost {
        // Without a completeness signal there is nothing to poll for: one attempt, no waiting.
        // See ADR-0061.
        val offsets = pollSchedule()
        var partial: SessionSpendOutcome.Partial? = null
        var previous: SessionSpendOutcome.Partial? = null
        var lastFailure: String? = null
        for ((index, offset) in offsets.withIndex()) {
            // A plain delay: cancellation while waiting propagates, never priced. See ADR-0061.
            if (index > 0) delay(offset - offsets[index - 1])
            when (val outcome = attemptOnce()) {
                is SessionSpendOutcome.Complete -> {
                    return ResolvedCost(outcome.cost, CostSource.SESSION)
                }

                is SessionSpendOutcome.Partial -> {
                    // No call count to reach: a reading the previous one confirms is settled. See ADR-0070.
                    if (outcome.settles(expectedCalls, previous)) {
                        return ResolvedCost(outcome.cost, CostSource.SESSION)
                    }
                    previous = outcome
                    // The best reading, not the latest: a later one may report fewer calls. See ADR-0061.
                    if (outcome.betterThan(partial)) partial = outcome
                    logRepoll(outcome, expectedCalls)
                }

                is SessionSpendOutcome.Failed -> {
                    lastFailure = outcome.reason
                }
            }
        }
        return chargeBestPartial(cliCostUsd, expectedCalls, partial, lastFailure)
    }

    /** The poll offsets [pollForCost] runs on: a single immediate attempt without a completeness signal. */
    private fun pollSchedule(): List<Long> = if (pricing?.pollsForCompleteness == true) pollOffsetsMs else listOf(0L)

    private fun logRepoll(
        outcome: SessionSpendOutcome.Partial,
        expectedCalls: Int?,
    ) {
        logger.debug(
            "llmPrice.sessionSpend has ingested {} of {} calls of the session; re-polling",
            outcome.calls,
            expectedCalls ?: "an unknown number of",
        )
    }

    /** What [pollForCost] charges once the schedule ran out without a complete or settled figure. */
    private fun chargeBestPartial(
        cliCostUsd: Double,
        expectedCalls: Int?,
        partial: SessionSpendOutcome.Partial?,
        lastFailure: String?,
    ): ResolvedCost {
        // A partial figure is charged, marked incomplete, in preference to the CLI's. See ADR-0061.
        val best = partial ?: return fallback(cliCostUsd, lastFailure ?: "lookup failed")
        return chargeIncomplete(best, expectedCalls)
    }

    private fun chargeIncomplete(
        best: SessionSpendOutcome.Partial,
        expectedCalls: Int?,
    ): ResolvedCost {
        val reason =
            if (expectedCalls == null) {
                "incomplete: figure not stable across polls"
            } else {
                "incomplete: ${best.calls} of $expectedCalls calls ingested"
            }
        logger.warn("llmPrice.sessionSpend figure is incomplete ({}); charging it anyway", reason)
        return ResolvedCost(best.cost, CostSource.SESSION, reason)
    }

    @Suppress("SuspendFunSwallowedCancellation") // failureFor rethrows a caller's cancellation
    private suspend fun attempt(
        pricing: SessionSpendPricing,
        http: HttpClient,
        url: String,
        expectedCalls: Int?,
        startedAtMs: Long?,
        endedAtMs: Long?,
    ): SessionSpendOutcome {
        val cfg = pricing.config
        // `execute`'s block is not inlined, so its early exits are signalled through this variable.
        var httpFailureReason: String? = null
        val rawBody: String? =
            try {
                // The timeout bound must not depend on the engine's own timeout plugin. See ADR-0061.
                withTimeout(cfg.timeoutMs) {
                    // prepareGet + execute releases the response on every exit from the block.
                    http
                        .prepareGet(url) {
                            cfg.headers.forEach { (k, v) -> header(k, v) }
                        }.execute { response ->
                            if (!response.status.isSuccess()) {
                                httpFailureReason = "http ${response.status.value}"
                                return@execute null
                            }
                            val bytes = response.bodyAsChannel().readRemaining(MAX_BODY_BYTES + 1).readByteArray()
                            if (bytes.size > MAX_BODY_BYTES) {
                                httpFailureReason = "response over 1 MiB"
                                return@execute null
                            }
                            bytes.decodeToString()
                        }
                }
            } catch (e: Exception) {
                return failureFor(e)
            }
        val body: String = rawBody ?: return SessionSpendOutcome.Failed(httpFailureReason ?: "lookup failed")
        return pricing.price(body, expectedCalls, startedAtMs, endedAtMs)
    }

    /**
     * Maps an exception from the request-and-read round to its fixed-vocabulary reason, or
     * rethrows it when it is a caller's cancellation. The `is` checks run in the order the
     * catch clauses they replace did.
     */
    private suspend fun failureFor(e: Exception): SessionSpendOutcome.Failed =
        when (e) {
            // Our own timeout leaves the coroutine active; a caller's does not and propagates. See ADR-0061.
            is TimeoutCancellationException -> {
                if (!currentCoroutineContext().isActive) throw e
                SessionSpendOutcome.Failed("timeout")
            }

            is HttpRequestTimeoutException, is SocketTimeoutException, is ConnectTimeoutException -> {
                SessionSpendOutcome.Failed("timeout")
            }

            is UnresolvedAddressException, is java.io.IOException -> {
                SessionSpendOutcome.Failed("connection failed")
            }

            // HttpTimeout may cancel with a bare CancellationException: only a caller's propagates.
            is CancellationException -> {
                if (currentCoroutineContext().isActive) SessionSpendOutcome.Failed("timeout") else throw e
            }

            // Cost is metadata, never a reason to fail the run; an Error is not caught and propagates.
            else -> {
                SessionSpendOutcome.Failed("lookup failed")
            }
        }

    private fun fallback(
        cliCostUsd: Double,
        reason: String,
    ): ResolvedCost {
        // Reason strings are fixed vocabulary + status code: never the URL, body or headers.
        logger.warn("llmPrice.sessionSpend lookup failed ({}); charging the CLI-reported cost", reason)
        return ResolvedCost(cliCostUsd, CostSource.CLI, reason)
    }

    companion object {
        /**
         * Attempt offsets after the CLI exit, when `callsPath` is set: now, then 5, 15 and
         * 30 s. Fixed rather than configurable — see ADR-0061.
         */
        val DEFAULT_POLL_OFFSETS_MS = listOf(0L, 5_000L, 15_000L, 30_000L)
        val DISABLED = LlmCostResolver(null, engine = CIO.create())
        const val MAX_BODY_BYTES = 1024 * 1024L
    }
}
