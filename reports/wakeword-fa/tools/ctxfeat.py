# Noise-floor-relative context features for stage-1 candidates in explore2.json (adds them in place -> out.json).
#   floor      p20 of 20 ms frame RMS over [start-3 s, end+1 s] (background level)
#   pre_ms     ms of frames in [start-0.6 s, start-0.06 s] whose RMS > K x floor (speech just before the word)
#   post_ms    same for [end+0.06 s, end+0.6 s]
# Usage: ctxfeat.py <explore.json> <out.json> <label=dir> [...]
import sys, os, json, wave, numpy as np
sys.stdout.reconfigure(encoding='utf-8')
src, out = sys.argv[1], sys.argv[2]
dirs = dict(a.split('=', 1) for a in sys.argv[3:])
r = json.load(open(src, encoding='utf-8'))
SR, FR = 16000, 320
def frames(x):
    n = len(x) // FR
    return np.sqrt(np.mean(x[:n * FR].astype(np.float64).reshape(n, FR) ** 2, axis=1)) if n else np.zeros(0)
cache = {}
for label, d in dirs.items():
    for c in r[label]['candidates']:
        f = os.path.join(d, c['file'])
        if f not in cache:
            with wave.open(f) as w: cache = {f: np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)}
        x = cache[f]; s = int(c['t'] * SR); e = int((c['t'] + c['dur']) * SR)
        bg = frames(x[max(0, s - 3 * SR):min(len(x), e + SR)])
        floor = max(float(np.percentile(bg, 20)) if len(bg) else 0.0, 30.0)
        for K in (3.0, 5.0):
            pre = frames(x[max(0, s - int(0.6 * SR)):max(0, s - int(0.06 * SR))])
            post = frames(x[min(len(x), e + int(0.06 * SR)):min(len(x), e + int(0.6 * SR))])
            c[f'pre_ms_k{int(K)}'] = int(np.sum(pre > K * floor)) * 20
            c[f'post_ms_k{int(K)}'] = int(np.sum(post > K * floor)) * 20
        c['floor'] = round(floor, 1)
json.dump(r, open(out, 'w', encoding='utf-8'), ensure_ascii=False)
print('ok')
