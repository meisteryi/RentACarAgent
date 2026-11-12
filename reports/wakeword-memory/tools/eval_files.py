# Host decode of the exact WAV files pushed to the device (no in-process noise), same grammar/chunking/rule as the app.
import sys, os, json, glob, wave
from vosk import Model, KaldiRecognizer, SetLogLevel
SetLogLevel(-1)
model_dir, audio_dir, out = sys.argv[1], sys.argv[2], sys.argv[3]
model = Model(model_dir)
GRAMMAR = '["렌디", "<UNK>"]'
def decide(js):
    d = json.loads(js); text = d.get('text', '').replace(' ', '')
    words = d.get('result', []); conf = min([w.get('conf', 0) for w in words]) if words else 0.0
    return text, conf
res = {}
for f in sorted(glob.glob(os.path.join(audio_dir, '*', '*.wav'))):
    with wave.open(f) as w: data = w.readframes(w.getnframes())
    rec = KaldiRecognizer(model, 16000, GRAMMAR); rec.SetWords(True); hits = []
    for i in range(0, len(data), 2048):
        if rec.AcceptWaveform(data[i:i+2048]): hits.append(decide(rec.Result()))
    hits.append(decide(rec.FinalResult()))
    res[os.path.basename(os.path.dirname(f)) + '/' + os.path.basename(f)] = any(t == '렌디' and c >= 0.70 for t, c in hits)
json.dump(res, open(out, 'w', encoding='utf-8'), ensure_ascii=False)
pos = [v for k, v in res.items() if k.startswith('pos/')]; neg = [v for k, v in res.items() if k.startswith('neg/')]
print(f'{os.path.basename(model_dir)} pos={sum(pos)}/{len(pos)} neg={sum(neg)}/{len(neg)}')
