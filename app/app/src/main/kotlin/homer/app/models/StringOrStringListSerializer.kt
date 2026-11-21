package homer.app.models

/**
 * Accepts a scalar string (one-element list) or a list of strings. Unlike [CmdArgsSerializer] a
 * scalar is never POSIX-tokenized, so a path containing spaces stays one value.
 */
object StringOrStringListSerializer : ScalarOrStringListSerializer(
    "homer.app.models.StringOrStringList",
    subject = "value",
    elementPrefix = "",
) {
    override fun fromScalar(content: String): List<String> = listOf(content)
}
