package com.hyundai.rentalhelper.intent

import com.hyundai.rentalhelper.data.HyundaiCarModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class AnswerSource {
    /** Classified by the LLM proxy and resolved through the selected car's DB entry. */
    LLM,
    /** LLM off or failed; keyword rules picked the intent. */
    RULE_FALLBACK,
    /** Follow-up turn ("못 찾겠어", "찾았어", "됐어?") about the control answered last. */
    FOLLOW_UP,
    /** Ambiguous, multi-intent, unsupported, or unsafe to answer. */
    CLARIFICATION,
    /** No intent can be offered for the selected car at all. */
    UNAVAILABLE
}

/** Conversation state about the last answered control. Cleared on car change, timeout, cancel, or a new topic. */
data class DialogueState(
    val modelId: String,
    val intentId: String,
    val buttonId: String?,
    val tier: GuidanceTier?,
    val title: String,
    val lastSpoken: String,
    val updatedAtMs: Long,
    /** -1 before the first step was read. */
    val stepIndex: Int = -1,
    val notFoundCount: Int = 0,
    /** True once the driver said they found the control ("찾았어"). */
    val userConfirmedFound: Boolean = false,
    /** Coordinator request (one per STT result) that produced this state, for logs. */
    val requestId: Long? = null
)

sealed class DialogueStateUpdate {
    object Keep : DialogueStateUpdate()
    object Clear : DialogueStateUpdate()
    data class Set(val modelId: String, val intentId: String, val buttonId: String?, val tier: GuidanceTier?, val spoken: String) : DialogueStateUpdate()
    data class Advance(val stepIndex: Int, val notFoundCount: Int, val spoken: String) : DialogueStateUpdate()
    data class Touch(val spoken: String) : DialogueStateUpdate()
}

data class VoiceAnswer(
    val title: String,
    val text: String,
    val source: AnswerSource,
    val intentId: String?,
    val reason: String,
    val buttonId: String? = null,
    val tier: GuidanceTier? = null,
    /** Applied by [RendyQuestionPipeline.commit] only when this answer is actually delivered. */
    val stateUpdate: DialogueStateUpdate = DialogueStateUpdate.Keep
)

/**
 * Question answering for both modes (LLM classifier, or rules only when the flag is off).
 * The classifier only chooses an intent ID. What is spoken is decided by [RendyAnswerComposer] from the
 * selected car's verification data, so LLM failures, rule fallbacks and follow-ups share the same policy.
 *
 * [answer] has no side effects; [commit] applies the dialogue state once the answer is delivered. A new STT
 * session cancels an in-flight answer (coordinator) without touching the conversation, so "못 찾겠어" spoken in
 * the next session still refers to the previous control.
 */
