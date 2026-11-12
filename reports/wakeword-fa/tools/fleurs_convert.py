# Converts FLEURS float32 WAVs to 16 kHz mono int16 WAVs and prints totals/transcript checks.
import sys, glob, os, wave, csv, struct, numpy as np
src, dst = sys.argv[1], sys.argv[2]; os.makedirs(dst, exist_ok=True)
def read_float_wav(p):
    b = open(p, 'rb').read(); pos = 12; fmt = None; data = None
    while pos + 8 <= len(b):
        cid = b[pos:pos+4]; size = struct.unpack('<I', b[pos+4:pos+8])[0]; body = b[pos+8:pos+8+size]
        if cid == b'fmt ': fmt = struct.unpack('<HHIIHH', body[:16])
        if cid == b'data': data = body
        pos += 8 + size + (size & 1)
    tag, ch, sr, _, _, bits = fmt
    x = np.frombuffer(data, dtype=np.float32 if tag == 3 else np.int16).astype(np.float64)
    if tag != 3: x /= 32768.0
    if ch > 1: x = x.reshape(-1, ch).mean(axis=1)
    return x, sr
tot = 0.0; rates = set(); n = 0
for f in sorted(glob.glob(os.path.join(src, '*', '*', '*.wav'))):
    x, sr = read_float_wav(f); rates.add(sr)
    if sr != 16000:
        t = np.arange(0, len(x) * 16000 // sr) * sr / 16000.0; x = np.interp(t, np.arange(len(x)), x)
    y = np.clip(x * 32767, -32768, 32767).astype(np.int16)
    split = os.path.basename(os.path.dirname(os.path.dirname(f)))
    with wave.open(os.path.join(dst, f'{split}_{os.path.basename(f)}'), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(y.tobytes())
    tot += len(y) / 16000; n += 1
print('files', n, 'hours', round(tot / 3600, 3), 'source rates', rates)
for s in ['test', 'dev']:
    rows = list(csv.reader(open(os.path.join(src, s + '.tsv'), encoding='utf-8'), delimiter='\t'))
    g = [r[-1] for r in rows]
    hits = [r[2] for r in rows if any(k in r[2] for k in ['렌디', '렌트', '랜디', '렌즈', '엔디', '렌더'])]
    print(s, 'rows', len(rows), 'gender', {k: g.count(k) for k in set(g)}, 'renddi-like transcripts', len(hits), hits[:2])
