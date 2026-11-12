# Pools wake->Pleos STT latency runs per variant (logcat dumps from measure_wake.sh) and compares them.
import re, glob, os, statistics, itertools, random
def t(line):
    m = re.match(r'(\d\d-\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)', line)
    return None if not m else ((int(m[2])*60+int(m[3]))*60+int(m[4]))*1000+int(m[5])
P = {'tts_end': r'utterance completed: id=rendy_wake_prompt', 'stt_req': r'STT pipeline: trigger=WAKE',
     'ready': r'Pleos STT ready: trigger=WAKE', 'capture': r'microphone capture confirmed by .*trigger=WAKE'}
def runs(dirs):
    out = []
    for d in dirs:
        for f in glob.glob(os.path.join(d, 'latency', 'run_*.txt')):
            ev = {}
            for line in open(f, encoding='utf-8', errors='replace'):
                for k, p in P.items():
                    if k not in ev and re.search(p, line): ev[k] = t(line)
            out.append(ev)
    return out
V = {'off (no Vosk)': ['off'], 'full': ['full', 'full_run2'], 'lite': ['lite', 'lite_run2'], 'lite+vocab': ['lite_trimvocab']}
M = [('STT request -> onReady', 'stt_req', 'ready'), ('STT request -> capture', 'stt_req', 'capture'), ('TTS end -> capture', 'tts_end', 'capture')]
data = {}
for name, dirs in V.items():
    r = runs(dirs)
    for label, a, b in M:
        v = [e[b] - e[a] for e in r if a in e and b in e]
        data[(name, label)] = v
        print(f'{name:14s} {label:24s} n={len(v):2d} mean={statistics.mean(v):5.0f} median={statistics.median(v):5.0f} min={min(v):5d} max={max(v):5d} <=1000ms: {sum(x<=1000 for x in v)}/{len(v)}')
def perm_p(a, b, n=20000):
    random.seed(1); obs = abs(statistics.median(a) - statistics.median(b)); pool = a + b; c = 0
    for _ in range(n):
        random.shuffle(pool)
        if abs(statistics.median(pool[:len(a)]) - statistics.median(pool[len(a):])) >= obs: c += 1
    return c / n
print()
for x, y in itertools.combinations(V, 2):
    a, b = data[(x, 'TTS end -> capture')], data[(y, 'TTS end -> capture')]
    print(f'TTS end -> capture  {x} vs {y}: median diff={statistics.median(a)-statistics.median(b):+.0f} ms, permutation p={perm_p(a, b):.2f}')
