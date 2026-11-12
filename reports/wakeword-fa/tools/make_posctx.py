# Positives after speech: last 3 s of voiced FLEURS speech (trailing silence trimmed) + gap g s of silence/noise + clean synthetic "렌디" + 1.2 s tail.
# Versions: clean and brown noise at SNR 10 dB over the whole file (fixed seed).
import sys, os, glob, wave, numpy as np
pos_dir, fleurs_dir, out = sys.argv[1:4]; os.makedirs(out, exist_ok=True)
rng = np.random.default_rng(7)
pos = sorted(p for p in glob.glob(os.path.join(pos_dir, '*.wav')) if p.endswith('__snrclean.wav'))
fl = sorted(glob.glob(os.path.join(fleurs_dir, '*.wav')))
def rd(p):
    with wave.open(p) as w: return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64)
def brown(n):
    b = np.cumsum(rng.standard_normal(n)); b -= np.convolve(b, np.ones(400) / 400, mode='same'); return b
for i, p in enumerate(pos):
    x = rd(p); src = rd(fl[(i * 37) % len(fl)])
    m = len(x) // 320; fx = np.sqrt(np.mean(x[:m * 320].reshape(m, 320) ** 2, axis=1)); on = np.nonzero(fx > 300)[0]
    x = x[max(0, (int(on[0]) - 1) * 320):] if len(on) else x   # trim the clip's leading silence (SSML break)
    n = len(src) // 320; fr = np.sqrt(np.mean(src[:n * 320].reshape(n, 320) ** 2, axis=1))
    thr = max(3 * np.percentile(fr, 20), 50.0); vv = np.nonzero(fr > thr)[0]; last = int(vv[-1]) + 1 if len(vv) else n   # trim trailing silence
    tail = src[max(0, last * 320 - 3 * 16000):last * 320]
    for g in (0.1, 0.2, 0.3, 0.5, 0.8, 1.2):
        y = np.concatenate([tail, np.zeros(int(g * 16000)), x, np.zeros(int(1.2 * 16000))])
        for snr in ('clean', 10):
            z = y.copy()
            if snr != 'clean':
                n = brown(len(z)); sig = z[np.abs(z) > 50]; ps = np.mean(sig ** 2)
                n *= np.sqrt(ps / (10 ** (snr / 10)) / np.mean(n ** 2)); z = z + n
            name = f"{os.path.basename(p)[:-15]}__gap{int(g*1000)}__snr{snr}.wav"
            with wave.open(os.path.join(out, name), 'wb') as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(np.clip(z, -32768, 32767).astype(np.int16).tobytes())
print('files', len(os.listdir(out)))
