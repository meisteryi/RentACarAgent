# Onset (s) of the 렌디 clip in every posctx file, recomputed with make_posctx.py's construction (noise does not move it).
import sys, os, glob, wave, json, numpy as np
pos_dir, fleurs_dir, out = sys.argv[1:4]
pos = sorted(p for p in glob.glob(os.path.join(pos_dir, '*.wav')) if p.endswith('__snrclean.wav'))
fl = sorted(glob.glob(os.path.join(fleurs_dir, '*.wav')))
def rd(p):
    with wave.open(p) as w: return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64)
meta = {}
for i, p in enumerate(pos):
    src = rd(fl[(i * 37) % len(fl)])
    n = len(src) // 320; fr = np.sqrt(np.mean(src[:n * 320].reshape(n, 320) ** 2, axis=1))
    thr = max(3 * np.percentile(fr, 20), 50.0); vv = np.nonzero(fr > thr)[0]; last = int(vv[-1]) + 1 if len(vv) else n
    tail_len = (last * 320 - max(0, last * 320 - 3 * 16000)) / 16000
    for g in (0.1, 0.2, 0.3, 0.5, 0.8, 1.2):
        for snr in ('clean', 10):
            meta[f"{os.path.basename(p)[:-15]}__gap{int(g*1000)}__snr{snr}.wav"] = round(tail_len + g, 3)
json.dump(meta, open(out, 'w'), indent=0)
print(len(meta))
