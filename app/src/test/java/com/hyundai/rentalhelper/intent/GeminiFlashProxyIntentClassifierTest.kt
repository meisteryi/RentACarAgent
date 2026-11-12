package com.hyundai.rentalhelper.intent

import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/** Proxy client behaviour with a fake transport (no network, no Gemini). */
class GeminiFlashProxyIntentClassifierTest {
    private val endpoint = "https://rendy-proxy.example.invalid/v1/classify"
    private val candidates = listOf(
        IntentCandidate("front_defog", listOf("김서림")),
        IntentCandidate("hazard_light", listOf("비상등"))
    )
    private val allowed = setOf("front_defog", "hazard_light")

    private fun classifier(transport: ProxyTransport, timeoutMs: Long = 2_000L) =
        GeminiFlashProxyIntentClassifier(endpoint, totalTimeoutMs = timeoutMs, transport = transport)

    private fun ok(body: String) = ProxyTransport { _, _, _ -> ProxyHttpResponse(200, body) }

    @Test
    fun validResponseIsClassified() = runBlocking {
        val result = classifier(ok("""{"intent":"front_defog","confidence":0.91,"requires_clarification":false}"""))
            .classify("앞유리가 뿌예", candidates)
        assertEquals(IntentClassification.Classified("front_defog", 0.91), result)
    }

    @Test
    fun requestCarriesOnlyQueryAllowlistSchemaAndAllowedContext() = runBlocking {
        var sentBody = ""
        var sentEndpoint = ""
        val transport = ProxyTransport { url, body, max ->
            sentEndpoint = url; sentBody = body
            assertEquals(GeminiFlashProxyIntentClassifier.MAX_RESPONSE_BYTES, max)
            ProxyHttpResponse(200, """{"intent":"UNKNOWN","confidence":0.1,"requires_clarification":true}""")
        }
        classifier(transport).classify("이거 어떻게 꺼?", candidates, ClassificationContext("hazard_light"))
        assertEquals(endpoint, sentEndpoint)
        val json = JsonParser.parseString(sentBody).asJsonObject
        assertEquals(setOf("query", "allowed_intents", "previous_intent", "response_schema"), json.keySet())
        assertEquals("이거 어떻게 꺼?", json["query"].asString)
        assertEquals("hazard_light", json["previous_intent"].asString)
        val enum = json["response_schema"].asJsonObject["properties"].asJsonObject["intent"].asJsonObject["enum"].asJsonArray.map { it.asString }
        assertEquals(listOf("front_defog", "hazard_light", "UNKNOWN"), enum)
        assertFalse(sentBody.contains("key", ignoreCase = true) && sentBody.contains("api", ignoreCase = true))

        // Context that is not an offered candidate is dropped instead of leaking to the proxy.
        classifier(transport).classify("이거 어떻게 꺼?", candidates, ClassificationContext("sunroof_switch"))
        assertFalse(JsonParser.parseString(sentBody).asJsonObject.has("previous_intent"))
    }

