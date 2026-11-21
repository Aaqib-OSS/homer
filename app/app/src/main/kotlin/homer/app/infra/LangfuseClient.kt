package homer.app.infra

/**
 * Interface for Langfuse client providing prompt management capabilities
 */
interface LangfuseClient {
    /**
     * Fetches the named prompt from Langfuse and returns it compiled with [promptArguments].
     */
    suspend fun getPrompt(
        promptName: String,
        promptArguments: Map<String, String> = emptyMap(),
        version: Int? = null,
        label: String? = null,
    ): String

    /**
     * Substitutes [arguments] into the `{{variable}}` placeholders of [template].
     */
    fun compilePrompt(
        template: String,
        arguments: Map<String, String>,
    ): String
}
