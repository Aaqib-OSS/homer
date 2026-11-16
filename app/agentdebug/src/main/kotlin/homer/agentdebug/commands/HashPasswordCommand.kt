package homer.agentdebug.commands

import com.github.ajalt.clikt.core.ProgramResult
import homer.app.infra.PasswordVerifier
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Generates a bcrypt hash for use as `passwordHash` in `AuthConfig.Password.users`.
 *
 * Reads a password from stdin and prints the hash on stdout. Interactive TTY reads use
 * `System.console().readPassword(...)` (never echoed, never in shell history); piped
 * stdin is read one line verbatim (tests, `echo 'pass' | agentdebug hash-password`).
 * Prompts and read-backs go to stderr, so callers can `agentdebug hash-password > hash.txt`.
 */
class HashPasswordCommand(
    private val cost: Int,
    /** Reads one entry without echo, given its prompt; null when there is no console (piped stdin). */
    private val readHidden: ((String) -> CharArray?)? = System.console()?.let { console -> { prompt -> console.readPassword(prompt) } },
) {
    fun run() {
        require(cost in 4..15) { "cost must be in 4..15 (got $cost)" }

        val password = readPassword()
        if (password.isEmpty()) {
            System.err.println("Empty password — aborting.")
            throw ProgramResult(2)
        }

        val hash = PasswordVerifier().hash(password, cost)
        println(hash)
    }

    private fun readPassword(): String {
        val readHidden = readHidden ?: return BufferedReader(InputStreamReader(System.`in`)).readLine().orEmpty()
        return confirmedPassword(readHidden("Password: "), readHidden("Confirm:  "))
            ?: run {
                System.err.println("Passwords did not match — aborting.")
                throw ProgramResult(2)
            }
    }
}

/** The password when the [confirmation] matches the [first] entry (an unread entry counts as empty); null otherwise. */
internal fun confirmedPassword(
    first: CharArray?,
    confirmation: CharArray?,
): String? {
    val password = (first ?: charArrayOf()).concatToString()
    return password.takeIf { it == (confirmation ?: charArrayOf()).concatToString() }
}
