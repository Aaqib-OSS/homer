package homer.app.infra

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import homer.app.models.CronConfig
import java.time.ZoneId

/**
 * UNIX-cron parsing shared by the scheduler and load-time validation, so a schedule that loads
 * can never throw on a tick.
 */
internal object CronSupport {
    private val parser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

    /** Parsed zone + execution time for [config]. Throws on a malformed expression/timezone. */
    fun executionTimeFor(config: CronConfig): Pair<ZoneId, ExecutionTime> =
        ZoneId.of(config.timezone) to ExecutionTime.forCron(parser.parse(config.expression).validate())

    /** Returns null if [config] is valid, else a short human-readable reason. */
    fun validate(config: CronConfig): String? =
        try {
            executionTimeFor(config)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
}
