# Device self-test result -> detection and false-activation metrics for stage 1 (current rule) and each stage-2 setting.
# Usage: device_metrics.py <result_lite.json> <posctx_meta.json>
import sys, json, re, collections, statistics
sys.stdout.reconfigure(encoding='utf-8')
r = json.load(open(sys.argv[1], encoding='utf-8')); meta = json.load(open(sys.argv[2]))
S = r['sets']
params = r.get('isolationParams', {})
labels = params.get('candidates') or ['isolated']

def cond(name):
    m = re.search(r'__snr(\w+)\.wav', name); return m[1] if m else '?'

def metrics(pred):
    out = {}
    if 'pos' in S:
        hit = {a['file'] for a in S['pos']['activations'] if pred(a)}
        out['pos'] = (len(hit), [sum(1 for f in hit if cond(f) == c) for c in ('clean', '20', '10', '5')])
    if 'neg' in S:
        out['neg'] = len({a['file'] for a in S['neg']['activations'] if pred(a)})
    if 'posctx' in S:
        det = collections.defaultdict(set); tail = collections.Counter()
        for a in S['posctx']['activations']:
            if not pred(a): continue
            m = re.search(r'__gap(\d+)__snr(\w+)', a['file']); key = (int(m[1]), m[2])
            if a['t'] >= meta[a['file']] - 0.3: det[key].add(a['file'])
            else: tail[m[2]] += 1
        out['posctx'] = {k: len(v) for k, v in det.items()}; out['tail'] = tail
    for name in S:
        if name.endswith('_stream'):
            n = sum(1 for a in S[name]['activations'] if pred(a)); h = S[name]['seconds'] / 3600
            out['stream'] = (n, h)
    return out

rows = [('stage1 (current)', lambda a: True)]
for lab in labels:
    rows.append((lab, (lambda lab: (lambda a: a.get('variants', {}).get(lab, a.get('isolated'))))(lab)))
gaps = (100, 200, 300, 500, 800, 1200)
print(f"{'setting':28s} | isolated 렌디 (clean/20/10/5) | near-homophone negs | 렌디 after speech by pause clean/10dB of 25 | FA in speech tails clean/10dB | real speech FA/h")
for name, pred in rows:
    m = metrics(pred)
    p, by = m.get('pos', (0, [0, 0, 0, 0]))
    pc = m.get('posctx', {}); tail = m.get('tail', {})
    after = ' '.join(f"{g}:{pc.get((g, 'clean'), 0)}/{pc.get((g, '10'), 0)}" for g in gaps)
    n, h = m.get('stream', (0, 1))
    print(f"{name:28s} | {p:3d}/100 ({'/'.join(map(str, by))}) | {m.get('neg', 0):3d}/240 | {after} | {tail.get('clean', 0)}/{tail.get('10', 0)} | {n} in {h:.2f} h = {n / h:.1f}/h")
ev = [a['evalMicros'] for s in S.values() for a in s['activations'] if 'evalMicros' in a]
if ev: print(f"stage-2 evaluation time per candidate (production setting): n={len(ev)} median={statistics.median(ev)} us p95={sorted(ev)[int(len(ev) * .95)]} us max={max(ev)} us")
