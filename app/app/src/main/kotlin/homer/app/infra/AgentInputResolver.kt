package homer.app.infra

import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import homer.app.exceptions.PayloadSchemaValidationException
import homer.app.exceptions.QueryParamValidationException
import homer.app.exceptions.ValidationException
import homer.app.models.Agent
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.Transformer
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resolves agent inputs from query parameters, JSON request body, and HTTP headers
 * based on the agent's `inputs` (or `queryParams` alias) configuration.
 */
class AgentInputResolver(
    private val inputSchemaValidator: InputSchemaValidator = InputSchemaValidator(),
    private val paramValidator: ParamValidator = ParamValidator(),
) {
    /**
     * Resolves agent inputs from one HTTP request, dispatching each [Param] to query, JSON body or header.
     * [readBody] runs only for a `BODY` input or an `inputSchema`; a body then needs POST and JSON.
     * See [readJsonBody].
     */
    suspend fun resolve(
        method: HttpMethod,
        queryParameters: Parameters,
        headers: Headers,
        readBody: suspend () -> String,
        agent: Agent,
        enforceRequired: Boolean = true,
    ): Map<String, String> {
        val inputs = agent.effectiveInputs ?: emptyList()
        val needsBody = inputs.any { it.src == ParamSource.BODY } || agent.inputSchema != null
        val bodyJson: JsonObject? = if (needsBody) readJsonBody(method, headers, readBody, agent) else null

        applyInputSchema(agent, bodyJson)

        if (inputs.isEmpty()) return emptyMap()

        return resolveInternal(
            inputs = inputs,
            queryParameters = queryParameters,
            bodyJson = bodyJson,
            headerLookup = { name -> headers[name] },
            enforceRequired = enforceRequired,
        )
    }

    /**
     * Reads and parses the request body as a top-level JSON object, enforcing POST and a JSON
     * Content-Type on a non-blank body; a blank body is `{}` for an agent without an
     * `inputSchema`. See [resolve].
     */
    private suspend fun readJsonBody(
        method: HttpMethod,
        headers: Headers,
        readBody: suspend () -> String,
        agent: Agent,
    ): JsonObject {
        if (method != HttpMethod.Post) {
            throw QueryParamValidationException(
                "Agent '${agent.name}' requires request body; use POST",
            )
        }
        val text =
            try {
                readBody()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw QueryParamValidationException("Invalid JSON body")
            }
        if (text.isBlank() && agent.inputSchema == null) return JsonObject(emptyMap())
        requireJsonContentType(headers)
        return parseJsonObject(text)
    }

    /**
     * Resolves inputs for the debug-execute endpoint from its JSON payload, with the same extraction and
     * validation as [resolve]. Header lookup is case-insensitive; [headers] is indexed by lower-cased key.
     */
    fun resolveDebug(
        queryParams: Parameters,
        bodyJson: JsonObject?,
        headers: Map<String, String>,
        agent: Agent,
        enforceRequired: Boolean = false,
        skipTransformFor: Set<String> = emptySet(),
    ): Map<String, String> {
        val inputs = agent.effectiveInputs ?: emptyList()
        applyInputSchema(agent, bodyJson)
        if (inputs.isEmpty()) return emptyMap()
        val headerIndex = headers.mapKeys { it.key.lowercase() }
        return resolveInternal(
            inputs = inputs,
            queryParameters = queryParams,
            bodyJson = bodyJson,
            headerLookup = { name -> headerIndex[name.lowercase()] },
            enforceRequired = enforceRequired,
            skipTransformFor = skipTransformFor,
        )
    }

    /** Validates [bodyJson] against the agent's `inputSchema`; skipped when either is absent. */
    private fun applyInputSchema(
        agent: Agent,
        bodyJson: JsonObject?,
    ) {
        val schema = agent.inputSchema ?: return
        val body = bodyJson ?: return
        val errors = inputSchemaValidator.validate(schema, body)
        if (errors.isNotEmpty()) {
            throw PayloadSchemaValidationException(
                "Payload failed schema validation: ${errors.joinToString("; ")}",
                errors = errors,
            )
        }
    }

    private fun resolveInternal(
        inputs: List<Param>,
        queryParameters: Parameters,
        bodyJson: JsonObject?,
        headerLookup: (String) -> String?,
        enforceRequired: Boolean,
        skipTransformFor: Set<String> = emptySet(),
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        inputs.forEach { param ->
            val raw: String? =
                when (param.src) {
                    ParamSource.QUERY -> queryParameters[param.paramName]
                    ParamSource.BODY -> bodyJson?.get(param.paramName)?.let(::stringifyJsonValue)
                    ParamSource.HEADER -> headerLookup(param.paramName)
                }
            val resolved =
                if (raw == null) {
                    resolveAbsent(param, enforceRequired)
                } else {
                    resolvePresent(param, raw, enforceRequired, param.paramName in skipTransformFor)
                }
            if (resolved != null) result[param.envName] = resolved
        }
        return result
    }

    /** An input the request did not carry: its validated default, else null — or a rejection when it is required. */
    private fun resolveAbsent(
        param: Param,
        enforceRequired: Boolean,
    ): String? {
        val default = param.default
        if (default != null) {
            // Load-time validateDeclaration guarantees the default satisfies the param's constraints.
            return when (val v = paramValidator.validate(default, param)) {
                is ParamValidation.Valid -> v.normalized
                is ParamValidation.Invalid -> throw QueryParamValidationException(v.message, paramName = param.paramName)
            }
        }
        if (param.required && enforceRequired) {
            throw QueryParamValidationException(
                "Parameter '${param.paramName}' is missing",
                paramName = param.paramName,
            )
        }
        return null
    }

    /** An input the request carried, transformed and validated; unusable is null (lenient) or a rejection (strict). */
    private fun resolvePresent(
        param: Param,
        raw: String,
        enforceRequired: Boolean,
        skipTransform: Boolean = false,
    ): String? {
        // A stored value is already transformed; transforming again would reject it (ADR-0101).
        val transformed = if (skipTransform) raw else transform(param.transformer, raw)
        val invalidMessage: String =
            if (transformed == null) {
                "Parameter '${param.paramName}' is not valid ${param.type.wireName}"
            } else {
                when (val v = paramValidator.validate(transformed, param)) {
                    is ParamValidation.Valid -> return v.normalized
                    is ParamValidation.Invalid -> v.message
                }
            }
        if (enforceRequired) {
            throw QueryParamValidationException(invalidMessage, paramName = param.paramName)
        }
        return null
    }

    /** The query and body shapes [resolveInternal] expects, as split by [classifyParams]. */
    class ClassifiedParams(
        val queryParams: Parameters,
        val bodyJson: JsonObject?,
    )

    /**
     * Routes a string param map by each param's declared src: QUERY to query parameters, everything else
     * to a JSON body field; HEADER and reserved control keys are dropped so internal callers cannot forge them.
     */
    fun classifyParams(
        agent: Agent,
        params: Map<String, String>,
    ): ClassifiedParams {
        val inputs = agent.effectiveInputs.orEmpty()
        val queryNames = inputs.filter { it.src == ParamSource.QUERY }.map(Param::paramName).toSet()
        val headerNames = inputs.filter { it.src == ParamSource.HEADER }.map(Param::paramName).toSet()
        val queryBuilder = ParametersBuilder()
        val bodyFields = mutableMapOf<String, JsonElement>()
        params.forEach { (name, value) ->
            when {
                name in queryNames -> {
                    queryBuilder.append(name, value)
                }

                name in headerNames -> {}

                ReservedEnvNames.isReserved(name) -> {}

                else -> {
                    bodyFields[name] = JsonPrimitive(value)
                }
            }
        }
        val bodyJson =
            if (bodyFields.isNotEmpty() || agent.inputSchema != null) JsonObject(bodyFields) else null
        return ClassifiedParams(queryBuilder.build(), bodyJson)
    }

    /**
     * Resolves inputs from a plain param map (RunAgent, dispatch, continuation) like the HTTP route.
     * [skipTransformFor] names already-transformed stored inputs: validated, not transformed again (ADR-0101).
     */
    fun resolveFromParams(
        params: Map<String, String>,
        agent: Agent,
        enforceRequired: Boolean = true,
        skipTransformFor: Set<String> = emptySet(),
    ): Map<String, String> {
        val classified = classifyParams(agent, params)
        return resolveDebug(
            queryParams = classified.queryParams,
            bodyJson = classified.bodyJson,
            headers = emptyMap(),
            agent = agent,
            enforceRequired = enforceRequired,
            skipTransformFor = skipTransformFor,
        )
    }

    /**
     * Display names of the inputs a cron run can never satisfy: every required declared input (a fire
     * carries no params) and every `inputSchema.required` field, marked ` (inputSchema)`.
     */
    fun unsatisfiableWithoutDefaults(agent: Agent): List<String> =
        agent.effectiveInputs
            .orEmpty()
            .filter { it.required }
            .map { "'${it.paramName}'" } +
            schemaRequiredFields(agent).map { "'$it' (inputSchema)" }

    /**
     * Presence-only dispatch check: every required input and `inputSchema.required` field must be in
     * [providedParamNames]; a required HEADER input never is.
     */
    fun validateSatisfiable(
        agent: Agent,
        providedParamNames: Set<String>,
    ) {
        val unsatisfiable =
            agent.effectiveInputs.orEmpty().filter { param ->
                param.required &&
                    (param.src == ParamSource.HEADER || param.paramName !in providedParamNames)
            }
        val schemaMissing =
            schemaRequiredFields(agent).filter { it !in providedParamNames }
        if (unsatisfiable.isNotEmpty() || schemaMissing.isNotEmpty()) {
            val details =
                unsatisfiable.map { "${it.paramName} (${it.src.name.lowercase()})" } +
                    schemaMissing.map { "$it (inputSchema)" }
            throw ValidationException(
                "target agent '${agent.name}' has required inputs not satisfiable by this call: " +
                    details.joinToString(", "),
                "params",
            )
        }
    }

    /**
     * Re-validates a run's stored inputs against the current definitions for retry and resume: a missing
     * required input rejects, removed inputs drop, control markers stay; no transformer or `inputSchema` re-run.
     */
    fun resolveRetryParams(
        agent: Agent,
        storedParams: Map<String, String>,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        storedParams.forEach { (name, value) ->
            if (ReservedEnvNames.isReserved(name)) result[name] = value
        }
        agent.effectiveInputs.orEmpty().forEach { param ->
            val stored = storedParams[param.envName]
            if (stored == null) {
                if (param.required) {
                    throw QueryParamValidationException(
                        "Cannot retry: agent '${agent.name}' now requires input '${param.paramName}' " +
                            "which is absent from the original run",
                        paramName = param.paramName,
                    )
                }
                return@forEach
            }
            when (val v = paramValidator.validate(stored, param)) {
                is ParamValidation.Valid -> {
                    result[param.envName] = v.normalized
                }

                is ParamValidation.Invalid -> {
                    throw QueryParamValidationException(
                        "Cannot retry: stored value for '${param.paramName}' is no longer valid: ${v.message}",
                        paramName = param.paramName,
                    )
                }
            }
        }
        return result
    }

    /** The `inputSchema.required` field names, tolerating a non-string entry. */
    internal fun schemaRequiredFields(agent: Agent): List<String> =
        (agent.inputSchema?.get("required") as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }
}

