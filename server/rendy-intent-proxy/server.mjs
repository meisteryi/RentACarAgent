// RENDY intent proxy: classifies a Korean driver utterance into one allow-listed FAQ intent ID via Gemini.
// Zero dependencies (Node >= 20.12). The Gemini API key lives only in this process' environment / .env.
// The proxy never returns vehicle instructions: only {intent, confidence, requires_clarification}.
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
- Return "UNKNOWN" with requires_clarification=true when the only clue is a generic word that fits several allowed controls and nothing else narrows it: "열선" alone (seat / steering wheel / rear window heater), "깜빡이" alone (turn signal / hazard), "습기"·"김"·"창문이 안 보여" without front or rear, "충전" alone (EV charge port / phone charging), "브레이크" alone.
- Rain or water drops on the glass means the wiper, not the defogger; fog/frost/condensation means the defogger.
- If previous_intent is given and the utterance is only a pronoun follow-up about the same thing ("이거 어떻게 꺼?", "그거 다시 알려줘"), return previous_intent.
- confidence is your probability (0..1) that the chosen intent is correct.`;

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

function buildGeminiRequest(body) {
  const ids = body.allowed_intents.map((i) => i.id);
  const intentLines = body.allowed_intents.map((i) => {
    const label = i.label ? ` | ${i.label}` : '';
    const examples = (i.examples || []).slice(0, 30).join(', ');
    return `- ${i.id}${label} | examples: ${examples}`;
  }).join('\n');
  const userText = [
    'Allowed intents (id | control name | example phrases):',
    intentLines,
    `previous_intent: ${body.previous_intent || 'none'}`,
    `Utterance: """${body.query.replace(/"""/g, '"')}"""`,
  ].join('\n');

  const generationConfig = {
    responseMimeType: 'application/json',
    responseJsonSchema: {
      type: 'object',
      properties: {
        intent: { type: 'string', enum: [...ids, UNKNOWN] },
        confidence: { type: 'number', minimum: 0, maximum: 1 },
        requires_clarification: { type: 'boolean' },
      },
      required: ['intent', 'confidence', 'requires_clarification'],
      additionalProperties: false,
    },
    maxOutputTokens: 256,
  };
  if (config.thinkingLevel && config.thinkingLevel !== 'NONE') {
    generationConfig.thinkingConfig = { thinkingLevel: config.thinkingLevel };
  }
  return {
    systemInstruction: { parts: [{ text: SYSTEM_INSTRUCTION }] },
    contents: [{ role: 'user', parts: [{ text: userText }] }],
    generationConfig,
  };
}

class UpstreamError extends Error {
  constructor(code, status) { super(code); this.code = code; this.status = status; }
}

async function callGemini(body) {
  const url = `${config.apiBase}/v1beta/models/${encodeURIComponent(config.model)}:generateContent`;
  let res;
  try {
    res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': config.apiKey },
      body: JSON.stringify(buildGeminiRequest(body)),
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
  const clean = validateModelOutput(parsed, new Set(body.allowed_intents.map((i) => i.id)));
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
    if (req.method !== 'POST' || url.pathname !== '/v1/rendy/classify') return send(res, 404, { error: 'not_found' });
    if (!authorized(req)) { log({ status: 401, reason: 'unauthorized' }); return send(res, 401, { error: 'unauthorized' }); }
    const limited = allowRequest(req.socket.remoteAddress || 'unknown');
    if (limited) { log({ status: 429, reason: limited }); return send(res, 429, { error: limited }); }
    if (!config.apiKey) { log({ status: 503, reason: 'key_not_configured' }); return send(res, 503, { error: 'key_not_configured' }); }

    let body;
    try { body = await readJson(req); } catch { return send(res, 400, { error: 'invalid_json' }); }
    const invalid = validateRequest(body);
    if (invalid) { log({ status: 400, reason: invalid }); return send(res, 400, { error: invalid }); }

    try {
      const result = await callGemini(body);
      log({ status: 200, model: config.model, ms: Date.now() - started, intent: result.intent,
        confidence: result.confidence, clarify: result.requires_clarification,
        candidates: body.allowed_intents.length, context: Boolean(body.previous_intent) });
      return send(res, 200, result);
    } catch (e) {
      const code = e instanceof UpstreamError ? e.code : 'internal_error';
      const status = e instanceof UpstreamError ? e.status : 500;
      log({ status, model: config.model, ms: Date.now() - started, reason: code });
      return send(res, status, { error: code });
    }
  });
}

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
