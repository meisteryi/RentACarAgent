# Per-file comparison of wake decisions: device self-test (full, lite) vs host Vosk on the same WAV files.
import json, sys
def dev(p):
    d = json.load(open(p, encoding='utf-8')); return {r['label']+'/'+r['file']: (r['detected'], r['hyps']) for r in d['results']}
full, lite = dev('full/selftest_result.json'), dev('lite/selftest_result.json')
hf, hl = json.load(open(sys.argv[1], encoding='utf-8')), json.load(open(sys.argv[2], encoding='utf-8'))
print('host full vs host lite differing files:', sum(hf[k] != hl[k] for k in hf))
for name, a, b in [('device full vs device lite', {k: v[0] for k, v in full.items()}, {k: v[0] for k, v in lite.items()}),
                   ('device full vs host full', {k: v[0] for k, v in full.items()}, hf),
                   ('device lite vs host lite', {k: v[0] for k, v in lite.items()}, hl)]:
    diff = [k for k in a if a[k] != b[k]]
    print(f'{name}: {len(diff)} differing files', diff)
for k in full:
    if full[k][0] != lite[k][0]: print(' ', k, 'full', full[k], '| lite', lite[k])
