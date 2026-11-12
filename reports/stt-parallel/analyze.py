# Per-run timeline for wake STT modes (logcat dumps run_*.txt from measure_wake.sh) and summary statistics.
# Usage: python analyze.py <dir> [<dir> ...]
import sys, re, glob, os, statistics

def t(line):
    m = re.match(r'(\d\d-\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)', line)
    return None if not m else ((int(m[2]) * 60 + int(m[3])) * 60 + int(m[4])) * 1000 + int(m[5])

P = {
    'wake': r'Wake pipeline:',
    'tts_start': r'utterance started: id=rendy_wake_prompt',
    'tts_end': r'utterance completed: id=rendy_wake_prompt',
    'tts_stopped': r'utterance stopped: id=rendy_wake_prompt|Audio focus lost: change',
    'stt_req': r'STT pipeline: trigger=WAKE_WORD_RENDI',
    'par_ready': r'parallel STT ready: keep=',
    'discard': r'parallel STT discarded before recording',
    'ready': r'Pleos STT ready: trigger=WAKE_WORD_RENDI',
    'svc_record': r'\[GAAS\]AudioRecorderImpl: .*start\(com\.hyundai\.rentalhelper\)',
    'capture': r'microphone capture confirmed by .*trigger=WAKE_WORD_RENDI',
    'focus': r'parallel STT took audio focus at prompt end',
    'svc_close': r'OnDeviceSttBinder: .*close\(com\.hyundai\.rentalhelper\)',
}

def runs(d):
    files = [f for f in glob.glob(os.path.join(d, 'run_*.txt')) if re.fullmatch(r'run_\d+\.txt', os.path.basename(f))]
    for f in sorted(files, key=lambda p: int(re.findall(r'\d+', os.path.basename(p))[-1])):
        ev, reqs, texts, keep = {}, [], [], None
        for line in open(f, encoding='utf-8', errors='replace'):
            ts = t(line)
            for k, p in P.items():
                if re.search(p, line):
                    if k not in ev: ev[k] = ts
                    if k == 'stt_req': reqs.append(ts)
                    if k == 'par_ready': keep = 'keep=true' in line; ev['par_ready_line'] = line.strip()[-160:]
            m = re.search(r'(partial|final) recognition: trigger=WAKE[^,]*, text=([^,]*)', line)
            if m: texts.append((ts, m[1], m[2]))
            m = re.search(r'response (midResult|finalResult) = (.*)$', line)
            if m and m[2].strip(): texts.append((ts, 'svc_' + m[1], m[2].strip()[:40]))
        yield os.path.basename(f), ev, reqs, texts, keep

def d(ev, a, b): return ev[b] - ev[a] if a in ev and b in ev and ev[a] is not None and ev[b] is not None else None

for dirname in sys.argv[1:]:
    print(f'===== {dirname}')
    rows = list(runs(dirname))
    vals = {'TTS end -> capture': [], 'TTS end -> service AudioRecord start': [], 'first STT request -> ready': []}
    discards = keeps = stopped = leaked = 0
    for name, ev, reqs, texts, keep in rows:
        te = ev.get('tts_end')
        early = len(reqs) > 0 and te is not None and reqs[0] < te
        before_end = [x for x in texts if te is not None and x[0] < te]
        tts_words = [x for x in texts if '말씀' in x[2]]
        if keep is True: keeps += 1
        if 'discard' in ev: discards += 1
        if 'tts_stopped' in ev: stopped += 1
        if tts_words: leaked += 1
        for k, (a, b) in {'TTS end -> capture': ('tts_end', 'capture'), 'TTS end -> service AudioRecord start': ('tts_end', 'svc_record'),
                          'first STT request -> ready': ('stt_req', 'ready')}.items():
            v = d(ev, a, b)
            if v is not None: vals[k].append(v)
        if 'discard' in ev and 'svc_record' in ev and 'svc_close' in ev and ev['svc_close'] >= ev['svc_record']:
            vals.setdefault('discarded session: service recorder open (record start -> close)', []).append(ev['svc_close'] - ev['svc_record'])
        print(f'{name}: requests={len(reqs)} earlyBeforePromptEnd={early} reqToPromptEnd={te - reqs[0] if early else None} '
              f'parallelKeep={keep} discarded={"discard" in ev} ttsEnd->record={d(ev, "tts_end", "svc_record")} '
              f'ttsEnd->capture={d(ev, "tts_end", "capture")} promptStopped={"tts_stopped" in ev} '
              f'textsBeforePromptEnd={before_end[:2]} textsWithPromptWords={tts_words[:2]}')
    print(f'summary: runs={len(rows)} kept={keeps} discarded={discards} promptInterrupted={stopped} runsWithPromptWordsRecognized={leaked}')
    for k, v in vals.items():
        if v: print(f'  {k}: n={len(v)} mean={statistics.mean(v):.0f} median={statistics.median(v):.0f} min={min(v)} max={max(v)} <=1000ms={sum(x <= 1000 for x in v)}/{len(v)}')
        else: print(f'  {k}: n=0')
