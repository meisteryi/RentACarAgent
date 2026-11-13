package com.hyundai.rentalhelper.intent

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Minimal HTTP abstraction so timeouts and network failures can be unit tested without a server. */
fun interface ProxyTransport {
    /** POSTs [body] as JSON. Throws [ResponseTooLargeException] when the body exceeds [maxResponseBytes]. */
    suspend fun postJson(endpoint: String, body: String, maxResponseBytes: Int): ProxyHttpResponse
}

data class ProxyHttpResponse(val status: Int, val body: String)

class ResponseTooLargeException : Exception("proxy_response_too_large")

class HttpUrlConnectionProxyTransport(
    private val connectTimeoutMs: Int = 1_500,
    private val readTimeoutMs: Int = 5_500,
    /** Optional shared app token for the proxy. It is not the Gemini key. */
    private val authToken: String? = null
) : ProxyTransport {
    override suspend fun postJson(endpoint: String, body: String, maxResponseBytes: Int): ProxyHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                authToken?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) return@withContext ProxyHttpResponse(status, "")
                if (connection.contentLengthLong > maxResponseBytes) throw ResponseTooLargeException()
                val text = connection.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    val output = ByteArrayOutputStream()
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        if (output.size() > maxResponseBytes) throw ResponseTooLargeException()
                    }
                    String(output.toByteArray(), StandardCharsets.UTF_8)
                }
                ProxyHttpResponse(status, text)
            } finally {
                connection.disconnect()
            }
        }
}

/**
 * Android-side client for a first-party backend which calls Gemini Flash.
 * The Gemini API key is deliberately not accepted or stored by this class, and nothing here logs the
 * transcript or the proxy response.
 */
