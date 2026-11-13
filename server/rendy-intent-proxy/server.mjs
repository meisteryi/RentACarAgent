// RENDY intent proxy: classifies a Korean driver utterance into one allow-listed FAQ intent ID via Gemini.
// Zero dependencies (Node >= 20.12). The Gemini API key lives only in this process' environment / .env.
// The proxy never returns vehicle instructions: only {intent, confidence, requires_clarification} (/classify)
// or {dialogue_act, intent, confidence} (/turn: a follow-up turn classified with the conversation state).
//
//   node server.mjs                 start the server
//   node server.mjs --check-models  list models available to the configured key (key never printed)

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const envFile = path.join(here, '.env');
if (fs.existsSync(envFile)) process.loadEnvFile(envFile);

const config = {
  apiKey: (process.env.GEMINI_API_KEY || '').trim(),
  model: (process.env.GEMINI_MODEL || 'gemini-3.5-flash-lite').trim(),
  apiBase: (process.env.GEMINI_API_BASE || 'https://generativelanguage.googleapis.com').replace(/\/+$/, ''),
  token: (process.env.RENDY_PROXY_TOKEN || '').trim(),
  host: process.env.HOST || '127.0.0.1',
  port: Number(process.env.PORT || 8787),
  upstreamTimeoutMs: Number(process.env.UPSTREAM_TIMEOUT_MS || 5000),
  ratePerMinute: Number(process.env.RATE_LIMIT_PER_MINUTE || 60),
  dailyLimit: Number(process.env.DAILY_REQUEST_LIMIT || 3000),
  thinkingLevel: (process.env.GEMINI_THINKING_LEVEL || 'MINIMAL').trim(),
};

const UNKNOWN = 'UNKNOWN';
const ID_PATTERN = /^[a-z0-9_]{1,64}$/;
const MAX_BODY_BYTES = 32 * 1024;

const SYSTEM_INSTRUCTION = `You are the intent classifier of RENDY, a Korean in-car voice assistant for drivers of an unfamiliar rental car.
Input: one driver utterance transcribed by speech recognition (it may contain typos, spacing errors or mis-heard words; interpret it phonetically), a list of allowed intents, and optionally previous_intent.
Task: choose the single allowed intent whose car control the driver wants to find or operate.

Rules:
- Output only the JSON object required by the schema. Never answer the question or describe car controls.
- Use only an intent id from the allowed list, or "UNKNOWN".
- Indirect complaints count when the matching control is clear (e.g. a fogged windshield -> windshield defog).
- Return "UNKNOWN" with requires_clarification=true when: the utterance asks about two or more different controls; it is a vague reference ("그거", "이거", "그 기능") and no previous_intent is given; the requested feature is not in the allowed list; it asks for driving-safety or emergency advice rather than locating/operating a control; or it is unrelated to the car.
- Some allowed intents are assistant topics rather than car controls (their label says so). Choose such an intent when the utterance is about that topic, for example the driver's own current position or the way to a destination, instead of UNKNOWN.
- Return "UNKNOWN" with requires_clarification=true when the only clue is a generic word that fits several allowed controls and nothing else narrows it: "열선" alone (seat / steering wheel / rear window heater), "깜빡이" alone (turn signal / hazard), "습기"·"김"·"창문이 안 보여" without front or rear, "충전" alone (EV charge port / phone charging), "브레이크" alone.
- Rain or water drops on the glass means the wiper, not the defogger; fog/frost/condensation means the defogger.
- If previous_intent is given and the utterance is only a pronoun follow-up about the same thing ("이거 어떻게 꺼?", "그거 다시 알려줘"), return previous_intent.
- confidence is your probability (0..1) that the chosen intent is correct.`;

export const DIALOGUE_ACTS = ['NEW_QUESTION', 'REPEAT', 'NEED_DETAIL', 'CONFIRM', 'FOUND', 'DENY', 'CANCEL', 'UNCLEAR'];
const STAGES = ['NONE', 'ANSWERED', 'HELD_FOR_PARKING', 'RESUME_OFFERED', 'GUIDE_LOCATE', 'GUIDE_STEP', 'GUIDE_DONE'];
const DRIVING_STATES = ['DRIVING', 'PARKED', 'UNKNOWN'];

