package com.hyundai.rentalhelper.intent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routing with scripted classifiers. Does not measure language understanding. */
class QuestionIntentRouterTest {
    private val db = IntentTestFixtures.database
    private val kb = IntentTestFixtures.knowledge
    private val cases = IntentTestFixtures.cases

    private fun always(id: String) = object : IntentClassifier {
        override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext) =
            IntentClassification.Classified(id, 0.99)
    }

    @Test
    fun fixtureHasEnoughNaturalLanguageCasesCoveringEveryCategory() {
        assertTrue("Need at least 60 cases, have ${cases.size}", cases.size >= 60)
        assertEquals("case ids must be unique", cases.size, cases.map { it.id }.toSet().size)
        val categories = cases.map { it.category }.toSet()
        listOf(
            "faq", "colloquial", "indirect", "stt_typo", "multi_intent", "ambiguous",
            "unsupported", "unsupported_no_db_intent", "safety", "context_missing", "context_followup"
        ).forEach { assertTrue("missing category $it", it in categories) }
        cases.mapNotNull { it.expectedIntent }.forEach { assertTrue("unknown intent $it", it in IntentTestFixtures.allIntentIds) }
    }

    @Test
    fun mockRoutingOnEveryCarRoutesExpectedIntentsToThatCarsOwnButtons() = runBlocking {
        val router = QuestionIntentRouter(FixtureMockClassifier(cases), kb)
        val lines = mutableListOf("model\tid\tcategory\texpected\tactual\troute\tok")
        var failures = 0
        for (car in db.models) {
            for (case in cases) {
                val route = router.route(case.query, car, ClassificationContext(case.previousIntent))
                val ok = when (val expected = case.expectedIntent) {
                    null -> route is QuestionRoute.Clarify
                    else -> route is QuestionRoute.Answer && route.intentId == expected && when (val r = route.route) {
                        is IntentRoute.Button -> r.buttonId in car.buttons
                        is IntentRoute.Redirect -> r.buttonId in car.buttons && r.targetButtonId in car.buttons
                        else -> true
                    }
                }
                if (!ok) failures++
                val actual = when (route) {
                    is QuestionRoute.Answer -> route.intentId
                    is QuestionRoute.Assistant -> route.intentId
                    is QuestionRoute.Clarify -> "CLARIFY(${route.reason})"
                    is QuestionRoute.Fallback -> "FALLBACK"
                }
                lines += "${car.modelId}\t${case.id}\t${case.category}\t${case.expectedIntent ?: "CLARIFY"}\t$actual\t${(route as? QuestionRoute.Answer)?.route}\t$ok"
            }
        }
        IntentTestFixtures.reportDir().resolve("mock_routing_all_models.tsv").writeText(lines.joinToString("\n"), Charsets.UTF_8)
        assertEquals("routing mismatches; see build/reports/rendy-intent/mock_routing_all_models.tsv", 0, failures)
    }

    @Test
    fun perModelRoutingFollowsModelRoutingActions() = runBlocking {
        suspend fun routeOf(intent: String, model: String) =
            (QuestionIntentRouter(always(intent), kb).route("q", IntentTestFixtures.car(model)) as QuestionRoute.Answer).route
        assertEquals(IntentRoute.NotApplicableNonEv("charge_door"), routeOf("charge_door", "SONATA"))
        assertEquals(IntentRoute.Button("charge_door", "charge_door"), routeOf("charge_door", "IONIQ5"))
        assertEquals(IntentRoute.Redirect("fuel_door", "fuel_door", "charge_door"), routeOf("fuel_door", "IONIQ5"))
        assertEquals(IntentRoute.Button("regen_paddle", "paddle_shift_left", viaFallback = true), routeOf("regen_paddle", "TUCSON"))
        assertTrue(routeOf("sunroof_switch", "IONIQ5") is IntentRoute.NoData)
        assertEquals(IntentRoute.Button("gear_type", "gear_shift"), routeOf("gear_type", "AVANTE"))
        // duplicate_of: Casper's EPB entry is the foot parking brake.
        assertEquals(IntentRoute.Button("electronic_parking_brake", "foot_parking_brake"), routeOf("electronic_parking_brake", "CASPER"))
        // proposed intent without model_routing: response_key, else no data.
        assertEquals(IntentRoute.Button("cruise_speed", "cruise_speed_toggle"), routeOf("cruise_speed", "TUCSON"))
        assertTrue(routeOf("memory_seat", "CASPER") is IntentRoute.NoData)
        // temperature: per-model control from the DB note.
        assertEquals(IntentRoute.Button("temperature", "temp_toggle_driver"), routeOf("temperature", "IONIQ5"))
        assertEquals(IntentRoute.Button("temperature", "temp_dial_driver"), routeOf("temperature", "TUCSON"))
        assertEquals(IntentRoute.Button("temperature", "climate_temp_dial"), routeOf("temperature", "CASPER"))
    }

    @Test
    fun misbehavingClassifierCannotSelectInventedIntents() = runBlocking {
        val car = IntentTestFixtures.car("IONIQ5")
        for (rogueId in listOf("invented_intent", "", "UNKNOWN", "front_defog ", "FRONT_DEFOG")) {
            val route = QuestionIntentRouter(always(rogueId), kb).route("아무 질문", car)
            assertTrue("'$rogueId' must not be answered: $route", route is QuestionRoute.Clarify)
        }
    }

    @Test
    fun candidatesAreTheMergedIntentSetWithinProxyLimits() = runBlocking {
        var offered: List<IntentCandidate> = emptyList()
        val spy = object : IntentClassifier {
            override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext): IntentClassification {
                offered = candidates
                return IntentClassification.Clarification(null, "spy")
            }
        }
        QuestionIntentRouter(spy, kb).route("q", IntentTestFixtures.car("CASPER"))
        // The 51 DB intents plus the non-vehicle assistant topics (driver location).
        assertEquals(kb.intents.map { it.intent } + RendyAssistantIntents.candidates.map { it.id }, offered.map { it.id })
        assertEquals(51 + RendyAssistantIntents.candidates.size, offered.size)
        assertTrue(offered.size <= 64)
        offered.forEach { c ->
            assertTrue(c.id, c.examples.isNotEmpty() && c.examples.size <= 30 && c.examples.all { it.length <= 40 })
            assertTrue(c.id, (c.label?.length ?: 0) <= 80)
        }
    }

    @Test
    fun contextIsForwardedOnlyWhenItIsAnOfferedIntent() = runBlocking {
        var seen: ClassificationContext? = null
        val spy = object : IntentClassifier {
            override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext): IntentClassification {
                seen = context
                return IntentClassification.Clarification(null, "spy")
            }
        }
        val router = QuestionIntentRouter(spy, kb)
        router.route("q", IntentTestFixtures.car("IONIQ5"), ClassificationContext("not_an_intent"))
        assertEquals(null, seen?.previousIntentId)
        router.route("q", IntentTestFixtures.car("IONIQ5"), ClassificationContext("hazard_light"))
        assertEquals("hazard_light", seen?.previousIntentId)
    }
}
