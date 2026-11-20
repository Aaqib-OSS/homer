package homer.app.runners

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks open `RunnerSession`s so that graceful shutdown can close
 * them all (deleting pods / freeing resources) instead of leaving
 * orphans behind.
 */
class ActiveSessionsRegistry {
    private val sessions = ConcurrentHashMap.newKeySet<RunnerSession>()

    fun add(session: RunnerSession) {
        sessions.add(session)
    }

    fun remove(session: RunnerSession) {
        sessions.remove(session)
    }

    fun size(): Int = sessions.size

    /**
     * Closes every tracked session. A session whose close fails is logged with its runner alias
     * and the rest still close. Called from the Ktor `ApplicationStopping` hook.
     */
    fun closeAll() {
        val snapshot = sessions.toList()
        sessions.clear()
        snapshot.forEach { s ->
            runCatching { s.close() }.onFailure {
                log.warn("Failed to close session on runner '{}' during shutdown", s.runnerAlias, it)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ActiveSessionsRegistry::class.java)
    }
}
