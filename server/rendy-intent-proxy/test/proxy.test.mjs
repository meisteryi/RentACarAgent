// Offline tests: a fake Gemini upstream on localhost. No API key or network needed.
// Run: npm test (= node --test, from server/rendy-intent-proxy)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

let upstreamReply = () => ({ status: 200, body: {} });
let lastUpstream = null;
const upstream = http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  lastUpstream = { url: req.url, headers: req.headers, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) };
  const reply = upstreamReply();
  if (reply.delayMs) await new Promise((r) => setTimeout(r, reply.delayMs));
  res.writeHead(reply.status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(reply.body));
});

const geminiText = (obj) => ({ status: 200, body: { candidates: [{ content: { parts: [{ text: JSON.stringify(obj) }] } }] } });
let base;
let server;

before(async () => {
  await new Promise((r) => upstream.listen(0, '127.0.0.1', r));
  process.env.GEMINI_API_BASE = `http://127.0.0.1:${upstream.address().port}`;
  process.env.GEMINI_API_KEY = 'test-key-not-real';
  process.env.GEMINI_MODEL = 'test-model';
  process.env.RENDY_PROXY_TOKEN = 'tok';
  process.env.UPSTREAM_TIMEOUT_MS = '300';
  process.env.RATE_LIMIT_PER_MINUTE = '1000';
  const { createServer } = await import('../server.mjs');
  server = createServer();
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => { server.close(); upstream.close(); });

const request = {
  query: '앞유리가 뿌예',
  allowed_intents: [
    { id: 'front_defog', examples: ['김서림'], label: '앞유리 성에 제거' },
    { id: 'hazard_light', examples: ['비상등'] },
  ],
};
const post = (body, token = 'tok') => fetch(`${base}/v1/rendy/classify`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  body: typeof body === 'string' ? body : JSON.stringify(body),
});

test('valid classification is returned with exactly three fields', async () => {
  upstreamReply = () => geminiText({ intent: 'front_defog', confidence: 0.93, requires_clarification: false });
  const res = await post(request);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { intent: 'front_defog', confidence: 0.93, requires_clarification: false });
  // Key sent only as header to upstream; schema is server-built with UNKNOWN; model comes from server config.
  assert.equal(lastUpstream.headers['x-goog-api-key'], 'test-key-not-real');
  assert.match(lastUpstream.url, /\/v1beta\/models\/test-model:generateContent$/);
  assert.deepEqual(lastUpstream.body.generationConfig.responseJsonSchema.properties.intent.enum,
    ['front_defog', 'hazard_light', 'UNKNOWN']);
  assert.ok(!JSON.stringify(lastUpstream.body).includes('test-key-not-real'));
});

test('client-supplied model and schema are ignored', async () => {
  upstreamReply = () => geminiText({ intent: 'UNKNOWN', confidence: 0.2, requires_clarification: true });
  const res = await post({ ...request, model: 'gemini-ultra-expensive', response_schema: { evil: true } });
  assert.equal(res.status, 200);
  assert.match(lastUpstream.url, /test-model/);
  assert.ok(!JSON.stringify(lastUpstream.body).includes('evil'));
});

test('auth is required when a token is configured', async () => {
  assert.equal((await post(request, null)).status, 401);
  assert.equal((await post(request, 'wrong')).status, 401);
});

test('invalid requests are rejected before calling Gemini', async () => {
  for (const bad of [
    'not json',
    { ...request, query: '' },
    { ...request, query: 'x'.repeat(301) },
    { ...request, allowed_intents: [] },
    { ...request, allowed_intents: [{ id: 'Bad Id' }] },
    { ...request, allowed_intents: [{ id: 'UNKNOWN' }] },
    { ...request, previous_intent: 'not_offered' },
  ]) {
    assert.equal((await post(bad)).status, 400, JSON.stringify(bad).slice(0, 60));
  }
});

test('model output outside the allowlist or schema becomes 502', async () => {
  for (const out of [
    { intent: 'sunroof_switch', confidence: 0.9, requires_clarification: false },
    { intent: 'front_defog', confidence: 1.4, requires_clarification: false },
    { intent: 'front_defog', confidence: 0.9, requires_clarification: false, answer: '왼쪽 버튼' },
  ]) {
    upstreamReply = () => geminiText(out);
    assert.equal((await post(request)).status, 502);
  }
  upstreamReply = () => ({ status: 200, body: { candidates: [{ content: { parts: [{ text: '```json\n{}\n```' }] } }] } });
  assert.equal((await post(request)).status, 502);
});

test('upstream errors map to non-2xx so the app falls back', async () => {
  upstreamReply = () => ({ status: 429, body: { error: { status: 'RESOURCE_EXHAUSTED' } } });
  assert.equal((await post(request)).status, 429);
  upstreamReply = () => ({ status: 500, body: {} });
  assert.equal((await post(request)).status, 502);
  upstreamReply = () => ({ ...geminiText({ intent: 'front_defog', confidence: 0.9, requires_clarification: false }), delayMs: 1000 });
  const res = await post(request);
  assert.equal(res.status, 504);
  assert.deepEqual(await res.json(), { error: 'upstream_timeout' });
});