// Deliberately no Korean example phrases: the model must generalize from meaning and context, and the
// evaluation set (app/src/test/resources/dialogue_act_cases.json) stays unseen by the prompt.
const TURN_SYSTEM_INSTRUCTION = `You classify the dialogue act of a driver's latest utterance in a conversation with RENDY, a Korean in-car voice assistant for drivers of an unfamiliar rental car.
Input: the conversation state, the allowed car-feature intents, and the driver's latest utterance transcribed by speech recognition (it may contain recognition errors, missing spaces or mis-heard words; interpret it phonetically and by meaning).
Decide what the driver means by this utterance IN THIS CONVERSATION STATE. Output only the JSON object required by the schema. Never answer, never describe car controls, never change the conversation yourself.

Dialogue acts:
- NEW_QUESTION: a question or request about a car control or feature (or about an assistant topic in the allowed list), either a different one or a full new question about the current one (for example how to turn it off). Also choose the intent.
- REPEAT: the driver did not hear or catch what RENDY just said and wants the same thing said again.
- NEED_DETAIL: the driver wants more specific help with the current feature: cannot find it, does not know where it is, asks where exactly, asks to be walked through it, or asks how to tell whether it worked.
- CONFIRM: the driver accepts or agrees, typically to RENDY's yes/no question or offer (for example an offer to explain now). A plain acknowledgement of an explanation is also CONFIRM.
- FOUND: the driver reports having found or reached the control RENDY is guiding them to, or having done the current step and being ready for the next.
- DENY: the driver declines or answers no to RENDY's question or offer.
- CANCEL: the driver wants to stop the current guidance or end the conversation, including closing remarks such as thanks combined with saying it is enough.
- UNCLEAR: you cannot tell the act with reasonable confidence: noise, a cut-off fragment, unrelated chatter, or contradictory signals.

How context changes meaning:
- The same short interjection or filler word means different things in different states. Right after RENDY explained something (RENDY's last utterance was not a question), a bare questioning interjection means the driver missed it (REPEAT). After RENDY offered something, an interjection followed by acceptance means CONFIRM. While RENDY is guiding the driver to a control (stage GUIDE_LOCATE), an interjection followed by discovery means FOUND.
- stage GUIDE_LOCATE: RENDY asked whether the driver found the control. Saying it cannot be seen or found is NEED_DETAIL; a bare "no" is DENY.
- stage RESUME_OFFERED: RENDY offered to resume guidance that was paused while driving. Acceptance is CONFIRM; refusal or "later" is DENY.
- stage GUIDE_STEP: RENDY read one operation step. Having done it or asking for the next step is FOUND.
- A sentence that names a car feature or control is NEW_QUESTION even if it starts with an interjection, unless it only refers back to the current feature to say it was found or not found.
- Asking to hear the paused or current feature's guidance now, by referring back to it without naming a different feature, is NEED_DETAIL (not NEW_QUESTION).
- Asking where the driver or the car itself is right now, saying they are lost, or asking the way to a destination is not about the current feature: it is NEW_QUESTION with the allowed intent whose label covers it (UNKNOWN if none).
- Hesitation is UNCLEAR: thinking sounds, fillers, "wait"/"hold on", "let me see", or an unfinished sentence that does not itself accept, refuse, report finding, ask to stop, or ask something. This holds in every state, including right after RENDY asked a question. Asking RENDY to wait a moment is not CANCEL.
- CONFIRM needs an explicit acceptance, DENY an explicit refusal, FOUND an explicit report of finding the control or doing the step, CANCEL an explicit wish to stop or end. A yes or no that is immediately taken back or followed by "wait" is UNCLEAR.
- Do not guess CONFIRM or FOUND from weak evidence; use UNCLEAR when unsure.

Output fields:
- dialogue_act: one of the acts above.
- intent: for NEW_QUESTION, the allowed intent id the question is about (the current feature when it refers to it with a pronoun), or "UNKNOWN" if it fits none or several; for every other act, "UNKNOWN".
- confidence: your probability (0..1) that dialogue_act is correct.`;

// ---------------------------------------------------------------- validation

function validateRequest(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return 'body_not_object';
  const { query, allowed_intents: intents, previous_intent: previous } = body;
  if (typeof query !== 'string' || !query.trim() || query.length > 300) return 'invalid_query';
  if (!Array.isArray(intents) || intents.length < 1 || intents.length > 64) return 'invalid_allowed_intents';
  const ids = new Set();
  for (const it of intents) {
    if (!it || typeof it.id !== 'string' || !ID_PATTERN.test(it.id) || it.id === UNKNOWN) return 'invalid_intent_id';
    if (ids.has(it.id)) return 'duplicate_intent_id';
    ids.add(it.id);
    if (it.examples !== undefined && (!Array.isArray(it.examples) || it.examples.length > 30 ||
        it.examples.some((e) => typeof e !== 'string' || e.length > 40))) return 'invalid_examples';
    if (it.label !== undefined && it.label !== null && (typeof it.label !== 'string' || it.label.length > 80)) return 'invalid_label';
  }
  if (previous !== undefined && previous !== null && !ids.has(previous)) return 'invalid_previous_intent';
  return null;
}

