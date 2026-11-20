package homer.app.services.persistence

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.Logger
import java.util.UUID

/**
 * Durable server-instance metadata (the [ServerMeta] table). It holds the stable
 * per-instance id used to scope the Kubernetes startup sweep, and the admin
 * maintenance-mode flag. SQLite is authoritative (ADR-0020).
 */
class ServerMetaRepository(
    private val db: DatabaseService,
    private val logger: Logger,
) {
    /**
     * The per-instance id, created on first call and, unlike `HMR_SERVER_ID`, kept across restarts: it scopes the
     * Kubernetes startup sweep to this instance's own orphans. Blocking and startup-only, not through
     * [DatabaseService.query] (ADR-0020); [DatabaseService.migrate] must have created `server_meta`.
     */
    fun getOrCreateInstanceId(): String =
        transaction(db.database) {
            ServerMeta
                .selectAll()
                .where { ServerMeta.key eq INSTANCE_ID_KEY }
                .firstOrNull()
                ?.get(ServerMeta.value)
                ?: UUID.randomUUID().toString().also { id ->
                    ServerMeta.insert {
                        it[key] = INSTANCE_ID_KEY
                        it[value] = id
                    }
                }
        }

    /**
     * The persisted maintenance-mode flag, read blocking once at startup like [getOrCreateInstanceId]. Anything
     * but `"true"`/`"false"` is logged and read as off: a damaged row never holds every start back (ADR-0092).
     */
    fun readMaintenanceMode(): Boolean =
        parseMaintenanceMode(
            transaction(db.database) {
                ServerMeta
                    .selectAll()
                    .where { ServerMeta.key eq MAINTENANCE_MODE_KEY }
                    .firstOrNull()
                    ?.get(ServerMeta.value)
            },
        )

    /**
     * Persists the maintenance-mode flag. Called from the toggle endpoint, so it runs on
     * the single-attempt writer handle ([DatabaseService.query]) — the upsert is idempotent,
     * so a replayed write cannot corrupt the value.
     */
    suspend fun writeMaintenanceMode(enabled: Boolean) {
        val stored = enabled.toString()
        db.query {
            val updated =
                ServerMeta.update({ ServerMeta.key eq MAINTENANCE_MODE_KEY }) {
                    it[value] = stored
                }
            if (updated == 0) {
                ServerMeta.insert {
                    it[key] = MAINTENANCE_MODE_KEY
                    it[value] = stored
                }
            }
        }
    }

    private fun parseMaintenanceMode(stored: String?): Boolean =
        when (stored) {
            MAINTENANCE_MODE_ON -> true
            MAINTENANCE_MODE_OFF, null -> false
            else -> logUnreadableMaintenanceMode(stored)
        }

    private fun logUnreadableMaintenanceMode(stored: String): Boolean {
        logger.warn(
            "server_meta '{}' holds the unreadable value '{}'; reading maintenance mode as off",
            MAINTENANCE_MODE_KEY,
            stored,
        )
        return false
    }

    private companion object {
        const val INSTANCE_ID_KEY = "instance_id"
        const val MAINTENANCE_MODE_KEY = "maintenance_mode"
        const val MAINTENANCE_MODE_ON = "true"
        const val MAINTENANCE_MODE_OFF = "false"
    }
}
