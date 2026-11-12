# Isolation features anchored on the voiced region around a candidate (not on Vosk word times, which cut slow speech).
#   floor       p20 of 20 ms frame RMS over [start-3 s, end+1 s], at least 30
#   region_ms   contiguous voiced region containing the word (frame RMS > K x floor, gaps <= 120 ms bridged)
#   pre_ms      voiced frames in the 0.6 s before the region (after the >120 ms gap that ends it)
#   post_ms     voiced frames in the 0.6 s after the region
# Usage: ctxfeat2.py <explore.json> <out.json> <label=dir> [...]
import sys, os, json, wave, numpy as np
src, out = sys.argv[1], sys.argv[2]
dirs = dict(a.split('=', 1) for a in sys.argv[3:])
r = json.load(open(src, encoding='utf-8'))
SR, FR, GAP = 16000, 320, 6
K = float(os.environ.get('ISO_K', '3.0')); WIN = int(float(os.environ.get('ISO_WIN_S', '0.6')) * 50)   # window frames     # 20 ms frames, gap bridge 6 frames = 120 ms

def isolation(x, start_s, end_s):
    n = len(x) // FR
    fr = np.sqrt(np.mean(x[:n * FR].astype(np.float64).reshape(n, FR) ** 2, axis=1))
    a, b = int(start_s * SR) // FR, min(n - 1, int(end_s * SR) // FR)
    bg = fr[max(0, a - 150):min(n, b + 50)]
    floor = max(float(np.percentile(bg, 20)) if len(bg) else 0.0, 30.0)
    v = fr > K * floor
    # seed: voiced frames inside the word; if none, the word span itself
    idx = [i for i in range(a, b + 1) if v[i]] or [a]
    lo, hi = idx[0], idx[-1]
    gap = 0; i = lo - 1
    while i >= 0:
        if v[i]: lo = i; gap = 0
        else:
            gap += 1
            if gap > GAP: break
        i -= 1
    gap = 0; i = hi + 1
    while i < n:
        if v[i]: hi = i; gap = 0
        else:
            gap += 1
            if gap > GAP: break
        i += 1
    pre = int(np.sum(v[max(0, lo - WIN):lo])) * 20
    post = int(np.sum(v[hi + 1:min(n, hi + 1 + WIN)])) * 20
    return {'floor': round(floor, 1), 'region_ms': (hi - lo + 1) * 20, 'pre_ms': pre, 'post_ms': post}

cache_f, cache_x = None, None
for label, d in dirs.items():
    for c in r[label]['candidates']:
        f = os.path.join(d, c['file'])
        if f != cache_f:
            with wave.open(f) as w: cache_x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
            cache_f = f
        c.update({'iso': isolation(cache_x, c['t'], c['t'] + c['dur'])})
json.dump(r, open(out, 'w', encoding='utf-8'), ensure_ascii=False)
print('ok')