const isOptString = (v, max) => v === undefined || v === null || (typeof v === 'string' && v.length <= max);
const isOptBool = (v) => v === undefined || v === null || typeof v === 'boolean';

/** /turn request: the /classify fields (without previous_intent) plus a `dialogue` state object. */
function validateTurnRequest(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return 'body_not_object';
  const base = validateRequest({ ...body, previous_intent: undefined });
  if (base) return base;
  const d = body.dialogue;
  if (!d || typeof d !== 'object' || Array.isArray(d)) return 'invalid_dialogue';
  if (!isOptString(d.last_rendy_utterance, 300)) return 'invalid_last_rendy_utterance';
  if (!isOptString(d.last_driver_question, 300)) return 'invalid_last_driver_question';
  if (!isOptString(d.car_model, 40)) return 'invalid_car_model';
  if (!isOptBool(d.rendy_asked_question) || !isOptBool(d.pending_detail)) return 'invalid_dialogue_flag';
  if (d.stage !== undefined && d.stage !== null && !STAGES.includes(d.stage)) return 'invalid_stage';
  if (d.driving_state !== undefined && d.driving_state !== null && !DRIVING_STATES.includes(d.driving_state)) return 'invalid_driving_state';
  if (d.current_intent !== undefined && d.current_intent !== null &&
      !body.allowed_intents.some((i) => i.id === d.current_intent)) return 'invalid_current_intent';
  return null;
}

/**
 * Strict /turn output check, mirrored on the app side. Only NEW_QUESTION carries an intent; a stray intent on
 * another act is normalized to UNKNOWN instead of being trusted. Returns the clean object or null.
 */
export function validateTurnOutput(obj, allowedIds) {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return null;
  if (Object.keys(obj).sort().join(',') !== 'confidence,dialogue_act,intent') return null;
  const { dialogue_act: act, intent, confidence } = obj;
  if (typeof act !== 'string' || !DIALOGUE_ACTS.includes(act) || typeof intent !== 'string') return null;
  if (typeof confidence !== 'number' || !Number.isFinite(confidence) || confidence < 0 || confidence > 1) return null;
  if (intent !== UNKNOWN && !allowedIds.has(intent)) return null;
  return { dialogue_act: act, intent: act === 'NEW_QUESTION' ? intent : UNKNOWN, confidence };
}

/** Strict output check, mirrored on the app side. Returns the clean object or null. */
export function validateModelOutput(obj, allowedIds) {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return null;
  const keys = Object.keys(obj).sort().join(',');
  if (keys !== 'confidence,intent,requires_clarification') return null;
  const { intent, confidence, requires_clarification: clarify } = obj;
  if (typeof intent !== 'string' || typeof clarify !== 'boolean') return null;
  if (typeof confidence !== 'number' || !Number.isFinite(confidence) || confidence < 0 || confidence > 1) return null;
  if (intent !== UNKNOWN && !allowedIds.has(intent)) return null;
  return { intent, confidence, requires_clarification: clarify };
}

// ---------------------------------------------------------------- Gemini call

function intentLines(body) {
  return body.allowed_intents.map((i) => {
    const label = i.label ? ` | ${i.label}` : '';
    const examples = (i.examples || []).slice(0, 30).join(', ');
    return `- ${i.id}${label} | examples: ${examples}`;
  }).join('\n');
}

const quote = (text) => `"""${String(text).replace(/"""/g, '"')}"""`;

function generationConfig(schema) {
  const cfg = { responseMimeType: 'application/json', responseJsonSchema: schema, maxOutputTokens: 256 };
  if (config.thinkingLevel && config.thinkingLevel !== 'NONE') cfg.thinkingConfig = { thinkingLevel: config.thinkingLevel };
  return cfg;
}

function buildGeminiRequest(body) {
  const ids = body.allowed_intents.map((i) => i.id);
  const userText = [
    'Allowed intents (id | control name | example phrases):',
    intentLines(body),
    `previous_intent: ${body.previous_intent || 'none'}`,
    `Utterance: ${quote(body.query)}`,
  ].join('\n');
  return {
    systemInstruction: { parts: [{ text: SYSTEM_INSTRUCTION }] },
    contents: [{ role: 'user', parts: [{ text: userText }] }],
    generationConfig: generationConfig({
      type: 'object',
      properties: {
        intent: { type: 'string', enum: [...ids, UNKNOWN] },
        confidence: { type: 'number', minimum: 0, maximum: 1 },
        requires_clarification: { type: 'boolean' },
      },
      required: ['intent', 'confidence', 'requires_clarification'],
      additionalProperties: false,
    }),
  };
}

