# -*- coding: utf-8 -*-
"""RENDY DB 자동 정합성·음성 품질 검사. 사용: python3 lint.py <db.json> [--orig orig.json] [--types-from final.json]"""
import json, re, sys, collections

def load(p): return json.load(open(p, encoding="utf-8"))
args=sys.argv[1:]
db=load(args[0])
orig=load(args[args.index("--orig")+1]) if "--orig" in args else None
tsrc=load(args[args.index("--types-from")+1]) if "--types-from" in args else db
TYPES={(m["model_id"],k):b.get("control_type") for m in tsrc["models"] for k,b in m["buttons"].items()}

HINT_FAMILY = {  # 문장 → 조작계 계열
 "발로 밟는 페달": "pedal", "운전대 뒤쪽에 손가락으로 당기는 패들": "paddle", "운전대 뒤쪽에 붙은, 손가락으로 당기는 패들": "paddle",
 "화면을 손가락으로 누르는 터치 버튼": "touch", "화면에 손가락을 대고 누르는 터치 버튼": "touch",
 "손으로 잡고 움직이는 레버": "lever", "손으로 잡고 당기거나 움직이는 레버": "lever", "막대 모양 레버": "lever",
 "돌리는 다이얼이에요": "dial", "손으로 돌리는 둥근 다이얼": "dial",
 "위아래로 밀거나 당기는 스위치": "toggle", "위아래(또는 앞뒤)로 밀거나 당기는 스위치": "toggle",
 "누르는 물리 버튼이에요": "button", "손가락으로 누르는 물리 버튼": "button",
}
FAMILY_OK = {
 "pedal":{"pedal"}, "paddle":{"paddle"}, "touch":{"touch"}, "info":set(),
 "lever":{"lever","floor_lever","gear_lever","stalk","column_dial","switch"}, "dial":{"dial","column_dial","toggle"},
 "toggle":{"toggle","steering_toggle","switch","stalk"}, "button":{"button","steering_button","switch","pad","touch"},
}
MODEL_NAMES={"IONIQ5":["아이오닉"],"TUCSON":["투싼"],"SONATA":["쏘나타"],"AVANTE":["아반떼"],"CASPER":["캐스퍼"]}
REQ=["name","zone","location","action_guide","spoken_answer","detailed_location","operation_steps","verification"]
issues=collections.defaultdict(list)

def sent_count(s): return len([x for x in re.split(r"(?<=[.?!])\s+", s.strip()) if x])

ids=set()
for m in db["models"]:
    mid=m["model_id"]
    for k,b in m["buttons"].items():
        key=f"{mid}.{k}"
        if key in ids: issues["duplicate_id"].append(key)
        ids.add(key)
        for f in REQ:
            if f not in b or b[f] in (None,""):
                if not (f=="operation_steps" and b.get(f)==[]): issues["missing_field"].append(f"{key}:{f}")
        ct=TYPES.get((mid,k))
        text=" ".join([b.get("spoken_answer",""),b.get("detailed_location","")])
        for phrase,fam in HINT_FAMILY.items():
            if phrase in text and ct and ct not in FAMILY_OK[fam]:
                issues["control_type_mixup"].append(f"{key}: '{phrase}' ↔ control_type={ct}")
        sp=b.get("spoken_answer","")
        if sent_count(sp)>2: issues["spoken_over_2_sentences"].append(f"{key}: {sp}")
        if len(sp)>120: issues["spoken_too_long"].append(f"{key} ({len(sp)}자)")
        if "제가 가진 정보로는" in sp: issues["hedge_phrase"].append(key)
        if re.search(r"(\S{2,}) \1(?=\s|$|[.,])", text): issues["repeated_word"].append(f"{key}: {re.search(r'(\S{2,}) \1', text).group(0)}")
        for om,names in MODEL_NAMES.items():
            if om!=mid and any(n in text+" ".join(b.get("operation_steps",[])) for n in names):
                issues["other_model_mentioned"].append(key)
        if re.search(r"(미는|얇은|숨겨진|작은|위치한)에 있어요", sp): issues["broken_grammar"].append(f"{key}: {sp}")
        v=b.get("verification") or {}
        if v.get("status") in ("VERIFIED","PARTIAL","CONFLICT") and not v.get("source_url"): issues["status_without_source"].append(key)
        if v.get("status")=="VERIFIED" and not (v.get("location_verified") ): issues["verified_without_location"].append(key)
        if v.get("answer_policy")=="hedged_safety" and not b.get("safety_notes"): issues["unverified_safety_without_note"].append(key)
        loc=b.get("location","")
        if "왼쪽" in loc and "오른쪽" not in loc and "오른쪽" in sp and "왼쪽" not in sp: issues["left_right_mismatch"].append(key)
        if "오른쪽" in loc and "왼쪽" not in loc and "왼쪽" in sp and "오른쪽" not in sp: issues["left_right_mismatch"].append(key)
        r=b.get("redirect_to") or b.get("duplicate_of")
        if r and r not in m["buttons"]: issues["broken_redirect"].append(f"{key}->{r}")

# FAQ 참조
models={m["model_id"]:m for m in db["models"]}
for it in db.get("faq_intents",[]):
    k="gear_shift" if it.get("is_gear") else it.get("response_key")
    if not any(k in mm["buttons"] for mm in models.values()): issues["faq_key_nowhere"].append(f"{it['intent']}->{k}")
for it in db.get("intent_enhancements",{}).get("proposed_new_intents",[]):
    k=it.get("response_key")
    if k and not any(k in mm["buttons"] for mm in models.values()): issues["new_intent_key_nowhere"].append(f"{it['intent']}->{k}")
for r in db.get("intent_enhancements",{}).get("clarification_rules",[]):
    for c in r["candidates"]:
        if not any(c in mm["buttons"] for mm in models.values()): issues["clarify_candidate_nowhere"].append(f"{r['phrase']}->{c}")
kw=collections.defaultdict(set)
for it in db.get("faq_intents",[]):
    for w in it["keywords"]: kw[w.replace(" ","").lower()].add(it["intent"])
for w,s in kw.items():
    if len(s)>1: issues["keyword_in_multiple_intents"].append(f"{w}: {sorted(s)}")

if orig:
    om={m["model_id"]:m for m in orig["models"]}
    for mid,m in om.items():
        a=set(m["buttons"]); b=set(models[mid]["buttons"]) if mid in models else set()
        for x in a-b: issues["id_lost_vs_original"].append(f"{mid}.{x}")
        for x in b-a: issues["id_added_vs_original"].append(f"{mid}.{x}")
        for x in a&b:
            if m["buttons"][x]["name"]!=models[mid]["buttons"][x]["name"] or m["buttons"][x].get("zone")!=models[mid]["buttons"][x].get("zone"):
                issues["name_or_zone_changed"].append(f"{mid}.{x}")
    if orig["faq_intents"]!=db["faq_intents"]: issues["faq_intents_modified"].append("faq_intents differ from original")

summary={k:len(v) for k,v in issues.items()}
total=sum(len(m["buttons"]) for m in db["models"])
out={"db":args[0].split("/")[-1],"buttons":total,"models":len(db["models"]),"faq_intents":len(db.get("faq_intents",[])),"summary":summary,"issues":issues}
print(json.dumps(out,ensure_ascii=False,indent=1))
