package com.hyundai.rentalhelper.intent

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hyundai.rentalhelper.data.HyundaiDatabase
import com.hyundai.rentalhelper.data.VerificationOverlay
import java.io.File

data class IntentTestCase(
    val id: String,
    val query: String,
    val expectedIntent: String?,
    val category: String,
    val previousIntent: String? = null
)

object IntentTestFixtures {
    private val gson = Gson()

    val cases: List<IntentTestCase> by lazy { loadCases("/intent_classification_cases.json") }

    /** Fresh utterances not used while writing the proxy prompt (held-out check against overfitting). */
    val holdoutCases: List<IntentTestCase> by lazy { loadCases("/intent_holdout_cases.json") }

    private fun loadCases(resource: String): List<IntentTestCase> {
        val type = object : TypeToken<List<IntentTestCase>>() {}.type
        return IntentTestFixtures::class.java.getResourceAsStream(resource)!!
            .bufferedReader(Charsets.UTF_8).use { gson.fromJson<List<IntentTestCase>>(it, type) }
    }

    /** The real checked-in car DB (not a copy), so DB edits are covered by these tests. */
    val database: HyundaiDatabase by lazy {
        val file = sequenceOf(
            File("src/main/assets/hyundai_buttons_db.json"),
            File("app/src/main/assets/hyundai_buttons_db.json")
        ).firstOrNull { it.isFile } ?: error("Could not locate the checked-in car database JSON")
        file.reader(Charsets.UTF_8).use { gson.fromJson(it, HyundaiDatabase::class.java) }
    }

    /** The 33 original FAQ intent ids (the label space of the 72/24 fixtures). */
    val allIntentIds: Set<String> by lazy { database.faqIntents.mapTo(linkedSetOf()) { it.intent } }

    /** The checked-in field-level verification overlay (same asset the app loads). */
    val overlay: VerificationOverlay by lazy {
        val file = sequenceOf(
            File("src/main/assets/rendy_verification_overlay.json"),
            File("app/src/main/assets/rendy_verification_overlay.json")
        ).firstOrNull { it.isFile } ?: error("Could not locate the verification overlay JSON")
        file.reader(Charsets.UTF_8).use { gson.fromJson(it, VerificationOverlay::class.java) }
    }

    /** DB + overlay, as in the app. */
    val knowledge: RendyKnowledgeBase by lazy { RendyKnowledgeBase(database, overlay) }

    /** DB verification flags only (the state before the overlay), for comparisons. */
    val knowledgeDbOnly: RendyKnowledgeBase by lazy { RendyKnowledgeBase(database) }

    fun car(modelId: String) = database.models.first { it.modelId == modelId }

    /** Third, independently written held-out set for the v4.2 candidate space (51 intents). */
    val holdoutV42Cases: List<IntentTestCase> by lazy { loadCases("/intent_holdout_v42_cases.json") }

    fun reportDir(): File = sequenceOf(File("build"), File("app/build"))
        .firstOrNull { it.isDirectory }!!
        .resolve("reports/rendy-intent").apply { mkdirs() }
}

/**
 * Scripted stand-in for the LLM: returns the fixture's expected intent. It measures routing and DB safety,
 * NOT language understanding — classification accuracy can only come from the real Gemini proxy.
 */
class FixtureMockClassifier(cases: List<IntentTestCase>) : IntentClassifier {
    private val byKey = cases.associateBy { it.query to it.previousIntent }
    val seenContexts = mutableListOf<ClassificationContext>()

    override suspend fun classify(
        query: String,
        candidates: List<IntentCandidate>,
        context: ClassificationContext
    ): IntentClassification {
        seenContexts += context
        val expected = (byKey[query to context.previousIntentId] ?: byKey[query to null])?.expectedIntent
            ?: return IntentClassification.Clarification(0.2, "fixture_ambiguous_or_unsupported")
        if (candidates.none { it.id == expected }) return IntentClassification.Clarification(0.2, "fixture_answer_not_available")
        return IntentClassification.Classified(expected, 0.95)
    }
}
