# Stage-2 rule table: detection kept (synthetic positives), false accepts kept (synthetic negatives, FLEURS per hour).
import json, sys
sys.stdout.reconfigure(encoding='utf-8')
r = json.load(open(sys.argv[1], encoding='utf-8'))
def syl(words): return sum(1 for w in words for ch in w if '\uac00' <= ch <= '\ud7a3')
rules = {
    'stage1 only': lambda c: True,
}
for t in [0.3, 0.5, 0.7, 1.0]: rules[f'pre_ratio<{t}'] = (lambda t: lambda c: c['pre_ratio'] < t)(t)
for t in [100, 200, 300]: rules[f'pre_sil>={t}ms'] = (lambda t: lambda c: c['pre_sil_ms'] >= t)(t)
for t in [0.8, 1.0, 1.2]: rules[f'dur<={t}s'] = (lambda t: lambda c: c['dur'] <= t)(t)
rules['decoy first==렌디'] = lambda c: bool(c['decoy']) and c['decoy'][0] == '렌디'
for t in [2, 3, 4]: rules[f'free syllables<={t}'] = (lambda t: lambda c: syl(c['free']) <= t)(t)
rules['pre_ratio<0.5 & free<=3'] = lambda c: c['pre_ratio'] < 0.5 and syl(c['free']) <= 3
rules['pre_ratio<0.7 & free<=3'] = lambda c: c['pre_ratio'] < 0.7 and syl(c['free']) <= 3
rules['pre_ratio<0.5 | decoy'] = lambda c: c['pre_ratio'] < 0.5 or (bool(c['decoy']) and c['decoy'][0] == '렌디')
rules['pre_ratio<0.5 & dur<=1.0'] = lambda c: c['pre_ratio'] < 0.5 and c['dur'] <= 1.0
pos, neg, fl = r['pos'], r['neg'], r['fleurs']
print(f"{'rule':28s} {'pos kept':>10s} {'synth neg kept':>15s} {'FLEURS FA/h':>12s}")
for name, f in rules.items():
    p = sum(map(f, pos['candidates'])); n = sum(map(f, neg['candidates'])); fa = sum(map(f, fl['candidates']))
    print(f"{name:28s} {p:4d}/{pos['files']:<4d} {n:7d}/{neg['files']:<6d} {fa/fl['audio_hours']:10.1f}  ({fa})")