class GeminiFlashProxyIntentClassifier(
    private val endpoint: String,
    private val minimumConfidence: Double = 0.55,
    private val totalTimeoutMs: Long = 6_000L,
    private val transport: ProxyTransport = HttpUrlConnectionProxyTransport(),
    /** Debug builds only: permits plain HTTP to the host-loopback proxy used in local/emulator testing. */
    private val allowLocalHttp: Boolean = false,
    /** Follow-up turn endpoint; by default the classify endpoint with `/classify` replaced by `/turn`. */
    private val turnEndpoint: String = turnEndpointFor(endpoint)
) : IntentClassifier, DialogueTurnClassifier {
    private val gson = Gson()

    /**
     * One proxy call per follow-up turn: the dialogue act and, for NEW_QUESTION, the FAQ intent. The model only
     * labels the utterance; [RendyQuestionPipeline] decides what is spoken and how the state changes.
     */
    override suspend fun classifyTurn(
        query: String,
        candidates: List<IntentCandidate>,
        context: TurnContext
    ): TurnClassification {
        if (query.isBlank()) return TurnClassification.Act(DialogueAct.UNCLEAR, null, 0.0)
        val uniqueCandidates = candidates.distinctBy { it.id }
        if (uniqueCandidates.isEmpty()) return TurnClassification.Failure("no_candidates")
        if (!isAllowedEndpoint(turnEndpoint, allowLocalHttp)) return TurnClassification.Failure("proxy_endpoint_must_be_https")
        val allowedIds = uniqueCandidates.mapTo(mutableSetOf()) { it.id }
        return try {
            val response = withTimeout(totalTimeoutMs) {
                transport.postJson(turnEndpoint, buildTurnRequestJson(query, uniqueCandidates, context), MAX_RESPONSE_BYTES)
            }
            if (response.status !in 200..299) return TurnClassification.Failure("proxy_http_${response.status}")
            validateTurnPayload(response.body, allowedIds)
        } catch (_: TimeoutCancellationException) {
            TurnClassification.Failure("proxy_timeout")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: ResponseTooLargeException) {
            TurnClassification.Failure("proxy_response_too_large")
        } catch (_: Exception) {
            TurnClassification.Failure("proxy_request_failed")
        }
    }

    internal fun buildTurnRequestJson(query: String, candidates: List<IntentCandidate>, context: TurnContext): String {
        val ids = candidates.mapTo(mutableSetOf()) { it.id }
        val dialogue = JsonObject().apply {
            addProperty("last_rendy_utterance", context.lastRendyUtterance?.take(MAX_CONTEXT_TEXT_CHARS))
            addProperty("rendy_asked_question", context.rendyAskedQuestion)
            addProperty("last_driver_question", context.lastDriverQuestion?.take(MAX_CONTEXT_TEXT_CHARS))
            addProperty("current_intent", context.currentIntentId?.takeIf { it in ids })
            addProperty("stage", context.stage.name)
            addProperty("pending_detail", context.pendingDetail)
            addProperty("driving_state", context.drivingState.name)
            addProperty("car_model", context.carModelName.take(MAX_CAR_MODEL_CHARS))
        }
        val root = JsonObject().apply {
            addProperty("query", query.take(MAX_QUERY_CHARS))
            add("allowed_intents", gson.toJsonTree(candidates))
            add("dialogue", dialogue)
        }
        return Gson().newBuilder().serializeNulls().create().toJson(root)
    }

    override suspend fun classify(
        query: String,
        candidates: List<IntentCandidate>,
        context: ClassificationContext
    ): IntentClassification {
        if (query.isBlank()) return IntentClassification.Clarification(null, "empty_query")
        val uniqueCandidates = candidates.distinctBy { it.id }
        if (uniqueCandidates.isEmpty()) return IntentClassification.Clarification(null, "no_verified_supported_intents")
        if (!isAllowedEndpoint(endpoint, allowLocalHttp)) return IntentClassification.Failure("proxy_endpoint_must_be_https")
        val allowedIds = uniqueCandidates.mapTo(mutableSetOf()) { it.id }
        val previousIntent = context.previousIntentId?.takeIf { it in allowedIds }

        return try {
            val response = withTimeout(totalTimeoutMs) {
                transport.postJson(
                    endpoint,
                    gson.toJson(buildProxyRequest(query, uniqueCandidates, previousIntent)),
                    MAX_RESPONSE_BYTES
                )
            }
            if (response.status !in 200..299) return IntentClassification.Failure("proxy_http_${response.status}")
            validatePayload(response.body, allowedIds, minimumConfidence)
        } catch (_: TimeoutCancellationException) {
            IntentClassification.Failure("proxy_timeout")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: ResponseTooLargeException) {
            IntentClassification.Failure("proxy_response_too_large")
        } catch (_: Exception) {
            IntentClassification.Failure("proxy_request_failed")
        }
    }

    internal fun buildProxyRequestJson(query: String, candidates: List<IntentCandidate>, previousIntent: String?): String =
        gson.toJson(buildProxyRequest(query, candidates.distinctBy { it.id }, previousIntent))

    private fun buildProxyRequest(
        query: String,
        candidates: List<IntentCandidate>,
        previousIntent: String?
    ): ProxyIntentRequest {
        val allowedIds = candidates.map { it.id } + UNKNOWN_INTENT
        val properties = JsonObject().apply {
            add("intent", JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply { allowedIds.forEach { id -> add(id) } })
            })
            add("confidence", JsonObject().apply {
                addProperty("type", "number")
                addProperty("minimum", 0)
                addProperty("maximum", 1)
            })
            add("requires_clarification", JsonObject().apply { addProperty("type", "boolean") })
        }
        val schema = JsonObject().apply {
            addProperty("type", "object")
            add("properties", properties)
            add("required", JsonArray().apply {
                add("intent")
                add("confidence")
                add("requires_clarification")
            })
            addProperty("additionalProperties", false)
        }
        return ProxyIntentRequest(
            query = query,
            allowedIntents = candidates,
            previousIntent = previousIntent,
            responseSchema = schema
        )
    }

    private data class ProxyIntentRequest(
        @SerializedName("query") val query: String,
        @SerializedName("allowed_intents") val allowedIntents: List<IntentCandidate>,
        /** Last DB-answered intent within the context window, or omitted. Used only for pronoun follow-ups. */
        @SerializedName("previous_intent") val previousIntent: String?,
        @SerializedName("response_schema") val responseSchema: JsonObject
    )

    companion object {
        const val UNKNOWN_INTENT = "UNKNOWN"
        const val MAX_RESPONSE_BYTES = 64 * 1024
        // Proxy request limits (server.mjs validateTurnRequest).
        private const val MAX_QUERY_CHARS = 300
        private const val MAX_CONTEXT_TEXT_CHARS = 300
        private const val MAX_CAR_MODEL_CHARS = 40
        /** Below this the act is treated as UNCLEAR. */
        const val MINIMUM_ACT_CONFIDENCE = 0.55
        /** Acts that move the conversation forward need more certainty; otherwise UNCLEAR (re-ask). */
        const val MINIMUM_PROGRESS_ACT_CONFIDENCE = 0.7
        private val PROGRESS_ACTS = setOf(DialogueAct.CONFIRM, DialogueAct.FOUND, DialogueAct.DENY)

        fun turnEndpointFor(classifyEndpoint: String): String =
            classifyEndpoint.trim().replace(Regex("/classify/?$"), "/turn")

        /**
         * Strict check of a /turn response: exactly {dialogue_act, intent, confidence}. Low confidence becomes UNCLEAR
         * (never a guessed CONFIRM/FOUND); an intent on any act other than NEW_QUESTION is dropped.
         */
        fun validateTurnPayload(json: String, allowedIntentIds: Set<String>): TurnClassification {
            return try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonObject) return TurnClassification.Failure("invalid_json_shape")
                val obj = root.asJsonObject
                if (obj.keySet() != setOf("dialogue_act", "intent", "confidence")) return TurnClassification.Failure("invalid_schema_fields")
                val actEl = obj.get("dialogue_act")
                val intentEl = obj.get("intent")
                val confEl = obj.get("confidence")
                if (!actEl.isJsonPrimitive || !actEl.asJsonPrimitive.isString ||
                    !intentEl.isJsonPrimitive || !intentEl.asJsonPrimitive.isString ||
                    !confEl.isJsonPrimitive || !confEl.asJsonPrimitive.isNumber
                ) return TurnClassification.Failure("invalid_schema_types")
                val act = DialogueAct.values().firstOrNull { it.name == actEl.asString }
                    ?: return TurnClassification.Failure("invalid_dialogue_act")
                val confidence = confEl.asDouble
                if (!confidence.isFinite() || confidence !in 0.0..1.0) return TurnClassification.Failure("invalid_confidence")
                val intent = intentEl.asString
                if (intent != UNKNOWN_INTENT && intent !in allowedIntentIds) return TurnClassification.Failure("intent_not_allowed")
                val threshold = if (act in PROGRESS_ACTS) MINIMUM_PROGRESS_ACT_CONFIDENCE else MINIMUM_ACT_CONFIDENCE
                if (confidence < threshold) return TurnClassification.Act(DialogueAct.UNCLEAR, null, confidence)
                TurnClassification.Act(
                    act, if (act == DialogueAct.NEW_QUESTION && intent != UNKNOWN_INTENT) intent else null, confidence,
                    intentResolved = true
                )
            } catch (_: Exception) {
                TurnClassification.Failure("invalid_json")
            }
        }

        private val LOCAL_DEV_HOSTS = setOf("127.0.0.1", "localhost", "10.0.2.2")

        /** HTTPS always; plain HTTP only to a local development host and only when [allowLocalHttp]. */
        fun isAllowedEndpoint(value: String, allowLocalHttp: Boolean): Boolean =
            isSecureEndpoint(value) || (allowLocalHttp && try {
                val url = URL(value)
                url.protocol.equals("http", ignoreCase = true) && url.host in LOCAL_DEV_HOSTS &&
                    url.userInfo == null && url.ref == null
            } catch (_: Exception) {
                false
            })

        fun isSecureEndpoint(value: String): Boolean = try {
            val url = URL(value)
            url.protocol.equals("https", ignoreCase = true) &&
                url.host.isNotBlank() && url.userInfo == null && url.ref == null
        } catch (_: Exception) {
            false
        }

        /** Strictly validate the proxy response independently of the provider's schema enforcement. */
        fun validatePayload(
            json: String,
            allowedIntentIds: Set<String>,
            minimumConfidence: Double = 0.55
        ): IntentClassification {
            return try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonObject) return IntentClassification.Failure("invalid_json_shape")
                val obj = root.asJsonObject
                val expectedFields = setOf("intent", "confidence", "requires_clarification")
                if (obj.keySet() != expectedFields) return IntentClassification.Failure("invalid_schema_fields")

                val intentElement = obj.get("intent")
                val confidenceElement = obj.get("confidence")
                val clarificationElement = obj.get("requires_clarification")
                if (intentElement == null || !intentElement.isJsonPrimitive || !intentElement.asJsonPrimitive.isString ||
                    confidenceElement == null || !confidenceElement.isJsonPrimitive || !confidenceElement.asJsonPrimitive.isNumber ||
                    clarificationElement == null || !clarificationElement.isJsonPrimitive || !clarificationElement.asJsonPrimitive.isBoolean
                ) return IntentClassification.Failure("invalid_schema_types")

                val intentId = intentElement.asString
                val confidence = confidenceElement.asDouble
                val needsClarification = clarificationElement.asBoolean
                if (!confidence.isFinite() || confidence !in 0.0..1.0) {
                    return IntentClassification.Failure("invalid_confidence")
                }
                if (needsClarification || intentId == UNKNOWN_INTENT) {
                    return IntentClassification.Clarification(confidence, "model_requires_clarification")
                }
                if (intentId !in allowedIntentIds) return IntentClassification.Failure("intent_not_allowed")
                if (confidence < minimumConfidence) return IntentClassification.Clarification(confidence, "confidence_below_threshold")
                IntentClassification.Classified(intentId, confidence)
            } catch (_: Exception) {
                IntentClassification.Failure("invalid_json")
            }
        }
    }
}
