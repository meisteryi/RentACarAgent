# Isolation-rule table for K variants: synthetic positives by SNR, positives after speech by gap, FLEURS FA/h (dev/test).
import json, sys, re, collections
sys.stdout.reconfigure(encoding='utf-8')
def cond(c): m=re.search(r'__snr(\w+)\.wav',c['file']); return m[1] if m else 'real'
HRS={'dev':0.772,'test':1.335}
for K in sys.argv[1:] or ('2.0','3.0'):
    r=json.load(open(f'explore2_k{K}.json',encoding='utf-8')); pc=json.load(open(f'explore_posctx_k{K}.json',encoding='utf-8'))['posctx']['candidates']
    print(f'===== K={K}')
    for R,P in [(None,None),(1500,60),(1500,120),(2000,60),(1200,60)]:
        f=(lambda c: True) if R is None else (lambda R,P: lambda c: c['iso']['region_ms']<=R and c['iso']['pre_ms']<P and c['iso']['post_ms']<P)(R,P)
        name='stage1' if R is None else f'region<={R} pre/post<{P}'
        p=r['pos']['candidates']; byc=[sum(1 for c in p if cond(c)==k and f(c)) for k in ['clean','20','10','5']]
        fl=r['fleurs']['candidates']; dev=sum(f(c) for c in fl if c['file'].startswith('dev_')); tst=sum(f(c) for c in fl if c['file'].startswith('test_'))
        det=collections.defaultdict(set); tailfa=collections.Counter()
        for c in pc:
            m=re.search(r'__gap(\d+)__snr(\w+)',c['file']); g=int(m[1]); s=m[2]
            if c['t'] >= 3.0 + g/1000 - 0.3:
                if f(c): det[(g,s)].add(c['file'])
            elif f(c): tailfa[s]+=1
        gaps=' '.join(f"{g}:{len(det[(g,'clean')])}/{len(det[(g,'10')])}" for g in (100,200,300,500,800,1200))
        print(f'{name:24s} pos {sum(byc):3d} ({"/".join(map(str,byc))}) | neg {sum(map(f,r["neg"]["candidates"])):3d}/240 | FLEURS dev {dev/HRS["dev"]:5.1f}/h test {tst/HRS["test"]:5.1f}/h | after-speech gap(ms):clean/10dB of 25 -> {gaps} | tail FA clean/10dB {tailfa["clean"]}/{tailfa["10"]}')
