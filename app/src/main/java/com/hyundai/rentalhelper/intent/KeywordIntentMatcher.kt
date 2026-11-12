package com.hyundai.rentalhelper.intent

import com.hyundai.rentalhelper.data.FaqIntent

/**
 * Keyword rules used when the LLM is off or failed. Base algorithm unchanged from the pre-LLM engine
 * (whitespace-insensitive, lower-cased substring match, longest keyword wins, first-declared intent wins ties),
 * with fixes for the measured mis-matches. The intent JSON itself is not edited:
 *  1. Keywords shorter than 2 characters are ignored ("비" matched "내비" and "비춰" -> wiper).
 *  2. Stem match: a keyword of 4+ characters also matches without its last syllable at one point less
 *     ("사이드미러접기" covers "사이드미러 접어/접는", which used to lose to rear_defog's "사이드미러").
 *  3. Weak keywords (v4.2 `keyword_risks` plus "위험") name a family of controls, so on their own they only
 *     produce an ambiguity, never an answer.
 *  4. Two different controls joined by a conjunction ("~랑", "~하고", "둘 다", "~도") are a multi-intent
 *     question and are not answered.
 *  5. Spoken synonyms for the rear/front glass ("뒤창", "앞창") are normalized before matching.
 */
object KeywordIntentMatcher {
    data class Match(val intent: FaqIntent, val matchedKeyword: String)

    sealed class Result {
        data class Matched(val intent: FaqIntent, val keyword: String) : Result()
        data class Ambiguous(val reason: String) : Result()
        object NoMatch : Result()
    }

    const val MIN_KEYWORD_LENGTH = 2
    val WEAK_KEYWORDS = setOf("깜빡이", "열선", "충전", "차선", "카메라", "뒷문", "사이드미러", "위험")
    private val CONJUNCTIONS = listOf("랑", "하고", "그리고", "둘다", "모두", "같이", "및")
    private val SYNONYMS = listOf(
        Regex("뒤창(?!문)") to "뒷창문", Regex("뒷창(?!문)") to "뒷창문", Regex("뒤쪽유리") to "뒷유리",
        Regex("앞창(?!문)") to "앞유리"
    )

    fun normalize(query: String): String =
        SYNONYMS.fold(query.replace(Regex("\\s"), "").lowercase()) { acc, (re, to) -> re.replace(acc, to) }

    private data class Hit(val intentIndex: Int, val keyword: String, val score: Int, val start: Int, val end: Int, val weak: Boolean)