// ---------------------------------------------------------------- /v1/rendy/turn (follow-up dialogue acts)

const turnRequest = {
  query: '어? 뭐라고?',
  allowed_intents: request.allowed_intents,
  dialogue: {
    last_rendy_utterance: '앞유리 성에 제거 버튼은 공조 패널에 있어요.',
    rendy_asked_question: false,
    last_driver_question: '앞유리 김 어떻게 없애?',
    current_intent: 'front_defog',
    stage: 'ANSWERED',
    pending_detail: false,
    driving_state: 'DRIVING',
    car_model: '투싼',
  },
};
const postTurn = (body, token = 'tok') => fetch(`${base}/v1/rendy/turn`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  body: typeof body === 'string' ? body : JSON.stringify(body),
});

test('turn returns exactly {dialogue_act, intent, confidence} and sends the conversation state', async () => {
  upstreamReply = () => geminiText({ dialogue_act: 'REPEAT', intent: 'UNKNOWN', confidence: 0.88 });
  const res = await postTurn(turnRequest);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { dialogue_act: 'REPEAT', intent: 'UNKNOWN', confidence: 0.88 });
  const prompt = lastUpstream.body.contents[0].parts[0].text;
  assert.match(prompt, /stage: ANSWERED/);
  assert.match(prompt, /current feature: front_defog/);
  assert.match(prompt, /RENDY's last utterance: """앞유리 성에 제거 버튼은 공조 패널에 있어요\."""/);
  assert.match(prompt, /was a question to the driver: no/);
  assert.match(prompt, /driving state: DRIVING/);
  assert.match(prompt, /Driver's latest utterance: """어\? 뭐라고\?"""/);
  const schema = lastUpstream.body.generationConfig.responseJsonSchema;
  assert.deepEqual(schema.properties.dialogue_act.enum,
    ['NEW_QUESTION', 'REPEAT', 'NEED_DETAIL', 'CONFIRM', 'FOUND', 'DENY', 'CANCEL', 'UNCLEAR']);
  assert.deepEqual(schema.properties.intent.enum, ['front_defog', 'hazard_light', 'UNKNOWN']);
  // The turn prompt carries no Korean example phrases of its own (only the request's data).
  assert.ok(!/[가-힣]/.test(lastUpstream.body.systemInstruction.parts[0].text));
});

test('turn NEW_QUESTION keeps an allowed intent; other acts never carry one', async () => {
  upstreamReply = () => geminiText({ dialogue_act: 'NEW_QUESTION', intent: 'hazard_light', confidence: 0.9 });
  assert.deepEqual(await (await postTurn({ ...turnRequest, query: '비상등은 어딨어?' })).json(),
    { dialogue_act: 'NEW_QUESTION', intent: 'hazard_light', confidence: 0.9 });
  upstreamReply = () => geminiText({ dialogue_act: 'FOUND', intent: 'front_defog', confidence: 0.8 });
  assert.deepEqual(await (await postTurn(turnRequest)).json(), { dialogue_act: 'FOUND', intent: 'UNKNOWN', confidence: 0.8 });
});

test('turn rejects invalid state before calling Gemini', async () => {
  const d = turnRequest.dialogue;
  for (const bad of [
    { ...turnRequest, dialogue: undefined },
    { ...turnRequest, dialogue: { ...d, stage: 'TELEPORT' } },
    { ...turnRequest, dialogue: { ...d, driving_state: 'FLYING' } },
    { ...turnRequest, dialogue: { ...d, current_intent: 'not_offered' } },
    { ...turnRequest, dialogue: { ...d, last_rendy_utterance: 'x'.repeat(301) } },
    { ...turnRequest, dialogue: { ...d, rendy_asked_question: 'yes' } },
    { ...turnRequest, query: '' },
  ]) {
    lastUpstream = null;
    assert.equal((await postTurn(bad)).status, 400, JSON.stringify(bad).slice(0, 80));
    assert.equal(lastUpstream, null);
  }
  assert.equal((await postTurn(turnRequest, null)).status, 401);
});

test('turn model output outside the schema becomes 502', async () => {
  for (const out of [
    { dialogue_act: 'TELEPORT', intent: 'UNKNOWN', confidence: 0.9 },
    { dialogue_act: 'NEW_QUESTION', intent: 'sunroof_switch', confidence: 0.9 },
    { dialogue_act: 'REPEAT', intent: 'UNKNOWN', confidence: 2 },
    { dialogue_act: 'REPEAT', intent: 'UNKNOWN', confidence: 0.9, text: '다시 말해요' },
    { intent: 'UNKNOWN', confidence: 0.9 },
  ]) {
    upstreamReply = () => geminiText(out);
    assert.equal((await postTurn(turnRequest)).status, 502, JSON.stringify(out));
  }
});

test('healthz reports key presence without the key', async () => {
  const body = await (await fetch(`${base}/healthz`)).json();
  assert.deepEqual(body, { ok: true, model: 'test-model', keyConfigured: true });
});
