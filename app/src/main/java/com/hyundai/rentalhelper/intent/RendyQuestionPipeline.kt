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
    /** [HyundaiCarModel.selectionId]: the DB model, or the design variant when one is selected. */
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
    val requestId: Long? = null,
    val stage: DialogueStage = DialogueStage.ANSWERED,
    /** The driver question that opened this topic. */
    val lastDriverQuestion: String? = null,
    /** The single post-parking resume offer for the current hold was already spoken. */
    val resumeOffered: Boolean = false,
    /** The one verified extra clue (appearance) was already given during guidance. */
    val extraClueGiven: Boolean = false
)

sealed class DialogueStateUpdate {
    object Keep : DialogueStateUpdate()
    object Clear : DialogueStateUpdate()
    data class Set(val modelId: String, val intentId: String, val buttonId: String?, val tier: GuidanceTier?, val spoken: String) : DialogueStateUpdate()
    data class Advance(val stepIndex: Int, val notFoundCount: Int, val spoken: String) : DialogueStateUpdate()
    data class Touch(val spoken: String) : DialogueStateUpdate()
    /** Same topic, new stage/flags. Unset fields keep their value. Also refreshes the context window. */
    data class Transition(
        val stage: DialogueStage? = null,
        val stepIndex: Int? = null,
        val resumeOffered: Boolean? = null,
        val extraClueGiven: Boolean? = null
    ) : DialogueStateUpdate()
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
    val stateUpdate: DialogueStateUpdate = DialogueStateUpdate.Keep,
    /** False for meta replies (re-ask, repeat): REPEAT keeps returning the last real answer. */
    val repeatable: Boolean = true,
    /** RENDY ends with a question to the driver ("찾으셨나요?", "알려드릴까요?"). */
    val asksQuestion: Boolean = false,
    /** Cancel / closing: the follow-up listening window should not reopen. */
    val endsConversation: Boolean = false,
    /** Dialogue act that produced this answer (null = first question without context). For logs. */
    val act: DialogueAct? = null,
    /** Filled by [RendyQuestionPipeline.answer]. */
    val driverQuery: String? = null,
    val modelId: String? = null
)

