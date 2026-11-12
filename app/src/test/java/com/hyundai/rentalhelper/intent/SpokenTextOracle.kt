package com.hyundai.rentalhelper.intent

import com.hyundai.rentalhelper.data.HyundaiCarModel
import com.hyundai.rentalhelper.data.HyundaiDatabase

/**
 * Independent check of spoken text against the raw DB (does not use the composer). Flags any sentence that
 * comes from a button's DB text when that button is not DB-verified for both fields, from never-spoken fields
 * (detailed_location, visual_landmarks, raw action_guide), or from another model, plus any other model's name.
 * Officially sourced overlay texts of this car are allowed.
 */
class SpokenTextOracle(car: HyundaiCarModel, kb: RendyKnowledgeBase, db: HyundaiDatabase) {
    private val allowed = mutableSetOf<String>()
    private val forbidden = mutableSetOf<String>()
    private val otherNames = db.models.filter { it.modelId != car.modelId }.map { it.modelNameKo }

    init {
        fun pieces(texts: List<String>) = texts.flatMap { ButtonGuidance.sentences(it) }.filter { it.length >= MIN_LEN }
        car.buttons.keys.forEach { id ->
            val g = kb.guidance(car, id)!!
            val b = g.button
            val dbSpeakable = listOfNotNull(b.spokenAnswer) + b.operationSteps.orEmpty()
            val dbVerified = g.tier == GuidanceTier.FULL && g.overlay == null
            if (dbVerified) allowed += pieces(dbSpeakable) + dbSpeakable
            else forbidden += pieces(dbSpeakable + listOfNotNull(b.location))
            g.overlay?.let { o ->
                val texts = listOfNotNull(o.location?.text, o.operation?.text, o.appearance?.text, o.operation?.result) + o.operation?.steps.orEmpty()
                allowed += pieces(texts) + texts
            }
            forbidden += pieces(listOfNotNull(b.detailedLocation, b.actionGuide) + b.visualLandmarks.orEmpty())
            // A NOT_APPLICABLE entry's absence sentence is the verified claim itself (e.g. no fuel door on an EV).
            if (g.tier == GuidanceTier.NOT_APPLICABLE) {
                b.spokenAnswer?.let { ButtonGuidance.sentences(it).firstOrNull() }?.takeIf { it.contains("없") }?.let { allowed += it }
            }
        }
        db.models.filter { it.modelId != car.modelId }.forEach { other ->
            forbidden += pieces(other.buttons.values.flatMap { listOfNotNull(it.spokenAnswer) + it.operationSteps.orEmpty() })
        }
        forbidden.removeAll(allowed)
    }

    fun violation(text: String): String? {
        // Remove verified sentences first, so a forbidden sentence that is merely a substring of a verified one
        // ("창문 잠금 버튼은 …" inside "뒷좌석 창문 잠금 버튼은 …") is not reported.
        val rest = allowed.sortedByDescending { it.length }.fold(text) { acc, s -> acc.replace(s, " ") }
        forbidden.firstOrNull { rest.contains(it) }?.let { return "unverified/foreign sentence '$it'" }
        otherNames.firstOrNull { text.contains(it) }?.let { return "other model name '$it'" }
        return null
    }

    companion object {
        private const val MIN_LEN = 12
    }
}
