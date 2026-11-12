package com.hyundai.rentalhelper.intent

import com.hyundai.rentalhelper.data.FaqIntent
import com.hyundai.rentalhelper.data.HyundaiCarModel

/**
 * Pre-v4.2 resolver: manual intent allowlist -> raw `action_guide`. Kept only for reference; it ignores the
 * per-button verification data, so it must not be used for spoken answers. Use [RendyKnowledgeBase] and
 * [RendyAnswerComposer] instead.
 */
@Deprecated("Ignores v4.2 per-button verification; use RendyKnowledgeBase + RendyAnswerComposer")
object FaqIntentAnswerResolver {
    fun supportedIntents(
        carModel: HyundaiCarModel,
        faqIntents: List<FaqIntent>,
        verifiedIntentIds: Set<String>
    ): List<FaqIntent> = faqIntents.filter { intent ->
        intent.intent in verifiedIntentIds && hasAnswer(carModel, intent)
    }

    fun resolve(
        carModel: HyundaiCarModel,
        faqIntents: List<FaqIntent>,
        intentId: String,
        verifiedIntentIds: Set<String>
    ): Pair<String, String>? {
        if (intentId !in verifiedIntentIds) return null
        val intent = faqIntents.firstOrNull { it.intent == intentId } ?: return null
        if (!hasAnswer(carModel, intent)) return null
        if (intent.isGear) return "변속기(기어) 조작" to carModel.gearType
        val responseKey = intent.responseKey ?: return null
        val button = carModel.buttons[responseKey] ?: return null
        return button.name to button.actionGuide
    }

    /** DB-derived control name used only to help the classifier; null if the car has no answer. */
    fun label(carModel: HyundaiCarModel, intent: FaqIntent): String? {
        if (!hasAnswer(carModel, intent)) return null
        if (intent.isGear) return "변속기(기어)"
        return intent.responseKey?.let { carModel.buttons[it]?.name }
    }

    private fun hasAnswer(carModel: HyundaiCarModel, intent: FaqIntent): Boolean {
        if (intent.isGear) return carModel.gearType.isNotBlank()
        val key = intent.responseKey ?: return false
        val button = carModel.buttons[key] ?: return false
        return button.name.isNotBlank() && button.actionGuide.isNotBlank()
    }
}