class RendyQuestionPipeline(
    private val classifier: IntentClassifier,
    private val fallbackClassifier: IntentClassifier,
    private val knowledge: RendyKnowledgeBase,
    private val contextWindowMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Optional extra restriction (build flag); empty = DB verification alone decides. */
    private val restrictToIntents: Set<String> = emptySet(),
    /** Real vehicle signal when available; UNKNOWN otherwise (never guessed). */
    private val drivingState: () -> DrivingState = { DrivingState.UNKNOWN }
) {
    @Volatile private var state: DialogueState? = null

    suspend fun answer(query: String, carModel: HyundaiCarModel): VoiceAnswer {
        val active = activeState(carModel)
        val driving = drivingFor(query)
        knowledge.followUpKind(query)?.let { kind ->
            return RendyAnswerComposer.followUp(knowledge, carModel, kind, active, driving)
        }
        val pronounFollowUp = active != null && DEMONSTRATIVES.any { KeywordIntentMatcher.normalize(query).contains(it) }
        if (!pronounFollowUp) {
            knowledge.mandatoryClarification(query, carModel)?.let { question ->
                return VoiceAnswer(CLARIFICATION_TITLE, question, AnswerSource.CLARIFICATION, null,
                    "db_clarification_rule|mandatory", stateUpdate = DialogueStateUpdate.Clear)
            }
        }
        val context = ClassificationContext(previousIntentId = active?.intentId)
        val permission = RendyAnswerComposer.asksPermission(query)
        return when (val primary = safeRoute(QuestionIntentRouter(classifier, knowledge), query, carModel, context)) {
            is QuestionRoute.Answer -> compose(primary, carModel, driving, AnswerSource.LLM, "classified", permission)
            is QuestionRoute.Clarify -> clarificationFor(query, carModel, primary.reason)
            is QuestionRoute.Fallback -> {
                val reason = "classifier_failed:${primary.reason}"
                when (val rule = safeRoute(QuestionIntentRouter(fallbackClassifier, knowledge), query, carModel, context)) {
                    is QuestionRoute.Answer -> compose(rule, carModel, driving, AnswerSource.RULE_FALLBACK, reason, permission)
                    is QuestionRoute.Clarify -> clarificationFor(query, carModel, rule.reason, reason)
                    is QuestionRoute.Fallback -> clarificationFor(query, carModel, rule.reason, reason)
                }
            }
        }
    }

    /** Apply [answer]'s state change. Call only for the answer that is actually spoken. */
    fun commit(answer: VoiceAnswer, requestId: Long? = null) {
        val now = clock()
        state = when (val u = answer.stateUpdate) {
            DialogueStateUpdate.Keep -> state
            DialogueStateUpdate.Clear -> null
            is DialogueStateUpdate.Set -> DialogueState(u.modelId, u.intentId, u.buttonId, u.tier, answer.title, u.spoken, now, requestId = requestId)
            is DialogueStateUpdate.Advance -> state?.copy(
                stepIndex = u.stepIndex, notFoundCount = u.notFoundCount, lastSpoken = u.spoken, updatedAtMs = now,
                userConfirmedFound = state?.userConfirmedFound == true || u.stepIndex >= 0, requestId = requestId
            )
            is DialogueStateUpdate.Touch -> state?.copy(lastSpoken = u.spoken, updatedAtMs = now, requestId = requestId)
        }
    }

    suspend fun answerAndCommit(query: String, carModel: HyundaiCarModel): VoiceAnswer =
        answer(query, carModel).also { commit(it) }

    fun resetContext() {
        state = null
    }

    /** Snapshot for diagnostics/tests. */
    fun currentState(): DialogueState? = state

    private fun activeState(carModel: HyundaiCarModel): DialogueState? {
        val s = state ?: return null
        if (s.modelId != carModel.modelId) return null
        if (clock() - s.updatedAtMs > contextWindowMs) return null
        return s
    }

    private fun drivingFor(query: String): DrivingState {
        val signal = try { drivingState() } catch (_: Exception) { DrivingState.UNKNOWN }
        if (signal == DrivingState.DRIVING) return signal
        val q = KeywordIntentMatcher.normalize(query)
        return if (DRIVING_PHRASES.any { q.contains(it) }) DrivingState.DRIVING else signal
    }

    private fun compose(route: QuestionRoute.Answer, car: HyundaiCarModel, driving: DrivingState, source: AnswerSource, reason: String, permission: Boolean) =
        RendyAnswerComposer.initial(knowledge, car, route.route, driving, source, reason, restrictToIntents, permission)

    private suspend fun safeRoute(
        router: QuestionIntentRouter,
        query: String,
        carModel: HyundaiCarModel,
        context: ClassificationContext
    ): QuestionRoute = try {
        router.route(query, carModel, context)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        QuestionRoute.Fallback("classifier_exception")
    }

    private fun clarificationFor(query: String, car: HyundaiCarModel, reason: String, prefix: String? = null): VoiceAnswer =
        clarification(knowledge, query, car, listOfNotNull(prefix, reason).joinToString("|"), reason)

    companion object {
        const val CLARIFICATION_TITLE = "질문을 확인할게요"
        const val CLARIFICATION_TEXT = "어떤 기능을 말씀하시는지 기능 이름으로 한 번 더 말씀해 주세요."
        const val MULTI_INTENT_TEXT = "한 번에 한 가지 기능만 물어봐 주세요."
        const val UNAVAILABLE_TITLE = "음성 안내 준비 중"
        const val UNAVAILABLE_TEXT = "이 차량은 아직 확인된 음성 안내가 없어요. 화면 안내를 확인해 주세요."
        private val DEMONSTRATIVES = listOf("이거", "그거", "저거", "이것", "그것")
        private val DRIVING_PHRASES = listOf("달리는중", "주행중", "운전중", "운전하면서", "달리면서", "주행하면서")

        /** DB clarification question when one applies to this car, else a generic prompt. Clears context. */
        fun clarification(kb: RendyKnowledgeBase, query: String, car: HyundaiCarModel, fullReason: String, reason: String): VoiceAnswer {
            if (reason == QuestionIntentRouter.REASON_NO_VERIFIED_INTENTS) {
                return VoiceAnswer(UNAVAILABLE_TITLE, UNAVAILABLE_TEXT, AnswerSource.UNAVAILABLE, null, fullReason,
                    stateUpdate = DialogueStateUpdate.Clear)
            }
            val question = kb.clarificationQuestion(query, car)
            val text = question ?: if (reason.endsWith("multi_intent")) MULTI_INTENT_TEXT else CLARIFICATION_TEXT
            val why = if (question != null) "$fullReason|db_clarification_rule" else fullReason
            return VoiceAnswer(CLARIFICATION_TITLE, text, AnswerSource.CLARIFICATION, null, why, stateUpdate = DialogueStateUpdate.Clear)
        }

        /**
         * Stateless rules-only answer for synchronous callers (demo buttons, `matchVoiceQuery`).
         * Same verification policy; no follow-up context.
         */
        fun answerWithRulesOnce(kb: RendyKnowledgeBase, car: HyundaiCarModel, query: String): VoiceAnswer {
            val candidates = kb.candidatesFor(car)
            if (candidates.isEmpty()) return clarification(kb, query, car, QuestionIntentRouter.REASON_NO_VERIFIED_INTENTS, QuestionIntentRouter.REASON_NO_VERIFIED_INTENTS)
            kb.mandatoryClarification(query, car)?.let { question ->
                return VoiceAnswer(CLARIFICATION_TITLE, question, AnswerSource.CLARIFICATION, null, "db_clarification_rule|mandatory",
                    stateUpdate = DialogueStateUpdate.Clear)
            }
            val q = KeywordIntentMatcher.normalize(query)
            val driving = if (DRIVING_PHRASES.any { q.contains(it) }) DrivingState.DRIVING else DrivingState.UNKNOWN
            return when (val c = RuleBasedIntentClassifier(kb.intents).classifyNow(query, candidates, ClassificationContext())) {
                is IntentClassification.Classified ->
                    RendyAnswerComposer.initial(kb, car, kb.route(car, c.intentId), driving, AnswerSource.RULE_FALLBACK, "rules_only",
                        asksPermission = RendyAnswerComposer.asksPermission(query))
                is IntentClassification.Clarification -> clarification(kb, query, car, c.reason, c.reason)
                is IntentClassification.Failure -> clarification(kb, query, car, c.reason, c.reason)
            }
        }
    }
}

/**
 * Ensures at most one delivery per question and drops answers from superseded questions.
 * All methods are expected on the scope's (main) thread, so the check-and-deliver step cannot interleave.
 */
class RendyQuestionCoordinator(private val scope: CoroutineScope) {
    private val sequence = AtomicLong(0L)
    private var job: Job? = null

    fun submit(resolve: suspend () -> VoiceAnswer, deliver: (requestId: Long, answer: VoiceAnswer) -> Unit): Long {
        val requestId = sequence.incrementAndGet()
        job?.cancel()
        job = scope.launch {
            val answer = resolve()
            if (sequence.get() == requestId) deliver(requestId, answer)
        }
        return requestId
    }

    /** Invalidate any in-flight question, e.g. when a new STT session starts. Returns true if one was pending. */
    fun cancelPending(): Boolean {
        sequence.incrementAndGet()
        val wasActive = job?.isActive == true
        job?.cancel()
        job = null
        return wasActive
    }
}
