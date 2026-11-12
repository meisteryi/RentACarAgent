# Open-vocabulary keyword spotting for "렌디" with sherpa-onnx KeywordSpotter on the Korean streaming zipformer
# (general ASR transducer, not a KWS-trained model). Counts activations per file for each threshold setting.
# Usage: kws_eval.py <model_dir> <out.json> <label=dir> [...]
import sys, os, json, glob, wave, time, numpy as np, sentencepiece as spm, sherpa_onnx
sys.stdout.reconfigure(encoding="utf-8"); model_dir, out = sys.argv[1], sys.argv[2]
sets = [a.split('=', 1) for a in sys.argv[3:]]
sp = spm.SentencePieceProcessor(model_file=os.path.join(model_dir, 'bpe.model'))
pieces = sp.encode('렌디', out_type=str)
print('렌디 tokens:', pieces, flush=True)
SETTINGS = [tuple(map(float, x.split(","))) for x in os.environ.get("KWS_SETTINGS", "1.0,0.25;1.5,0.25;1.0,0.15;2.0,0.10").split(";")]   # (boost score, trigger threshold)
res = {'tokens': pieces, 'settings': SETTINGS, 'sets': {}}
for score, thr in SETTINGS:
    kwfile = os.path.join(os.path.dirname(out) or '.', f'kw_{score}_{thr}.txt')
    open(kwfile, 'w', encoding='utf-8').write(' '.join(pieces) + f' :{score} #{thr}\n')
    kws = sherpa_onnx.KeywordSpotter(
        tokens=os.path.join(model_dir, 'tokens.txt'),
        encoder=os.path.join(model_dir, 'encoder-epoch-99-avg-1.int8.onnx'),
        decoder=os.path.join(model_dir, 'decoder-epoch-99-avg-1.onnx'),
        joiner=os.path.join(model_dir, 'joiner-epoch-99-avg-1.int8.onnx'),
        num_threads=2, keywords_file=kwfile, provider='cpu')
    key = f'score{score}_thr{thr}'
    for label, d in sets:
        files = sorted(glob.glob(os.path.join(d, '*.wav'))); acts = 0; hitfiles = 0; secs = 0.0; t0 = time.time(); per = {}
        for f in files:
            with wave.open(f) as w: x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
            secs += len(x) / 16000
            s = kws.create_stream()
            n = 0
            for i in range(0, len(x), 1024):
                s.accept_waveform(16000, x[i:i + 1024])
                while kws.is_ready(s):
                    kws.decode_stream(s)
                    if kws.get_result(s):
                        n += 1; kws.reset_stream(s)
            s.accept_waveform(16000, np.zeros(8000, dtype=np.float32)); s.input_finished()
            while kws.is_ready(s):
                kws.decode_stream(s)
                if kws.get_result(s): n += 1; kws.reset_stream(s)
            acts += n; hitfiles += n > 0; per[os.path.basename(f)] = n
        rtf = (time.time() - t0) / max(secs, 1e-9)
        res['sets'].setdefault(label, {})[key] = {'files': len(files), 'hours': secs / 3600, 'activations': acts, 'files_with_activation': hitfiles, 'rtf': rtf, 'per_file': per}
        print(f'{key} {label}: files={len(files)} hours={secs/3600:.3f} activations={acts} files_with_activation={hitfiles} rtf={rtf:.3f}', flush=True)
json.dump(res, open(out, 'w', encoding='utf-8'), ensure_ascii=False)
