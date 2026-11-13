package com.hyundai.rentalhelper.intent

/**
 * What the driver means by a follow-up utterance, decided from its meaning AND the conversation state
 * ("어?" after an explanation = REPEAT, "어, 알려줘" after an offer = CONFIRM). The app decides what happens.
 */
enum class DialogueAct { NEW_QUESTION, REPEAT, NEED_DETAIL, CONFIRM, FOUND, DENY, CANCEL, UNCLEAR }

/** Where the conversation about the current feature is. Only the app changes it (never the model). */
enum class DialogueStage {
    /** No feature in context. */
    NONE,
    /** A first answer about the feature was spoken. */
    ANSWERED,
    /** Detailed guidance was requested while not confirmed parked; waiting for a parked signal. */
    HELD_FOR_PARKING,
    /** "아까 찾으시던 ..., 지금 알려드릴까요?" was asked after parking. */
    RESUME_OFFERED,
    /** Location was given and RENDY asked "찾으셨나요?". */
    GUIDE_LOCATE,
    /** One operation step was read; waiting for the driver. */
    GUIDE_STEP,
    /** All steps were read and the result check was given. */
    GUIDE_DONE
}

/** Conversation state sent with a follow-up turn. Contains no vehicle instructions beyond what RENDY already said. */
data class TurnContext(
    val lastRendyUtterance: String?,
    val rendyAskedQuestion: Boolean,
    val lastDriverQuestion: String?,
    val currentIntentId: String?,
    val stage: DialogueStage,
    val pendingDetail: Boolean,
    val drivingState: DrivingState,
    val carModelName: String
)

sealed class TurnClassification {
    /**
     * For [DialogueAct.NEW_QUESTION]: when [intentResolved] the classifier already chose the FAQ intent
     * ([intentId], or null = none fits -> clarification); otherwise the question classifier decides.
     * [ruleKind] is set only by the offline DB-phrase fallback.
     */
    data class Act(
        val act: DialogueAct,
        val intentId: String?,
        val confidence: Double,
        val ruleKind: FollowUpKind? = null,
        val intentResolved: Boolean = false
    ) : TurnClassification()

    data class Failure(val reason: String) : TurnClassification()
}

/** Fixed dialogue sentences. None of them contains a location or an operation step (those come only from the DB). */
object RendyDialogueText {
    const val HOLD_DRIVING = "지금은 운전에 집중해 주세요. 정차하면 차근차근 알려드릴게요."
    const val HOLD_UNKNOWN = "지금은 차가 멈춰 있는지 확인되지 않아요. 정차가 확인되면 차근차근 알려드릴게요."
    const val FOUND_QUESTION = "찾으셨나요?"
    const val FOUND_WHILE_HELD = "네, 조작 방법은 차를 세운 뒤에 알려드릴게요."
    const val NO_MORE_STEP_DETAIL = "이 단계는 더 자세히 확인된 설명이 아직 없어요. 설명서나 렌터카 업체에 확인해 주세요."
    const val ACK = "네, 더 궁금한 게 있으면 말씀해 주세요."
    const val DECLINED = "알겠어요. 필요하면 다시 물어봐 주세요."
    const val UNCLEAR = "잘 못 알아들었어요. 한 번만 다시 말씀해 주세요."
    const val CLOSING = "도움이 됐길 바라요. 필요하면 다시 불러 주세요."

    fun resumeOffer(featureName: String) = "아까 찾으시던 $featureName, 지금 알려드릴까요?"
}

/** Classifies a follow-up turn. It never creates answers or changes the dialogue state. */
interface DialogueTurnClassifier {
    suspend fun classifyTurn(query: String, candidates: List<IntentCandidate>, context: TurnContext): TurnClassification
}

/**
 * Offline fallback used when the LLM is off or the proxy failed. It only recognizes the DB's own
 * `follow_up_phrases` (exact phrase, see [RendyKnowledgeBase.followUpKind]); everything else is passed on as a
 * question. It cannot understand yes/no answers, so offers and "찾으셨나요?" need the LLM for natural replies.
 */
class RuleDialogueTurnClassifier(private val knowledge: RendyKnowledgeBase) : DialogueTurnClassifier {
    override suspend fun classifyTurn(query: String, candidates: List<IntentCandidate>, context: TurnContext): TurnClassification =
        classifyNow(query)

    fun classifyNow(query: String): TurnClassification.Act {
        val kind = knowledge.followUpKind(query)
        val act = when (kind) {
            FollowUpKind.NOT_FOUND, FollowUpKind.DONE_CHECK -> DialogueAct.NEED_DETAIL
            FollowUpKind.FOUND -> DialogueAct.FOUND
            FollowUpKind.REPEAT -> DialogueAct.REPEAT
            FollowUpKind.CANCEL -> DialogueAct.CANCEL
            null -> DialogueAct.NEW_QUESTION
        }
        return TurnClassification.Act(act, null, 1.0, kind)
    }
}
