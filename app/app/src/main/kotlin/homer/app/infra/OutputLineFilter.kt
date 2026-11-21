package homer.app.infra

/**
 * Decides per stdout line (UTF-8, no trailing newline) whether [ShellExecutor] writes it;
 * the original bytes are written on `true`. May be called from an I/O pump thread. See ADR-0063.
 */
fun interface OutputLineFilter {
    fun keep(line: String): Boolean
}
