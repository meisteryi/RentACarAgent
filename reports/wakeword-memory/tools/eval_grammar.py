# Wake-word evaluation mirroring RendyWakeWordController: grammar ["렌디","<UNK>"], 2048-byte chunks,
# accept a segment/final result whose text (spaces removed) == "렌디" and min word conf >= 0.70.
import sys, os, json, glob, time, wave, psutil, numpy as np
from vosk import Model, KaldiRecognizer, SetLogLevel
SetLogLevel(-1)
model_dir, audio_dir, out = sys.argv[1], sys.argv[2], sys.argv[3]
proc = psutil.Process()
rss0 = proc.memory_info().rss
t0 = time.time(); model = Model(model_dir); load_s = time.time() - t0
rss1 = proc.memory_info().rss
GRAMMAR = sys.argv[4]
rng = np.random.default_rng(1234)
def brown(n):
    w = rng.standard_normal(n); b = np.cumsum(w); b -= np.convolve(b, np.ones(400)/400, mode='same'); return b
def decide(js):
    d = json.loads(js); text = d.get('text', '').replace(' ', '')
    words = d.get('result', []); conf = min([w.get('conf', 0) for w in words]) if words else 0.0
    return text, conf
def run(pcm):
    rec = KaldiRecognizer(model, 16000, GRAMMAR); rec.SetWords(True)
    data = pcm.astype(np.int16).tobytes(); hits = []
    for i in range(0, len(data), 2048):
        if rec.AcceptWaveform(data[i:i+2048]): hits.append(decide(rec.Result()))
    hits.append(decide(rec.FinalResult()))
    det = any(t == '렌디' and c >= 0.70 for t, c in hits)
    return det, [h for h in hits if h[0]]
res = []
for f in sorted(glob.glob(os.path.join(audio_dir, '*', '*.wav'))):
    with wave.open(f) as w: x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64)
    label = os.path.basename(os.path.dirname(f))
    for snr in [None, 20, 10, 5]:
        if snr is None: y = x
        else:
            n = brown(len(x)); ps = np.mean(x[np.abs(x) > 50]**2) if np.any(np.abs(x) > 50) else np.mean(x**2)
            n *= np.sqrt(ps / (10**(snr/10)) / np.mean(n**2)); y = np.clip(x + n, -32768, 32767)
        det, hyps = run(y)
        res.append({'file': os.path.basename(f), 'label': label, 'snr': snr, 'detected': det, 'hyps': hyps})
rss2 = proc.memory_info().rss
json.dump({'model': model_dir, 'load_s': load_s, 'rss_before_mb': rss0/2**20, 'rss_after_load_mb': rss1/2**20,
           'rss_after_eval_mb': rss2/2**20, 'peak_wset_mb': proc.memory_info().peak_wset/2**20, 'results': res},
          open(out, 'w', encoding='utf-8'), ensure_ascii=False)
print(f'{os.path.basename(model_dir)} load={load_s:.1f}s rss_load_delta={(rss1-rss0)/2**20:.0f}MB peak={proc.memory_info().peak_wset/2**20:.0f}MB')