/** One Gemini call per follow-up turn: dialogue act and, for NEW_QUESTION, the FAQ intent. */
export function buildTurnGeminiRequest(body) {
  const ids = body.allowed_intents.map((i) => i.id);
  const d = body.dialogue || {};
  const current = d.current_intent ? body.allowed_intents.find((i) => i.id === d.current_intent) : null;
  const userText = [
    'Conversation state:',
    `- rental car: ${d.car_model || 'unknown'}`,
    `- stage: ${d.stage || 'NONE'}`,
    `- current feature: ${current ? `${current.id}${current.label ? ` (${current.label})` : ''}` : 'none'}`,
    `- RENDY's last utterance: ${d.last_rendy_utterance ? quote(d.last_rendy_utterance) : 'none'}`,
    `- RENDY's last utterance was a question to the driver: ${d.rendy_asked_question ? 'yes' : 'no'}`,
    `- driver's last question: ${d.last_driver_question ? quote(d.last_driver_question) : 'none'}`,
    `- detailed guidance is paused until the car is parked: ${d.pending_detail ? 'yes' : 'no'}`,
    `- driving state: ${d.driving_state || 'UNKNOWN'}`,
    '',
    'Allowed intents for NEW_QUESTION (id | control name | example phrases):',
    intentLines(body),
    '',
    `Driver's latest utterance: ${quote(body.query)}`,
  ].join('\n');
  return {
    systemInstruction: { parts: [{ text: TURN_SYSTEM_INSTRUCTION }] },
    contents: [{ role: 'user', parts: [{ text: userText }] }],
    generationConfig: generationConfig({
      type: 'object',
      properties: {
        dialogue_act: { type: 'string', enum: DIALOGUE_ACTS },
        intent: { type: 'string', enum: [...ids, UNKNOWN] },
        confidence: { type: 'number', minimum: 0, maximum: 1 },
      },
      required: ['dialogue_act', 'intent', 'confidence'],
      additionalProperties: false,
    }),
  };
}

class UpstreamError extends Error {
  constructor(code, status) { super(code); this.code = code; this.status = status; }
}

async function callGemini(geminiRequest, validate) {
  const url = `${config.apiBase}/v1beta/models/${encodeURIComponent(config.model)}:generateContent`;
  let res;
  try {
    res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': config.apiKey },
      body: JSON.stringify(geminiRequest),
      signal: AbortSignal.timeout(config.upstreamTimeoutMs),
    });
  } catch (e) {
    throw new UpstreamError(e?.name === 'TimeoutError' ? 'upstream_timeout' : 'upstream_network', 504);
  }
  if (!res.ok) {
    let reason = '';
    try { reason = (await res.json())?.error?.status || ''; } catch { /* ignore */ }
    throw new UpstreamError(`upstream_http_${res.status}${reason ? `_${reason}` : ''}`, res.status === 429 ? 429 : 502);
  }
  const data = await res.json();
  const parts = data?.candidates?.[0]?.content?.parts || [];
  const text = parts.filter((p) => typeof p.text === 'string' && !p.thought).map((p) => p.text).join('').trim();
  if (!text) throw new UpstreamError(`upstream_empty_${data?.candidates?.[0]?.finishReason || 'no_candidate'}`, 502);
  let parsed;
  try { parsed = JSON.parse(text); } catch { throw new UpstreamError('upstream_invalid_json', 502); }
  const clean = validate(parsed);
  if (!clean) throw new UpstreamError('upstream_schema_violation', 502);
  return clean;
}

// ---------------------------------------------------------------- limits & auth

const buckets = new Map();
let day = new Date().toISOString().slice(0, 10);
let dailyCount = 0;

function allowRequest(ip) {
  const today = new Date().toISOString().slice(0, 10);
  if (today !== day) { day = today; dailyCount = 0; }
  if (dailyCount >= config.dailyLimit) return 'daily_limit';
  const now = Date.now();
  const b = buckets.get(ip) || { start: now, count: 0 };
  if (now - b.start >= 60_000) { b.start = now; b.count = 0; }
  if (b.count >= config.ratePerMinute) return 'rate_limited';
  b.count += 1;
  buckets.set(ip, b);
  dailyCount += 1;
  return null;
}

function authorized(req) {
  if (!config.token) return true;
  const header = req.headers.authorization || '';
  const expected = Buffer.from(`Bearer ${config.token}`);
  const given = Buffer.from(header);
  return given.length === expected.length && crypto.timingSafeEqual(given, expected);
}