    fun analyze(intents: List<FaqIntent>, query: String): Result {
        val q = normalize(query)
        if (q.isEmpty()) return Result.NoMatch
        val hits = mutableListOf<Hit>()
        intents.forEachIndexed { index, intent ->
            for (raw in intent.keywords) {
                val kw = raw.replace(Regex("\\s"), "").lowercase()
                if (kw.length < MIN_KEYWORD_LENGTH) continue
                val weak = kw in WEAK_KEYWORDS
                val at = q.indexOf(kw)
                if (at >= 0) {
                    hits += Hit(index, kw, kw.length, at, at + kw.length, weak)
                } else if (kw.length >= 4 && kw.last() in '가'..'힣') {
                    val stem = kw.dropLast(1)
                    val s = q.indexOf(stem)
                    if (s >= 0) hits += Hit(index, kw, stem.length, s, s + stem.length, weak)
                }
            }
        }
        if (hits.isEmpty()) return Result.NoMatch

        val strong = hits.filter { !it.weak }
        if (strong.isEmpty()) {
            // Weak words alone: accept only when one intent owns every weak word heard and has 2+ of them
            // ("사이드미러 열선" -> rear_defog); otherwise ask.
            val byIntent = hits.groupBy { it.intentIndex }.mapValues { (_, v) -> v.map { it.keyword }.toSet() }
            val owner = byIntent.entries.firstOrNull { (_, kws) -> kws.size >= 2 && byIntent.values.all { kws.containsAll(it) } }
            return if (owner != null) Result.Matched(intents[owner.key], owner.value.maxBy { it.length })
            else Result.Ambiguous("weak_keyword_only")
        }

        // Longest wins; ties keep the first-declared intent (stable sort by declaration index).
        val best = strong.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.intentIndex }).first()
        val bestIntent = intents[best.intentIndex]
        val bestKeywords = bestIntent.keywords.mapTo(mutableSetOf()) { it.replace(Regex("\\s"), "").lowercase() }
        val bestSpans = hits.filter { it.intentIndex == best.intentIndex }

        if (CONJUNCTIONS.any { q.contains(it) } || strong.any { it.intentIndex != best.intentIndex && q.startsWith("도", it.end) }) {
            // Weak words count here ("시트 열선이랑 핸들 열선"), unless the chosen intent owns them too.
            val other = hits.any { h ->
                h.intentIndex != best.intentIndex && h.keyword !in bestKeywords &&
                    bestSpans.none { b -> h.start < b.end && b.start < h.end }
            }
            if (other) return Result.Ambiguous("multi_intent")
        }
        return Result.Matched(bestIntent, best.keyword)
    }

    /** Compatibility helper for callers that only need the chosen intent. */
    fun bestMatch(intents: List<FaqIntent>, query: String): Match? =
        (analyze(intents, query) as? Result.Matched)?.let { Match(it.intent, it.keyword) }

    /** The pre-v4.2 algorithm, verbatim, kept for regression reports. Not used for answers. */
    fun legacyBestMatch(intents: List<FaqIntent>, query: String): Match? {
        val normalizedQuery = query.replace(" ", "").lowercase()
        var best: Match? = null
        for (intent in intents) {
            for (kw in intent.keywords) {
                val cleanKw = kw.replace(" ", "").lowercase()
                if (cleanKw.isNotEmpty() && normalizedQuery.contains(cleanKw)) {
                    if (best == null || cleanKw.length > best.matchedKeyword.length) best = Match(intent, cleanKw)
                }
            }
        }
        return best
    }
}

/**
 * Keyword rules exposed through [IntentClassifier]. All intents are scored, so a stronger match to an intent
 * that is not offered becomes a clarification instead of being replaced by a weaker offered one.
 */
class RuleBasedIntentClassifier(private val allIntents: List<FaqIntent>) : IntentClassifier {
    override suspend fun classify(
        query: String,
        candidates: List<IntentCandidate>,
        context: ClassificationContext
    ): IntentClassification = classifyNow(query, candidates, context)

    /** Non-suspending form for synchronous callers. */
    fun classifyNow(query: String, candidates: List<IntentCandidate>, context: ClassificationContext): IntentClassification {
        return when (val result = KeywordIntentMatcher.analyze(allIntents, query)) {
            is KeywordIntentMatcher.Result.Matched ->
                if (candidates.none { it.id == result.intent.intent }) IntentClassification.Clarification(null, "rule_match_not_eligible")
                else IntentClassification.Classified(result.intent.intent, RULE_CONFIDENCE)
            is KeywordIntentMatcher.Result.Ambiguous -> IntentClassification.Clarification(null, "rule_${result.reason}")
            KeywordIntentMatcher.Result.NoMatch -> {
                // "이거 어떻게 꺼?" right after an answered question refers to that same control.
                val previous = context.previousIntentId?.takeIf { prev -> candidates.any { it.id == prev } }
                val normalized = KeywordIntentMatcher.normalize(query)
                if (previous != null && DEMONSTRATIVES.any { normalized.contains(it) }) {
                    IntentClassification.Classified(previous, RULE_CONFIDENCE)
                } else {
                    IntentClassification.Clarification(null, "rule_no_match")
                }
            }
        }
    }

    companion object {
        /** Keyword matches carry no calibrated probability; 1.0 only marks a deterministic rule hit. */
        const val RULE_CONFIDENCE = 1.0
        private val DEMONSTRATIVES = listOf("이거", "그거", "저거", "이것", "그것", "이기능", "그기능")
    }
}
