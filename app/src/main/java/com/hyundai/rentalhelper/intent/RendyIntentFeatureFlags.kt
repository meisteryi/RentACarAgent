package com.hyundai.rentalhelper.intent

import com.hyundai.rentalhelper.BuildConfig

/**
 * LLM routing is off by default until the FAQ response data has an explicit verification audit.
 * Values are injected at build time from `-P` flags or the git-ignored local.properties
 * (`rendy.llm.enabled`, `rendy.llm.proxyEndpoint`, `rendy.llm.verifiedIntents`).
 * Never put a Gemini API key here; the Android app calls only a first-party HTTPS proxy.
 */
object RendyIntentFeatureFlags {
    val ENABLE_LLM_CLASSIFIER: Boolean = BuildConfig.RENDY_LLM_ENABLED
    val GEMINI_PROXY_ENDPOINT: String = BuildConfig.RENDY_LLM_PROXY_ENDPOINT.trim()
    /** Shared app token for the proxy (not the Gemini key). Blank = no Authorization header. */
    val PROXY_TOKEN: String = BuildConfig.RENDY_LLM_PROXY_TOKEN.trim()
    /** Plain HTTP to 10.0.2.2/localhost is accepted only in debug builds, for the local proxy. */
    val ALLOW_LOCAL_HTTP_PROXY: Boolean = BuildConfig.DEBUG
    const val MINIMUM_MODEL_CONFIDENCE = 0.55
    const val PROXY_TIMEOUT_MS = 6_000L
    const val CONTEXT_WINDOW_MS = 60_000L

    /**
     * Since DB v4.2 the per-button `verification` data decides what may be spoken (see RendyKnowledgeBase).
     * This optional list only narrows it further: when non-empty, intents not listed never get a full
     * location/operation answer even if verified. Empty (default) = DB verification alone decides.
     */
    val VERIFIED_INTENT_IDS: Set<String> = parseIntentIds(BuildConfig.RENDY_LLM_VERIFIED_INTENTS)

    fun parseIntentIds(raw: String): Set<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