/** Accepts `application/json` or `application/<subtype>+json`, ignoring parameters. */
private fun requireJsonContentType(headers: Headers) {
    val rawCt = headers[HttpHeaders.ContentType]
    val ct =
        try {
            (rawCt?.let(ContentType::parse) ?: ContentType.Any).withoutParameters()
        } catch (_: BadContentTypeFormatException) {
            // CWE-248: a caller-controlled header must not escape as an unhandled 500.
            throw QueryParamValidationException("Invalid Content-Type header: '$rawCt'")
        }
    val ok =
        ct.match(ContentType.Application.Json) ||
            (ct.contentType == "application" && ct.contentSubtype.endsWith("+json"))
    if (!ok) {
        throw QueryParamValidationException(
            "Expected Content-Type: application/json (got '$ct')",
        )
    }
}

/** [text] as a top-level JSON object; anything else is a [QueryParamValidationException]. */
private fun parseJsonObject(text: String): JsonObject =
    try {
        Json.parseToJsonElement(text) as? JsonObject
            ?: throw QueryParamValidationException("Invalid JSON body: expected an object")
    } catch (e: CancellationException) {
        throw e
    } catch (e: QueryParamValidationException) {
        throw e
    } catch (_: Exception) {
        throw QueryParamValidationException("Invalid JSON body")
    }

private fun transform(
    transformer: Transformer,
    raw: String,
): String? =
    when (transformer) {
        is Transformer.JsonPath -> {
            val parsed = runCatching { JsonPath.parse(raw) }.getOrNull()
            try {
                jsonPathText(parsed?.read<Any>(transformer.path))
            } catch (_: PathNotFoundException) {
                null
            }
        }

        Transformer.Pass -> {
            raw
        }
    }

private fun stringifyJsonValue(value: JsonElement): String? =
    when (value) {
        is JsonNull -> null
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
