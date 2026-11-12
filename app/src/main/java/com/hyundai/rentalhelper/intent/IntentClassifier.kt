package com.hyundai.rentalhelper.intent

data class IntentCandidate(
    val id: String,
    val examples: List<String>,
    /** The selected car's DB control name for this intent (e.g. button name). Context only, never spoken. */
    val label: String? = null
)

/**
 * Conversation context for follow-ups such as "이거 어떻게 꺼?". [previousIntentId] is set only when the
 * previous turn produced a DB-backed answer for the same car inside the context window; otherwise null,
 * and a pronoun-only question must be answered with a clarification.
 */
data class ClassificationContext(val previousIntentId: String? = null)

sealed class IntentClassification {
    data class Classified(val intentId: String, val confidence: Double) : IntentClassification()
    data class Clarification(val confidence: Double?, val reason: String) : IntentClassification()
    data class Failure(val reason: String) : IntentClassification()
}

/** Classifies text only. It never creates vehicle instructions or answers. */
interface IntentClassifier {
    suspend fun classify(
        query: String,
        candidates: List<IntentCandidate>,
        context: ClassificationContext = ClassificationContext()
    ): IntentClassification
}
