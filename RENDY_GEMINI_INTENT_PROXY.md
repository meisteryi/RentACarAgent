# RENDY Gemini intent proxy contract

The Android app must call a first-party HTTPS endpoint. It must never contain a Gemini API key. `GeminiFlashProxyIntentClassifier` posts the STT transcript and the selected vehicle's allow-listed FAQ intents to the endpoint configured by `RendyIntentFeatureFlags.GEMINI_PROXY_ENDPOINT`.

## Request

```json
{
  "query": "앞유리가 뿌예서 안 보여",
  "model": "gemini-3.8-flash",
  "allowed_intents": [
    {"id": "front_defog", "examples": ["김서림", "앞유리 김"]}
  ],
  "previous_intent": "seat_vent_heat",
  "response_schema": {"type": "object", "...": "JSON Schema supplied by the app"}
}
```

`previous_intent` is optional. The app sends it only when the previous turn produced a DB-backed answer for the same car within 60 seconds, and only if it is one of `allowed_intents`. The proxy may use it to resolve pronoun follow-ups ("이거 어떻게 꺼?"). Without it, such questions must return `UNKNOWN`. `model` is only a hint; the proxy must enforce its own model allowlist.

## Response

Return only this JSON object, with no Markdown or additional properties:

```json
{"intent":"front_defog","confidence":0.91,"requires_clarification":false}
```

For an unclear, multi-intent, or unsupported question, return `intent: "UNKNOWN"` and `requires_clarification: true`. The app independently validates shape, confidence range, and membership in the requested intent IDs. Confidence is the model's estimate, not measured accuracy. The app maps an accepted ID to an exact selected-car `response_key`; the model must never produce a vehicle instruction or answer.

## Backend requirements

1. Require an authenticated application or gateway identity and rate limit requests. Do not make an unauthenticated public relay.
2. Store `GEMINI_API_KEY` in the backend's secret manager/environment. Never return it, log it, or pass it to the app.
3. Call Google's `generateContent` endpoint for the requested supported model and use `application/json` structured output with the supplied schema (or an equivalent server-owned schema narrowed to `allowed_intents`).
4. Validate the returned ID against the request allowlist and return only the three response fields. Return non-2xx on provider/network errors. The app then tries its keyword rules, but only accepts a rule match that is itself a verified intent with a stored answer for the selected car. Otherwise it asks the user to clarify.
5. Do not persist transcripts by default; document any retention and access controls before deployment.

Google documents JSON Schema constrained structured output for classification and lists `gemini-3.8-flash` as a stable model supporting structured output. The live endpoint, credentials, server deployment, authentication and retention policy are environment-specific and are not configured by this repository.

## Enabling (DB v4.2 verification policy)

Since DB v4.2.0-final, what may be spoken is decided per car and per button from `verification` (see `RendyKnowledgeBase` / `RendyAnswerComposer`), not by an intent allowlist:

| Button tier | Condition | Spoken |
|---|---|---|
| FULL | status VERIFIED/PARTIAL/CONFLICT **and** `location_verified` **and** `operation_verified`, policy direct/direct_with_caution/corrected_official | `spoken_answer` verbatim; follow-ups read one `operation_steps` entry per turn and `expected_result` (never claims success) |
| EXISTS_ONLY | evidenced status but location and/or operation unverified | fixed notice: feature is in the manual, location/operation not confirmed, check manual or rental company |
| UNVERIFIED | no evidence | fixed notice (+ DB safety note when `hedged_safety`) |
| NOT_APPLICABLE / redirect | DB says the car lacks it | absence sentence, redirect target only if FULL |

Field-level overlay (`app/src/main/assets/rendy_verification_overlay.json`, v4.2.1): for buttons whose v4.2 `spoken_answer` mixes verified and unverified content, the overlay holds TTS text for a single field (location `exact`/`component`, operation with steps, appearance) restating an official Korean manual quote (URL/PDF page recorded per field). Tiers then become LOCATION_ONLY / OPERATION_ONLY (that field is spoken plus a one-sentence "not confirmed" notice) or FULL (exact location + operation). US/overseas manuals are not used. The base DB file is unchanged.

`detailed_location` and `visual_landmarks` are never spoken: they have no per-field verification and contain details the DB notes mark as unconfirmed. In v4.2 only 19 of 237 buttons are FULL (IONIQ5: 1, TUCSON: 14, SONATA: 2, AVANTE: 1, CASPER: 1).

Configuration is injected at build time, from `-P` flags or the git-ignored `local.properties`. Never put an API key here:

```properties
rendy.llm.enabled=true
rendy.llm.proxyEndpoint=https://<your-proxy-host>/v1/rendy/classify
# optional: narrows FULL answers to these intents; empty = DB verification alone decides
rendy.llm.verifiedIntents=
```

Behaviour:

| State | Result |
|---|---|
| `enabled=false` (default) | keyword rules (no network) + the same verification policy and follow-up dialogue |
| enabled, endpoint missing or not HTTPS | same as `enabled=false` (logged warning) |
| proxy answers an intent ID | per-car `model_routing` -> button -> tier-based answer above |
| bare ambiguous phrase matching a DB `clarification_rules` entry ("깜빡이 켜줘") | the DB clarification question, before/independent of the classifier |
| proxy says UNKNOWN / low confidence | DB clarification question if one applies, else generic prompt |
| proxy timeout / network / bad JSON / 4xx / 5xx | keyword rules under the same policy; else clarification |

The app sends all 51 intents (33 FAQ + 18 accepted v4.2 proposals) as candidates; intents with no data for the car get a truthful "no data / not applicable" reply instead of a rephrase request.

## Measuring the live proxy

```bash
RENDY_INTENT_PROXY_URL=https://<your-proxy-host>/v1/rendy/classify ./gradlew :app:testDebugUnitTest --tests "*IntentEvaluationTest*"
```

Add `--tests "*RendyQaSuiteTest*"` for the 133-scenario QA suite. Reports are written to `app/build/reports/rendy-intent/` (`live_gemini*_legacy33*` = the earlier 33-intent space, `*_v42*` = 51 intents, `qa133_live*`). Without the variable, live tests are skipped; rule and mock reports always run.
