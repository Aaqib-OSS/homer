package homer.app

import homer.app.infra.Dumpability
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Makes the server JVM non-dumpable where it can and logs the outcome; startup goes on either way. See ADR-0078. */
internal fun hardenServerJvm(
    harden: () -> Dumpability.Result = Dumpability::harden,
    logger: Logger = LoggerFactory.getLogger("homer.app.Application"),
) {
    when (val result = harden()) {
        Dumpability.Result.Hardened -> {
            logger.info("Server JVM is non-dumpable")
        }

        is Dumpability.Result.Skipped -> {
            logger.warn("Server JVM left dumpable: {}", result.reason)
        }

        is Dumpability.Result.Failed -> {
            logger.error(
                "Server JVM left dumpable, so processes running as Homer's user can read its environment: {}",
                result.reason,
            )
        }
    }
}