// ---------------------------------------------------------------- HTTP

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
}

function log(fields) {
  // Never log the transcript, the request body, or the key.
  console.log(JSON.stringify({ t: new Date().toISOString(), ...fields }));
}

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new Error('body_too_large');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

export function createServer() {
  return http.createServer(async (req, res) => {
    const started = Date.now();
    const url = new URL(req.url, 'http://local');
    if (req.method === 'GET' && url.pathname === '/healthz') {
      return send(res, 200, { ok: true, model: config.model, keyConfigured: Boolean(config.apiKey) });
    }
    const route = req.method === 'POST' ? ROUTES[url.pathname] : undefined;
    if (!route) return send(res, 404, { error: 'not_found' });
    if (!authorized(req)) { log({ status: 401, reason: 'unauthorized' }); return send(res, 401, { error: 'unauthorized' }); }
    const limited = allowRequest(req.socket.remoteAddress || 'unknown');
    if (limited) { log({ status: 429, reason: limited }); return send(res, 429, { error: limited }); }
    if (!config.apiKey) { log({ status: 503, reason: 'key_not_configured' }); return send(res, 503, { error: 'key_not_configured' }); }

    let body;
    try { body = await readJson(req); } catch { return send(res, 400, { error: 'invalid_json' }); }
    const invalid = route.validate(body);
    if (invalid) { log({ status: 400, route: route.name, reason: invalid }); return send(res, 400, { error: invalid }); }

    try {
      const ids = new Set(body.allowed_intents.map((i) => i.id));
      const result = await callGemini(route.build(body), (parsed) => route.check(parsed, ids));
      log({ status: 200, route: route.name, model: config.model, ms: Date.now() - started, ...route.summary(body, result) });
      return send(res, 200, result);
    } catch (e) {
      const code = e instanceof UpstreamError ? e.code : 'internal_error';
      const status = e instanceof UpstreamError ? e.status : 500;
      log({ status, route: route.name, model: config.model, ms: Date.now() - started, reason: code });
      return send(res, status, { error: code });
    }
  });
}

// Logged fields are ids, enums and numbers only: never the transcript or RENDY's utterance.
const ROUTES = {
  '/v1/rendy/classify': {
    name: 'classify',
    validate: validateRequest,
    build: buildGeminiRequest,
    check: validateModelOutput,
    summary: (body, r) => ({ intent: r.intent, confidence: r.confidence, clarify: r.requires_clarification,
      candidates: body.allowed_intents.length, context: Boolean(body.previous_intent) }),
  },
  '/v1/rendy/turn': {
    name: 'turn',
    validate: validateTurnRequest,
    build: buildTurnGeminiRequest,
    check: validateTurnOutput,
    summary: (body, r) => ({ act: r.dialogue_act, intent: r.intent, confidence: r.confidence,
      stage: body.dialogue.stage || 'NONE', candidates: body.allowed_intents.length }),
  },
};

async function checkModels() {
  if (!config.apiKey) { console.error('GEMINI_API_KEY is not set (.env).'); process.exit(2); }
  const found = [];
  let pageToken = '';
  do {
    const res = await fetch(`${config.apiBase}/v1beta/models?pageSize=1000${pageToken ? `&pageToken=${pageToken}` : ''}`,
      { headers: { 'x-goog-api-key': config.apiKey } });
    if (!res.ok) { console.error(`models.list failed: HTTP ${res.status}`); process.exit(1); }
    const data = await res.json();
    for (const m of data.models || []) {
      if ((m.supportedGenerationMethods || []).includes('generateContent')) found.push(m.name.replace(/^models\//, ''));
    }
    pageToken = data.nextPageToken || '';
  } while (pageToken);
  const flash = found.filter((n) => /flash/.test(n) && !/(tts|image|live|audio|embedding)/.test(n)).sort();
  console.log(`generateContent models visible to this key: ${found.length}`);
  console.log(`text flash models:\n  ${flash.join('\n  ')}`);
  console.log(`configured GEMINI_MODEL=${config.model}: ${found.includes(config.model) ? 'AVAILABLE' : 'NOT FOUND'}`);
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  if (process.argv.includes('--check-models')) {
    await checkModels();
  } else {
    createServer().listen(config.port, config.host, () => {
      console.log(`RENDY intent proxy on http://${config.host}:${config.port} model=${config.model} ` +
        `key=${config.apiKey ? 'configured' : 'MISSING'} auth=${config.token ? 'token' : 'none'}`);
    });
  }
}