/** The last real answer RENDY spoke, kept verbatim for REPEAT. */
data class SpokenTurn(val text: String, val modelId: String?, val atMs: Long, val asksQuestion: Boolean, val title: String)

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
    private val drivingState: () -> DrivingState = { DrivingState.UNKNOWN },
    /**
     * Follow-up turns (any utterance while RENDY's last answer is still in context). The LLM proxy in LLM mode;
     * the DB-phrase fallback otherwise. It only labels the utterance; this class decides what happens.
     */
    private val turnClassifier: DialogueTurnClassifier = RuleDialogueTurnClassifier(knowledge),
    /** How long a "정차 후 알려드릴게요" hold stays valid while waiting for the car to be parked. */
    private val holdWindowMs: Long = 30 * 60_000L
) {
    @Volatile private var state: DialogueState? = null
    @Volatile private var lastTurn: SpokenTurn? = null
    private val ruleTurns = RuleDialogueTurnClassifier(knowledge)

    suspend fun answer(query: String, carModel: HyundaiCarModel): VoiceAnswer =
        answerInternal(query, carModel).copy(driverQuery = query, modelId = carModel.selectionId)

    private suspend fun answerInternal(query: String, carModel: HyundaiCarModel): VoiceAnswer {
        val active = activeState(carModel)
        val recent = recentTurn(carModel, active)
        val driving = drivingFor(query)
        if (active == null && recent == null) {
            // Nothing to follow up on: a DB follow-up phrase gets "which feature?", anything else is a question.
            knowledge.followUpKind(query)?.let { kind ->
                return RendyAnswerComposer.followUp(knowledge, carModel, kind, null, driving)
            }
            return answerQuestion(query, carModel, null, driving, useLlm = true)
        }

        val context = TurnContext(
            lastRendyUtterance = recent?.text,
            rendyAskedQuestion = recent?.asksQuestion == true,
            lastDriverQuestion = active?.lastDriverQuestion,
            currentIntentId = active?.intentId,
            stage = active?.stage ?: DialogueStage.NONE,
            pendingDetail = active?.stage == DialogueStage.HELD_FOR_PARKING || active?.stage == DialogueStage.RESUME_OFFERED,
            drivingState = driving,
            carModelName = carModel.modelNameKo
        )
        val candidates = knowledge.candidatesFor(carModel) + RendyAssistantIntents.candidates
        var failure: String? = null
        val turn = when (val t = safeTurn(query, candidates, context)) {
            is TurnClassification.Act -> t
            is TurnClassification.Failure -> {
                // Proxy down/timeout/invalid: DB phrases only, and no second LLM call for the question itself.
                failure = t.reason
                ruleTurns.classifyNow(query)
            }
        }
        val tag = listOfNotNull(failure?.let { "turn_failed:$it" }, "act=${turn.act}").joinToString("|")
        val reply = when (turn.act) {
            DialogueAct.NEW_QUESTION -> when {
                !turn.intentResolved -> answerQuestion(query, carModel, active, driving, useLlm = failure == null, prefix = failure?.let { "turn_failed:$it" })
                turn.intentId == null -> clarificationFor(query, carModel, "turn_unknown_intent")
                else -> answerResolvedIntent(turn.intentId, turn.confidence, query, carModel, active, driving)
            }
            DialogueAct.REPEAT -> repeat(recent, active)
            DialogueAct.CANCEL -> cancel(active)
            DialogueAct.UNCLEAR -> unclear(active)
            DialogueAct.NEED_DETAIL -> needDetail(carModel, active, driving, turn.ruleKind)
            DialogueAct.FOUND -> found(carModel, active, driving)
            DialogueAct.CONFIRM -> confirm(carModel, active, driving)
            DialogueAct.DENY -> deny(carModel, active, driving)
        }
        // A follow-up that could not be mapped (proxy down + unknown phrase, STT garbage, no fitting intent) asks
        // again but keeps the current topic, so the next "못 찾겠어" still refers to it.
        val keepTopic = active != null && turn.act == DialogueAct.NEW_QUESTION &&
            reply.source == AnswerSource.CLARIFICATION && reply.stateUpdate == DialogueStateUpdate.Clear
        val safe = if (keepTopic) reply.copy(stateUpdate = DialogueStateUpdate.Transition(), reason = "${reply.reason}|topic_kept") else reply
        return safe.copy(act = turn.act, reason = "$tag|${safe.reason}")
    }

    /** Apply [answer]'s state change. Call only for the answer that is actually spoken. */
    fun commit(answer: VoiceAnswer, requestId: Long? = null) {
        val now = clock()
        state = when (val u = answer.stateUpdate) {
            DialogueStateUpdate.Keep -> state
            DialogueStateUpdate.Clear -> null
            is DialogueStateUpdate.Set -> DialogueState(u.modelId, u.intentId, u.buttonId, u.tier, answer.title, u.spoken, now,
                requestId = requestId, lastDriverQuestion = answer.driverQuery)
            is DialogueStateUpdate.Advance -> state?.copy(
                stepIndex = u.stepIndex, notFoundCount = u.notFoundCount, lastSpoken = u.spoken, updatedAtMs = now,
                userConfirmedFound = state?.userConfirmedFound == true || u.stepIndex >= 0, requestId = requestId
            )
            is DialogueStateUpdate.Touch -> state?.copy(lastSpoken = u.spoken, updatedAtMs = now, requestId = requestId)
            is DialogueStateUpdate.Transition -> state?.let { s ->
                val step = u.stepIndex ?: s.stepIndex
                s.copy(
                    stage = u.stage ?: s.stage, stepIndex = step,
                    resumeOffered = u.resumeOffered ?: s.resumeOffered, extraClueGiven = u.extraClueGiven ?: s.extraClueGiven,
                    userConfirmedFound = s.userConfirmedFound || step >= 0,
                    lastSpoken = if (answer.repeatable) answer.text else s.lastSpoken, updatedAtMs = now, requestId = requestId
                )
            }
        }
        val modelId = answer.modelId ?: state?.modelId
        lastTurn = if (answer.repeatable) {
            SpokenTurn(answer.text, modelId, now, answer.asksQuestion, answer.title)
        } else {
            lastTurn?.copy(atMs = now) // a re-ask or repeat keeps the last real answer repeatable
        }
    }

    suspend fun answerAndCommit(query: String, carModel: HyundaiCarModel): VoiceAnswer =
        answer(query, carModel).also { commit(it) }

    /**
     * The one "아까 찾으시던 ..., 지금 알려드릴까요?" offer for guidance that was held while driving. Returned only
     * when the vehicle signal (not the driver's words) says PARKED, the hold is still valid and it was not offered
     * yet. The caller speaks it and [commit]s it; after that it is never offered again for this hold.
     */
    fun resumeOffer(carModel: HyundaiCarModel): VoiceAnswer? {
        val s = activeState(carModel) ?: return null
        if (s.stage != DialogueStage.HELD_FOR_PARKING || s.resumeOffered) return null
        if (signalDrivingState() != DrivingState.PARKED) return null
        val text = RendyDialogueText.resumeOffer(knowledge.featureName(s.intentId))
        return VoiceAnswer(s.title, text, AnswerSource.FOLLOW_UP, s.intentId, "resume_offer_after_parking", s.buttonId, s.tier,
            DialogueStateUpdate.Transition(stage = DialogueStage.RESUME_OFFERED, resumeOffered = true),
            asksQuestion = true, modelId = carModel.selectionId)
    }

    fun resetContext() {
        state = null
        lastTurn = null
    }

    /** Snapshot for diagnostics/tests. */
    fun currentState(): DialogueState? = state

    /** Last real answer spoken (REPEAT source). For diagnostics/tests. */
    fun lastSpokenTurn(): SpokenTurn? = lastTurn

    private fun activeState(carModel: HyundaiCarModel): DialogueState? {
        val s = state ?: return null
        if (s.modelId != carModel.selectionId) return null
        val held = s.stage == DialogueStage.HELD_FOR_PARKING || s.stage == DialogueStage.RESUME_OFFERED
        if (clock() - s.updatedAtMs > if (held) holdWindowMs else contextWindowMs) return null
        return s
    }

    private fun recentTurn(carModel: HyundaiCarModel, active: DialogueState?): SpokenTurn? {
        val t = lastTurn ?: return null
        if (t.modelId != null && t.modelId != carModel.selectionId) return null
        val window = if (active?.stage == DialogueStage.HELD_FOR_PARKING || active?.stage == DialogueStage.RESUME_OFFERED) holdWindowMs else contextWindowMs
        return t.takeIf { clock() - it.atMs <= window }
    }

    private fun signalDrivingState(): DrivingState = try { drivingState() } catch (_: Exception) { DrivingState.UNKNOWN }

    private fun drivingFor(query: String): DrivingState {
        val signal = signalDrivingState()
        if (signal == DrivingState.DRIVING) return signal
        val q = KeywordIntentMatcher.normalize(query)
        return if (DRIVING_PHRASES.any { q.contains(it) }) DrivingState.DRIVING else signal
    }

    private suspend fun safeTurn(query: String, candidates: List<IntentCandidate>, context: TurnContext): TurnClassification = try {
        turnClassifier.classifyTurn(query, candidates, context)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        TurnClassification.Failure("turn_classifier_exception")
    }

    // ---------------------------------------------------------------- questions

    private suspend fun answerQuestion(
        query: String,
        carModel: HyundaiCarModel,
        active: DialogueState?,
        driving: DrivingState,
        useLlm: Boolean,
        prefix: String? = null
    ): VoiceAnswer {
        mandatoryClarification(query, carModel, active)?.let { return it }
        val context = ClassificationContext(previousIntentId = active?.intentId)
        val permission = RendyAnswerComposer.asksPermission(query)
        if (useLlm) {
            when (val primary = safeRoute(QuestionIntentRouter(classifier, knowledge), query, carModel, context)) {
                is QuestionRoute.Answer -> return compose(primary, carModel, driving, AnswerSource.LLM, "classified", permission)
                is QuestionRoute.Assistant -> return RendyAssistantIntents.answer(primary.intentId, AnswerSource.LLM, "classified")
                is QuestionRoute.Clarify -> return clarificationFor(query, carModel, primary.reason)
                is QuestionRoute.Fallback -> return ruleQuestion(query, carModel, context, driving, permission, "classifier_failed:${primary.reason}")
            }
        }
        return ruleQuestion(query, carModel, context, driving, permission, prefix ?: "rules")
    }

    private suspend fun ruleQuestion(
        query: String, carModel: HyundaiCarModel, context: ClassificationContext, driving: DrivingState, permission: Boolean, reason: String
    ): VoiceAnswer = when (val rule = safeRoute(QuestionIntentRouter(fallbackClassifier, knowledge), query, carModel, context)) {
        is QuestionRoute.Answer -> compose(rule, carModel, driving, AnswerSource.RULE_FALLBACK, reason, permission)
        is QuestionRoute.Assistant -> RendyAssistantIntents.answer(rule.intentId, AnswerSource.RULE_FALLBACK, reason)
        is QuestionRoute.Clarify -> clarificationFor(query, carModel, rule.reason, reason)
        is QuestionRoute.Fallback -> clarificationFor(query, carModel, rule.reason, reason)
    }

    /** NEW_QUESTION whose intent the turn classifier already chose (one LLM call per turn). */
    private fun answerResolvedIntent(
        intentId: String, confidence: Double, query: String, carModel: HyundaiCarModel, active: DialogueState?, driving: DrivingState
    ): VoiceAnswer {
        if (RendyAssistantIntents.isAssistant(intentId)) return RendyAssistantIntents.answer(intentId, AnswerSource.LLM, "turn_classified")
        mandatoryClarification(query, carModel, active)?.let { return it }
        // Same guard as QuestionIntentRouter: only an offered intent for this car may be answered.
        if (knowledge.candidatesFor(carModel).none { it.id == intentId }) return clarificationFor(query, carModel, "intent_not_offered")
        val route = QuestionRoute.Answer(intentId, knowledge.route(carModel, intentId), confidence)
        return compose(route, carModel, driving, AnswerSource.LLM, "turn_classified", RendyAnswerComposer.asksPermission(query))
    }

    private fun mandatoryClarification(query: String, carModel: HyundaiCarModel, active: DialogueState?): VoiceAnswer? {
        val pronounFollowUp = active != null && DEMONSTRATIVES.any { KeywordIntentMatcher.normalize(query).contains(it) }
        if (pronounFollowUp) return null
        return knowledge.mandatoryClarification(query, carModel)?.let { question ->
            VoiceAnswer(CLARIFICATION_TITLE, question, AnswerSource.CLARIFICATION, null,
                "db_clarification_rule|mandatory", stateUpdate = DialogueStateUpdate.Clear)
        }
    }

    // ---------------------------------------------------------------- follow-up acts (app-side policy)

    private fun noContext(why: String) = VoiceAnswer(CLARIFICATION_TITLE, RendyAnswerComposer.NO_CONTEXT_TEXT,
        AnswerSource.CLARIFICATION, null, "follow_up_without_context:$why", stateUpdate = DialogueStateUpdate.Clear)

    private fun reply(
        s: DialogueState, text: String, why: String, update: DialogueStateUpdate,
        repeatable: Boolean = true, asksQuestion: Boolean = false, ends: Boolean = false
    ) = VoiceAnswer(s.title, text, AnswerSource.FOLLOW_UP, s.intentId, why, s.buttonId, s.tier, update,
        repeatable = repeatable, asksQuestion = asksQuestion, endsConversation = ends)

    /** Verbatim: the exact text of RENDY's last real answer, never regenerated. */
    private fun repeat(recent: SpokenTurn?, active: DialogueState?): VoiceAnswer {
        val t = recent ?: return noContext("REPEAT")
        val update = if (active != null) DialogueStateUpdate.Transition() else DialogueStateUpdate.Keep
        return VoiceAnswer(t.title, t.text, AnswerSource.FOLLOW_UP, active?.intentId, "repeat_verbatim", active?.buttonId, active?.tier,
            update, repeatable = false, asksQuestion = t.asksQuestion)
    }

    private fun cancel(active: DialogueState?): VoiceAnswer =
        if (active != null) {
            reply(active, RendyAnswerComposer.CANCEL_TEXT, "cancel", DialogueStateUpdate.Clear, repeatable = false, ends = true)
        } else {
            VoiceAnswer(CLARIFICATION_TITLE, RendyAnswerComposer.CANCEL_TEXT, AnswerSource.FOLLOW_UP, null,
                "cancel", stateUpdate = DialogueStateUpdate.Clear, repeatable = false, endsConversation = true)
        }

    /** Short re-ask; the conversation (and what REPEAT would say) is unchanged. */
    private fun unclear(active: DialogueState?): VoiceAnswer =
        VoiceAnswer(active?.title ?: CLARIFICATION_TITLE, RendyDialogueText.UNCLEAR, AnswerSource.CLARIFICATION, active?.intentId,
            "unclear_reask", stateUpdate = if (active != null) DialogueStateUpdate.Transition() else DialogueStateUpdate.Keep,
            repeatable = false)

    private fun guidanceOf(car: HyundaiCarModel, s: DialogueState): ButtonGuidance? {
        val g = s.buttonId?.let { knowledge.guidance(car, it) } ?: return null
        return g.takeIf { it.tier.speakable && s.tier?.speakable == true }
    }

    /**
     * Not confirmed parked (DRIVING or UNKNOWN): remember the feature and stage and promise to continue after
     * parking. Each hold allows exactly one resume offer ([resumeOffer] moves the stage to RESUME_OFFERED).
     */
    private fun hold(s: DialogueState, driving: DrivingState, why: String): VoiceAnswer {
        val text = if (driving == DrivingState.DRIVING) RendyDialogueText.HOLD_DRIVING else RendyDialogueText.HOLD_UNKNOWN
        return reply(s, text, "hold_until_parked:$why|driving=$driving",
            DialogueStateUpdate.Transition(stage = DialogueStage.HELD_FOR_PARKING, resumeOffered = false))
    }

    /** Parked: the verified location (with its verified appearance when both are known), then "찾으셨나요?". */
    private fun startGuidance(s: DialogueState, g: ButtonGuidance, why: String): VoiceAnswer {
        val location = g.locationSentence
        val clue = (if (g.exactWithAppearance) "$location ${g.appearanceSentence}" else location) ?: g.appearanceSentence
            ?: return reply(s, RendyAnswerComposer.LOCATION_UNKNOWN_TEXT, "$why|guide_no_verified_location",
                DialogueStateUpdate.Transition(stage = DialogueStage.ANSWERED))
        val notice = when {
            location == null -> ButtonGuidance.NOTICE_LOCATION_UNCONFIRMED
            !g.locationIsExact -> ButtonGuidance.NOTICE_EXACT_POSITION_UNCONFIRMED
            else -> null
        }
        val text = listOfNotNull(clue, notice, RendyDialogueText.FOUND_QUESTION).joinToString(" ")
        return reply(s, text, "$why|guide_locate", DialogueStateUpdate.Transition(
            stage = DialogueStage.GUIDE_LOCATE, stepIndex = -1, extraClueGiven = location == null), asksQuestion = true)
    }

    /** Cannot find it during guidance: one more VERIFIED clue if the DB has one, never the same sentence again. */
    private fun notFoundDuringGuidance(s: DialogueState, g: ButtonGuidance): VoiceAnswer {
        // The appearance was already spoken with the location when both are verified; then the extra clue is the
        // entry's own verified clue (e.g. the neighbouring button not to confuse it with).
        val extra = if (g.exactWithAppearance) g.clueSentence else g.appearanceSentence ?: g.clueSentence
        if (!s.extraClueGiven && extra != null && extra != g.locationSentence) {
            return reply(s, "$extra ${RendyDialogueText.FOUND_QUESTION}", "guide_extra_clue",
                DialogueStateUpdate.Transition(extraClueGiven = true), asksQuestion = true)
        }
        return reply(s, RendyAnswerComposer.NOT_FOUND_AGAIN_TEXT, "guide_no_more_verified_clues",
            DialogueStateUpdate.Transition(extraClueGiven = true))
    }

    /** Next verified operation step, one per turn; after the last one, the result check (never claims success). */
    private fun nextStep(s: DialogueState, g: ButtonGuidance): VoiceAnswer {
        val steps = g.steps
        if (steps.isEmpty()) {
            return reply(s, RendyAnswerComposer.OPERATION_UNKNOWN_TEXT, "guide_operation_unverified",
                DialogueStateUpdate.Transition(stage = DialogueStage.GUIDE_DONE))
        }
        val next = s.stepIndex + 1
        if (next < steps.size) {
            return reply(s, steps[next], "guide_step_${next + 1}_of_${steps.size}",
                DialogueStateUpdate.Transition(stage = DialogueStage.GUIDE_STEP, stepIndex = next))
        }
        return reply(s, doneCheck(g), "guide_steps_complete",
            DialogueStateUpdate.Transition(stage = DialogueStage.GUIDE_DONE, stepIndex = steps.size))
    }

    private fun doneCheck(g: ButtonGuidance): String =
        g.expectedResult?.let { "${RendyAnswerComposer.CANNOT_SEE_CAR} $it" } ?: RendyAnswerComposer.GENERIC_DONE_CHECK

    private fun closing(s: DialogueState) =
        reply(s, RendyDialogueText.CLOSING, "guide_closed", DialogueStateUpdate.Clear, ends = true)

    private fun needDetail(car: HyundaiCarModel, s: DialogueState?, driving: DrivingState, ruleKind: FollowUpKind?): VoiceAnswer {
        if (s == null) return noContext("NEED_DETAIL")
        if (ruleKind == FollowUpKind.DONE_CHECK) return RendyAnswerComposer.followUp(knowledge, car, FollowUpKind.DONE_CHECK, s, driving)
        val g = guidanceOf(car, s)
            ?: return RendyAnswerComposer.followUp(knowledge, car, FollowUpKind.NOT_FOUND, s, driving) // unverified -> escalate
        if (s.stage == DialogueStage.GUIDE_DONE) return reply(s, doneCheck(g), "guide_done_check", DialogueStateUpdate.Transition())
        if (g.locationSentence == null && g.appearanceSentence == null && s.stage != DialogueStage.GUIDE_STEP) {
            // Nothing verified to show even after parking: say so now instead of promising a walkthrough.
            return reply(s, RendyAnswerComposer.LOCATION_UNKNOWN_TEXT, "no_verified_location", DialogueStateUpdate.Transition())
        }
        if (driving != DrivingState.PARKED) return hold(s, driving, "NEED_DETAIL")
        return when (s.stage) {
            DialogueStage.GUIDE_LOCATE -> notFoundDuringGuidance(s, g)
            DialogueStage.GUIDE_STEP -> reply(s, RendyDialogueText.NO_MORE_STEP_DETAIL, "guide_step_no_more_detail", DialogueStateUpdate.Transition())
            else -> startGuidance(s, g, "need_detail")
        }
    }

    private fun found(car: HyundaiCarModel, s: DialogueState?, driving: DrivingState): VoiceAnswer {
        if (s == null) return noContext("FOUND")
        val g = guidanceOf(car, s) ?: return RendyAnswerComposer.followUp(knowledge, car, FollowUpKind.FOUND, s, driving)
        if (g.steps.isEmpty() && s.stage != DialogueStage.GUIDE_DONE) {
            // No verified operation: say so now (in any driving state) instead of holding for nothing.
            return reply(s, RendyAnswerComposer.OPERATION_UNKNOWN_TEXT, "operation_unverified",
                DialogueStateUpdate.Transition(stage = DialogueStage.GUIDE_DONE))
        }
        return when (s.stage) {
            DialogueStage.GUIDE_DONE -> closing(s)
            DialogueStage.HELD_FOR_PARKING ->
                if (driving == DrivingState.PARKED) nextStep(s, g)
                else reply(s, RendyDialogueText.FOUND_WHILE_HELD, "found_while_held", DialogueStateUpdate.Transition())
            else -> if (driving != DrivingState.PARKED) hold(s, driving, "FOUND") else nextStep(s, g)
        }
    }

    private fun confirm(car: HyundaiCarModel, s: DialogueState?, driving: DrivingState): VoiceAnswer {
        if (s == null) return ack(null)
        val g = guidanceOf(car, s)
        return when (s.stage) {
            DialogueStage.RESUME_OFFERED ->
                if (g == null) RendyAnswerComposer.followUp(knowledge, car, FollowUpKind.NOT_FOUND, s, driving)
                else if (driving != DrivingState.PARKED) hold(s, driving, "CONFIRM")
                else startGuidance(s, g, "resume_confirmed")
            // "찾으셨나요?" -> yes, or "했어" after a step: same as FOUND.
            DialogueStage.GUIDE_LOCATE, DialogueStage.GUIDE_STEP -> found(car, s, driving)
            // "그거 지금 알려줘" while held: a detail request (holds again unless parked).
            DialogueStage.HELD_FOR_PARKING -> needDetail(car, s, driving, null)
            DialogueStage.GUIDE_DONE -> closing(s)
            else -> ack(s)
        }
    }

    private fun deny(car: HyundaiCarModel, s: DialogueState?, driving: DrivingState): VoiceAnswer {
        if (s == null) return ack(null)
        return when (s.stage) {
            DialogueStage.RESUME_OFFERED -> reply(s, RendyDialogueText.DECLINED, "resume_declined",
                DialogueStateUpdate.Transition(stage = DialogueStage.ANSWERED))
            // "찾으셨나요?" -> "아니": not found yet.
            DialogueStage.GUIDE_LOCATE -> needDetail(car, s, driving, null)
            else -> reply(s, RendyDialogueText.DECLINED, "deny_ack", DialogueStateUpdate.Transition(), repeatable = false)
        }
    }

    private fun ack(s: DialogueState?): VoiceAnswer =
        VoiceAnswer(s?.title ?: CLARIFICATION_TITLE, RendyDialogueText.ACK, AnswerSource.FOLLOW_UP, s?.intentId, "ack",
            s?.buttonId, s?.tier, if (s != null) DialogueStateUpdate.Transition() else DialogueStateUpdate.Keep, repeatable = false)

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
