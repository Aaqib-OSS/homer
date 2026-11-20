package homer.app.services

import homer.app.exceptions.CostCapExceededException
import homer.app.exceptions.ValidationException
import homer.app.executors.llm.CostRecorder
import homer.app.models.CostLimits
import homer.app.services.persistence.CostRepository
import org.slf4j.LoggerFactory
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Soft spend caps over the durable `cost_entries` ledger: a 60 s global window ([CostLimits.globalUsdPerMin]) and
 * a 24 h per-agent one ([CostLimits.perAgentUsdPerDay]). [checkCaps] runs before a run, [recordCost] after one.
 * See ADR-0035, ADR-0007.
 */
@OptIn(ExperimentalTime::class)
class CostMeter(
    private val repository: CostRepository,
    private val limits: CostLimits = CostLimits(),
    private val clock: Clock = Clock.System,
) : CostRecorder {
    private data class Overshoot(
        val capName: String,
        val cap: Double,
        val current: Double,
    )

    private val overshootTotal = AtomicLong(0)

    @Volatile
    private var lastOvershootLogAtMs = 0L

    /**
     * The [recordCost] calls that pushed a window past its cap since this process started; non-zero under
     * concurrency, since [checkCaps] reserves no budget. See ADR-0007, ADR-0035.
     */
    val overshootCount: Long
        get() = overshootTotal.get()

    /**
     * Throws [CostCapExceededException] when the global or [agentName]'s window is already at its cap.
     * A pass reserves no budget, so concurrent callers can push a window over together. See ADR-0007.
     */
    suspend fun checkCaps(agentName: String) {
        val now = clock.now().epochSeconds
        limits.globalUsdPerMin?.let { cap ->
            val current = repository.sumGlobalSince(now - GLOBAL_WINDOW_SECONDS)
            if (current >= cap) {
                throw CostCapExceededException("globalUsdPerMin", cap, current)
            }
        }
        limits.perAgentUsdPerDay[agentName]?.let { cap ->
            val current = repository.sumAgentSince(agentName, now - AGENT_WINDOW_SECONDS)
            if (current >= cap) {
                throw CostCapExceededException("perAgentUsdPerDay[$agentName]", cap, current)
            }
        }
    }

    /**
     * Records a successful Claude run's cost into the ledger. Zero or negative
     * deltas are no-ops to avoid corrupting the statistics with rounding
     * artifacts from the upstream JSON.
     */
    override suspend fun recordCost(
        agentName: String,
        costUsd: Double,
    ) {
        if (costUsd <= 0.0) return
        val now = clock.now().epochSeconds
        repository.insert(agentName, now, costUsd)
        // checkCaps reserves nothing, so concurrent runs, or one over-budget run, can push a window past its cap.
        var overshoot: Overshoot? = null
        limits.perAgentUsdPerDay[agentName]?.let { cap ->
            val sum = repository.sumAgentSince(agentName, now - AGENT_WINDOW_SECONDS)
            if (sum > cap) overshoot = Overshoot("perAgentUsdPerDay[$agentName]", cap, sum)
        }
        // Global is the system-wide signal; prefer it when both trip.
        limits.globalUsdPerMin?.let { cap ->
            val sum = repository.sumGlobalSince(now - GLOBAL_WINDOW_SECONDS)
            if (sum > cap) overshoot = Overshoot("globalUsdPerMin", cap, sum)
        }
        overshoot?.let { noteOvershoot(it) }
    }

    private fun noteOvershoot(overshoot: Overshoot) {
        val total = overshootTotal.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - lastOvershootLogAtMs >= OVERSHOOT_LOG_WINDOW_MS) {
            lastOvershootLogAtMs = now
            val pctOver = ((overshoot.current - overshoot.cap) / overshoot.cap) * 100.0
            log.warn(
                "CostMeter soft cap exceeded: {} window is {} vs cap {} ({}% over, {} overshoot(s) total). " +
                    "checkCaps is pre-flight and reserves no budget, so runs can push a window past its cap; " +
                    "see ADR-0007.",
                overshoot.capName,
                "%.4f".format(Locale.ROOT, overshoot.current),
                "%.4f".format(Locale.ROOT, overshoot.cap),
                "%.1f".format(Locale.ROOT, pctOver),
                total,
            )
        }
    }

    /** Snapshots the current windows for the cost API. */
    suspend fun snapshot(): Snapshot {
        val now = clock.now().epochSeconds
        return Snapshot(
            globalUsdLast60s = repository.sumGlobalSince(now - GLOBAL_WINDOW_SECONDS),
            globalCapUsdPerMin = limits.globalUsdPerMin,
            perAgentUsdLast24h = repository.sumPerAgentSince(now - AGENT_WINDOW_SECONDS),
            perAgentCapUsdPerDay = limits.perAgentUsdPerDay,
            snapshotAtSec = now,
        )
    }

    /**
     * Per-agent spend for one UTC month. [yearMonth] is "YYYY-MM"; null
     * resolves to the current UTC month. Malformed input throws
     * [ValidationException] (mapped to 400 at the HTTP boundary).
     */
    suspend fun monthly(yearMonth: String?): MonthlyCosts {
        val ym = parseOrCurrentMonth(yearMonth)
        val start = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val end =
            ym
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toEpochSecond()
        val perAgent = repository.sumPerAgentBetween(start, end)
        return MonthlyCosts(
            month = ym.toString(),
            perAgentUsd = perAgent,
            totalUsd = perAgent.values.sum(),
            availableMonths = repository.availableMonths(),
        )
    }

    /** All-time per-agent spend over the full ledger. */
    suspend fun totals(): TotalCosts {
        val perAgent = repository.totalPerAgent()
        return TotalCosts(perAgentUsd = perAgent, totalUsd = perAgent.values.sum())
    }

    private fun parseOrCurrentMonth(yearMonth: String?): YearMonth {
        if (yearMonth == null) {
            return YearMonth.from(
                java.time.Instant
                    .ofEpochSecond(clock.now().epochSeconds)
                    .atZone(ZoneOffset.UTC),
            )
        }
        if (!MONTH_FORMAT.matches(yearMonth)) {
            throw ValidationException("expected YYYY-MM", field = "month")
        }
        return try {
            YearMonth.parse(yearMonth)
        } catch (_: java.time.format.DateTimeParseException) {
            throw ValidationException("expected YYYY-MM", field = "month")
        }
    }

    data class Snapshot(
        val globalUsdLast60s: Double,
        val globalCapUsdPerMin: Double?,
        val perAgentUsdLast24h: Map<String, Double>,
        val perAgentCapUsdPerDay: Map<String, Double>,
        val snapshotAtSec: Long,
    )

    data class MonthlyCosts(
        val month: String,
        val perAgentUsd: Map<String, Double>,
        val totalUsd: Double,
        val availableMonths: List<String>,
    )

    data class TotalCosts(
        val perAgentUsd: Map<String, Double>,
        val totalUsd: Double,
    )

    companion object {
        const val GLOBAL_WINDOW_SECONDS: Long = 60
        const val AGENT_WINDOW_SECONDS: Long = 24 * 60 * 60
        private const val OVERSHOOT_LOG_WINDOW_MS = 60_000L
        private val MONTH_FORMAT = Regex("""^\d{4}-\d{2}$""")
        private val log = LoggerFactory.getLogger(CostMeter::class.java)
    }
}
