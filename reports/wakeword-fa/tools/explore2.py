# Stage-1 wake candidates (app grammar + accept rule) and stage-2 features, with persistent second-pass recognizers.
# Usage: explore2.py <full_model_dir> <decoy_grammar_file> <out.json> <label=dir> [...]
# Features per candidate (word = the accepted 렌디 word, times relative to the file):
#   dur         word duration (s)
#   pre_ratio   RMS of the 0.5 s before word start / RMS of the word (speech right before -> high)
#   pre_sil_ms  length of low-energy audio immediately before the word (frames < 0.2 x word RMS, 10 ms frames, max 1500)
#   decoy       decoy-grammar decode of the cut [start-0.25, end+0.25]
#   free        free-form (full LM) decode of the same cut
import sys, os, json, glob, wave, time, numpy as np
from vosk import Model, KaldiRecognizer, SetLogLevel
SetLogLevel(-1)
model_dir, decoy_file, out = sys.argv[1:4]
sets = [a.split('=', 1) for a in sys.argv[4:]]
model = Model(model_dir)
G = '["렌디", "<UNK>"]'
SR = 16000
decoy_rec = KaldiRecognizer(model, SR, open(decoy_file, encoding='utf-8').read().strip()); decoy_rec.SetWords(True)
free_rec = KaldiRecognizer(model, SR); free_rec.SetWords(True)

def run(rec, pcm):
    rec.Reset(); b = pcm.tobytes(); outs = []   # text only, so cumulative word times do not matter
    for i in range(0, len(b), 2048):
        if rec.AcceptWaveform(b[i:i + 2048]): outs.append(json.loads(rec.Result()).get('text', ''))
    outs.append(json.loads(rec.FinalResult()).get('text', ''))
    return ' '.join(outs).split()

def rms(x): return float(np.sqrt(np.mean(x.astype(np.float64) ** 2))) if len(x) else 0.0

def features(pcm, w):
    s, e = int(w['start'] * SR), int(w['end'] * SR)
    word_rms = rms(pcm[s:e]) or 1.0
    pre = pcm[max(0, s - SR // 2):s]
    sil = 0
    for k in range(s, max(0, s - int(1.5 * SR)), -160):
        if rms(pcm[max(0, k - 160):k]) < 0.2 * word_rms: sil += 10
        else: break
    cut = pcm[max(0, s - SR // 4):min(len(pcm), e + SR // 4)]
    if os.environ.get("SKIP2"): return {"dur": round(w["end"] - w["start"], 3), "conf": w.get("conf"), "pre_ratio": round(rms(pre) / word_rms, 3), "pre_sil_ms": sil, "decoy": [], "free": []}
    return {'dur': round(w['end'] - w['start'], 3), 'conf': w.get('conf'), 'pre_ratio': round(rms(pre) / word_rms, 3),
            'pre_sil_ms': sil, 'decoy': run(decoy_rec, cut), 'free': run(free_rec, cut)}

result = {}; t0 = time.time()
for label, d in sets:
    files = sorted(glob.glob(os.path.join(d, '*.wav'))); total_s = 0.0; cands = []; hitfiles = 0
    for f in files:
        with wave.open(f) as w: pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
        total_s += len(pcm) / SR; b = pcm.tobytes(); segs = []
        rec = KaldiRecognizer(model, SR, G); rec.SetWords(True)   # fresh per file: Reset() does not restart word times
        for i in range(0, len(b), 2048):
            if rec.AcceptWaveform(b[i:i + 2048]): segs.append(rec.Result())
        segs.append(rec.FinalResult()); hit = False
        for js in segs:
            dct = json.loads(js); ws = dct.get('result', [])
            if dct.get('text', '').replace(' ', '') == '렌디' and ws and min(x.get('conf', 0) for x in ws) >= 0.70:
                hit = True; c = features(pcm, ws[0]); c['file'] = os.path.basename(f); c['t'] = round(ws[0]['start'], 2); cands.append(c)
        hitfiles += hit
    result[label] = {'files': len(files), 'audio_hours': total_s / 3600, 'files_with_activation': hitfiles, 'candidates': cands}
    print(f'{label}: files={len(files)} hours={total_s/3600:.3f} files_with_activation={hitfiles} activations={len(cands)} elapsed={time.time()-t0:.0f}s', flush=True)
json.dump(result, open(out, 'w', encoding='utf-8'), ensure_ascii=False)
