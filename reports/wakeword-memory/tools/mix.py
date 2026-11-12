# Writes clean + brown-noise (SNR 20/10/5 dB) versions of every clip as separate 16 kHz WAVs (fixed seed).
import sys, os, glob, wave, numpy as np
src, dst = sys.argv[1], sys.argv[2]; rng = np.random.default_rng(1234)
def brown(n):
    b = np.cumsum(rng.standard_normal(n)); b -= np.convolve(b, np.ones(400)/400, mode='same'); return b
for f in sorted(glob.glob(os.path.join(src, '*', '*.wav'))):
    label = os.path.basename(os.path.dirname(f)); os.makedirs(os.path.join(dst, label), exist_ok=True)
    with wave.open(f) as w: x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64)
    for snr in [None, 20, 10, 5]:
        if snr is None: y = x
        else:
            n = brown(len(x)); sig = x[np.abs(x) > 50]; ps = np.mean(sig**2) if len(sig) else np.mean(x**2)
            n *= np.sqrt(ps / (10**(snr/10)) / np.mean(n**2)); y = np.clip(x + n, -32768, 32767)
        out = os.path.join(dst, label, os.path.basename(f)[:-4] + f'__snr{snr if snr else "clean"}.wav')
        with wave.open(out, 'wb') as w: w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(y.astype(np.int16).tobytes())
print('done')