    @Test
    fun timeoutBecomesFailureSoRulesCanTakeOver() = runBlocking {
        val slow = ProxyTransport { _, _, _ -> delay(5_000); ProxyHttpResponse(200, "{}") }
        val started = System.nanoTime()
        val result = classifier(slow, timeoutMs = 100).classify("앞유리", candidates)
        assertEquals(IntentClassification.Failure("proxy_timeout"), result)
        assertTrue("timeout must not wait for the transport", (System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun networkAndHttpErrorsBecomeFailures() = runBlocking {
        assertEquals(IntentClassification.Failure("proxy_request_failed"),
            classifier(ProxyTransport { _, _, _ -> throw IOException("reset") }).classify("앞유리", candidates))
        assertEquals(IntentClassification.Failure("proxy_request_failed"),
            classifier(ProxyTransport { _, _, _ -> throw UnknownHostException("no dns") }).classify("앞유리", candidates))
        assertEquals(IntentClassification.Failure("proxy_http_500"),
            classifier(ProxyTransport { _, _, _ -> ProxyHttpResponse(500, "") }).classify("앞유리", candidates))
        assertEquals(IntentClassification.Failure("proxy_http_429"),
            classifier(ProxyTransport { _, _, _ -> ProxyHttpResponse(429, "") }).classify("앞유리", candidates))
        assertEquals(IntentClassification.Failure("proxy_response_too_large"),
            classifier(ProxyTransport { _, _, _ -> throw ResponseTooLargeException() }).classify("앞유리", candidates))
    }

    @Test
    fun realHttpTransportConnectionRefusedBecomesFailure() = runBlocking {
        // Exercises HttpUrlConnectionProxyTransport against a closed local port (no external network).
        val result = GeminiFlashProxyIntentClassifier("https://127.0.0.1:9/classify", totalTimeoutMs = 3_000)
            .classify("앞유리", candidates)
        assertTrue("$result", result is IntentClassification.Failure)
    }

    @Test
    fun insecureEndpointBlankQueryAndNoCandidatesNeverCallTheNetwork() = runBlocking {
        var calls = 0
        val counting = ProxyTransport { _, _, _ -> calls++; ProxyHttpResponse(200, "{}") }
        assertEquals(IntentClassification.Failure("proxy_endpoint_must_be_https"),
            GeminiFlashProxyIntentClassifier("http://rendy-proxy.example.invalid", transport = counting).classify("q", candidates))
        assertTrue(GeminiFlashProxyIntentClassifier("https://user:pw@host.example/x", transport = counting)
            .classify("q", candidates) is IntentClassification.Failure)
        assertTrue(classifier(counting).classify("   ", candidates) is IntentClassification.Clarification)
        assertTrue(classifier(counting).classify("q", emptyList()) is IntentClassification.Clarification)
        assertEquals(0, calls)
    }

    @Test
    fun cancellationOfStaleQuestionPropagatesInsteadOfReturningAResult() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val hanging = ProxyTransport { _, _, _ -> entered.complete(Unit); delay(10_000); ProxyHttpResponse(200, "{}") }
        val job = async(start = CoroutineStart.UNDISPATCHED) { classifier(hanging, timeoutMs = 20_000).classify("q", candidates) }
        entered.await()
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun payloadValidationRejectsEverythingButTheExactSchema() {
        fun v(json: String) = GeminiFlashProxyIntentClassifier.validatePayload(json, allowed)
        assertEquals(IntentClassification.Classified("hazard_light", 0.8),
            v("""{"intent":"hazard_light","confidence":0.8,"requires_clarification":false}"""))
        val failures = mapOf(
            "not json" to "invalid_json",
            "```json\n{\"intent\":\"front_defog\",\"confidence\":0.9,\"requires_clarification\":false}\n```" to null,
            "[]" to "invalid_json_shape",
            "\"front_defog\"" to "invalid_json_shape",
            """{"intent":"invented","confidence":0.99,"requires_clarification":false}""" to "intent_not_allowed",
            """{"intent":"front_defog","confidence":0.9,"requires_clarification":false,"answer":"버튼은 왼쪽"}""" to "invalid_schema_fields",
            """{"intent":"front_defog","confidence":0.9}""" to "invalid_schema_fields",
            """{"intent":"front_defog","confidence":"0.9","requires_clarification":false}""" to "invalid_schema_types",
            """{"intent":7,"confidence":0.9,"requires_clarification":false}""" to "invalid_schema_types",
            """{"intent":"front_defog","confidence":0.9,"requires_clarification":"no"}""" to "invalid_schema_types",
            """{"intent":"front_defog","confidence":1.5,"requires_clarification":false}""" to "invalid_confidence",
            """{"intent":"front_defog","confidence":-0.1,"requires_clarification":false}""" to "invalid_confidence",
            """{"intent":null,"confidence":0.9,"requires_clarification":false}""" to "invalid_schema_types"
        )
        failures.forEach { (json, reason) ->
            val result = v(json)
            assertTrue("$json -> $result", result is IntentClassification.Failure)
            if (reason != null) assertEquals(json, reason, (result as IntentClassification.Failure).reason)
        }
        assertTrue(v("""{"intent":"UNKNOWN","confidence":0.9,"requires_clarification":true}""") is IntentClassification.Clarification)
        assertTrue(v("""{"intent":"front_defog","confidence":0.9,"requires_clarification":true}""") is IntentClassification.Clarification)
        assertTrue(v("""{"intent":"front_defog","confidence":0.4,"requires_clarification":false}""") is IntentClassification.Clarification)
    }
}
