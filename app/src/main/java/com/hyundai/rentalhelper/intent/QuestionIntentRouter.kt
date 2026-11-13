package com.hyundai.rentalhelper.intent

import com.hyundai.rentalhelper.data.HyundaiCarModel

sealed class QuestionRoute {
    /** The classifier chose an offered intent; [route] says where it leads for the selected car. */
    data class Answer(
        val intentId: String,
        val route: IntentRoute,
        val reportedConfidence: Double
    ) : QuestionRoute()

    /** A non-vehicle topic ([RendyAssistantIntents]); never routed through the vehicle DB. */
    data class Assistant(val intentId: String, val reportedConfidence: Double) : QuestionRoute()

    data class Clarify(val reason: String) : QuestionRoute()
    data class Fallback(val reason: String) : QuestionRoute()
}

/**
 * Classification plus per-car routing. It decides only WHICH intent and WHERE it leads; what may be spoken
 * is decided afterwards from the button's verification (see [RendyAnswerComposer]).
 */
class QuestionIntentRouter(
    private val classifier: IntentClassifier,
    private val knowledge: RendyKnowledgeBase
) {
    suspend fun route(
        query: String,
        carModel: HyundaiCarModel,
        context: ClassificationContext = ClassificationContext()
    ): QuestionRoute {
        val vehicle = knowledge.candidatesFor(carModel)
        if (vehicle.isEmpty()) return QuestionRoute.Clarify(REASON_NO_VERIFIED_INTENTS)
        val candidates = vehicle + RendyAssistantIntents.candidates
        val candidateIds = candidates.mapTo(mutableSetOf()) { it.id }
        // Only forward context that is itself an offered intent for this car.
        val safeContext = context.copy(previousIntentId = context.previousIntentId?.takeIf { it in candidateIds })

        return when (val classification = classifier.classify(query, candidates, safeContext)) {
            is IntentClassification.Classified -> {
                // Any classifier, including a misbehaving one, may only select from the offered candidates.
                if (classification.intentId !in candidateIds) return QuestionRoute.Clarify("intent_not_offered")
                if (RendyAssistantIntents.isAssistant(classification.intentId)) {
                    return QuestionRoute.Assistant(classification.intentId, classification.confidence)
                }
                QuestionRoute.Answer(
                    intentId = classification.intentId,
                    route = knowledge.route(carModel, classification.intentId),
                    reportedConfidence = classification.confidence
                )
            }
            is IntentClassification.Clarification -> QuestionRoute.Clarify(classification.reason)
            is IntentClassification.Failure -> QuestionRoute.Fallback(classification.reason)
        }
    }

    companion object {
        const val REASON_NO_VERIFIED_INTENTS = "no_verified_supported_intents"
    }
}
