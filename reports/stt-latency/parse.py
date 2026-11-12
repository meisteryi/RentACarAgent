# Parses run_*.txt logcat dumps into per-run latency metrics and summary statistics.
import sys, re, glob, statistics, os, datetime
d = sys.argv[1]
def t(line):
    m = re.match(r'(\d\d-\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)', line)
    return None if not m else ((int(m[2])*60+int(m[3]))*60+int(m[4]))*1000+int(m[5])
pat = {
 'wake': r'Wake pipeline:',
 'tts_req': r'Wake prompt requested',
 'tts_start': r'utterance started: id=rendy_wake_prompt',
 'tts_end': r'utterance completed: id=rendy_wake_prompt',
 'stt_req': r'STT pipeline: trigger=WAKE',
 'ready': r'Pleos STT ready: trigger=WAKE',
 'capture': r'microphone capture confirmed by .*trigger=WAKE',
 'speech_start': r'microphone capture confirmed by onStartedRecognition|EPD_ON|speechStartedAt',
 'partial': r'partial recognition: trigger=WAKE.*first=true',
 'final': r'final recognition: trigger=WAKE',
 'beep_end': r'Listening cue played',
}
rows=[]
for f in sorted(glob.glob(os.path.join(d,'run_*.txt')), key=lambda p:int(re.findall(r'\d+',os.path.basename(p))[0])):
    ev={}
    for line in open(f,encoding='utf-8',errors='replace'):
        for k,p in pat.items():
            if k not in ev and re.search(p,line):
                ev[k]=t(line)
    rows.append((os.path.basename(f),ev))
def diff(ev,a,b): return ev[b]-ev[a] if a in ev and b in ev else None
metrics=[('TTS end -> STT request','tts_end','stt_req'),('STT request -> onReady','stt_req','ready'),
 ('STT request -> capture (first RMS)','stt_req','capture'),('TTS end -> capture','tts_end','capture'),
 ('STT request -> first partial','stt_req','partial'),('TTS end -> final','tts_end','final'),('TTS end -> listening cue end','tts_end','beep_end')]
print('run\t'+'\t'.join(m[0] for m in metrics))
for name,ev in rows:
    print(name+'\t'+'\t'.join(str(diff(ev,a,b)) for _,a,b in metrics))
print()
for label,a,b in metrics:
    v=[diff(ev,a,b) for _,ev in rows if diff(ev,a,b) is not None]
    if v: print(f'{label}: n={len(v)} mean={statistics.mean(v):.0f} median={statistics.median(v):.0f} min={min(v)} max={max(v)}')
    else: print(f'{label}: n=0')
