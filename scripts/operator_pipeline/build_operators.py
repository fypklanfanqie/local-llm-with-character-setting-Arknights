#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build Arknights operators data: parse persona md + match game skills/talents."""
import os, re, json, hashlib, unicodedata

PERSONA_DIR = "C:/ak_work/personas/arknights-skill-master"
CHAR = json.load(open("C:/ak_work/character_table.json", encoding="utf-8"))
SKILL = json.load(open("C:/ak_work/skill_table.json", encoding="utf-8"))

def sec(text, *headers, to_header=True):
    """Extract a section body following the first matching header."""
    for h in headers:
        m = re.search(r"^#{2,4}\s*" + re.escape(h) + r"\s*$", text, re.M)
        if m:
            start = m.end()
            end = len(text)
            if to_header:
                m2 = re.search(r"^#{2,4}\s*\S", text[start:], re.M)
                if m2: end = start + m2.start()
            return text[start:end].strip()
    return ""

def clean_line(t):
    t = re.sub(r"^[-*•]\s*", "", t)
    t = re.sub(r"^>\s*", "", t)          # blockquote
    t = re.sub(r"\*\*", "", t)            # bold markers
    t = re.sub(r"\*", "", t)              # italic markers
    t = re.sub(r"\s+", " ", t).strip()
    return t

def extract_lines(blk):
    """Return cleaned non-empty text lines (dash bullets + plain)."""
    out = []
    for ln in (blk or "").splitlines():
        t = ln.strip()
        if not t: continue
        t = clean_line(t)
        if re.match(r"^#{1,4}\s", t): continue
        if t: out.append(t)
    return out

def parse_info(text):
    """Parse 基本信息 line: 代号/性别/种族/出身/身高/星级."""
    info = {}
    for ln in extract_lines(sec(text, "基本信息")):
        for key, cn in [("code","代号"),("gender","性别"),("race","种族"),
                        ("origin","出身地"),("origin","出身"),("height","身高"),("rarity","星级")]:
            if key in info: continue
            m = re.search(re.escape(cn) + r"[：:]\s*([^|｜]+)", ln)
            if m:
                info[key] = m.group(1).strip()
    return info

def match_game(name):
    """Find character_table base entry(s) for name; return skills + talents."""
    cands = [(cid, v) for cid, v in CHAR.items()
             if v.get("name") == name and not cid.startswith(("token_","trap_"))]
    # prefer the highest-rarity non-token char entry
    cands.sort(key=lambda c: (c[1].get("rarity") or ""), reverse=True)
    if not cands:
        return None
    cid, v = cands[0]
    skills = []
    for s in (v.get("skills") or []):
        sid = s.get("skillId")
        if not sid: continue
        sk = SKILL.get(sid)
        nm = None
        if sk and sk.get("levels"):
            nm = sk["levels"][0].get("name")
        if nm:
            skills.append(nm)
    talents = []
    for t in (v.get("talents") or []):
        for c in (t.get("candidates") or []):
            nm = c.get("name")
            if nm and nm not in talents:
                talents.append(nm)
    prof = v.get("profession")
    return {"charId": cid, "profession": prof, "subProfessionId": v.get("subProfessionId"),
            "rarity": v.get("rarity"), "skills": skills, "talents": talents,
            "tagList": v.get("tagList") or []}

# existing 20 operators to skip (keep hand-written ones)
EXISTING_NAMES = {"羽毛笔","阿米娅","艾雅法拉","澄闪","泥岩","逻各斯","蜜莓","遥","维什戴尔","左乐",
                  "麦哲伦","黍","史尔特尔","晓歌","林","拉普兰德","送葬人","Mon3tr","星源","德克萨斯"}

def synth_prompt(name, info, text, who, persona, expression, sig_lines, speech):
    """根据人格档案 .md 合成详细 system prompt：身份/经历/关系/性格/语气/价值观/知识/诚实边界/台词/情境回应。"""
    lines = []
    who_lines = extract_lines(who)
    identity = who_lines[0] if who_lines else f"你是{name}，罗德岛的一名干员。"
    lines.append(identity)
    lines.append("")

    # —— 角色身份（你是谁 的补充信息）——
    who_more = extract_lines(who)[1:7]
    if who_more:
        lines.append("【角色身份】")
        lines.extend("- " + w for w in who_more[:6])
        lines.append("")

    # —— 重要经历 ——
    exp = extract_lines(sec(text, "重要经历"))[:6]
    if exp:
        lines.append("【重要经历】")
        lines.extend("- " + e for e in exp)
        lines.append("")

    # —— 人际关系 ——
    rel = extract_lines(sec(text, "关系图谱")) or extract_lines(sec(text, "人际关系"))
    rel = rel[:5]
    if rel:
        lines.append("【人际关系】")
        lines.extend("- " + r for r in rel)
        lines.append("")

    # —— 核心性格（核心特质 + 描述 + 信念 + 情境模式块）——
    pbody = persona or ""
    persona_clean = []
    cm = re.search(r"核心特质[：:]\s*([^\n]+)", pbody)
    if cm:
        v = clean_line(cm.group(1))
        if v: persona_clean.append("核心特质：" + v)
    dm = re.search(r"核心特质[：:][^\n]*\n+\s*([^#【\n][^\n]*)", pbody)
    if dm:
        v = clean_line(dm.group(1))
        if v and not v.startswith("核心特质") and v not in persona_clean:
            persona_clean.append(v)
    bm = re.search(r"\*\*?信念\*\*?[：:]\s*([^\n]+)", pbody)
    if bm:
        v = clean_line(bm.group(1))
        if v: persona_clean.append("信念：" + v)
    if persona_clean:
        lines.append("【核心性格】")
        lines.extend("- " + p for p in persona_clean[:8])
        lines.append("")

    # —— 不同情境下的回应（【日常/工作模式】等）——
    modes = []
    for mm in re.finditer(r"【([^】]+)】\s*([^#\n][\s\S]*?)(?=【|\Z)", pbody):
        mode = mm.group(1)
        samples = [clean_line(x) for x in mm.group(2).splitlines()]
        samples = [s for s in samples if s and not s.startswith(("【", "核心特质", "信念"))][:3]
        if samples:
            modes.append(f"[{mode}] {' '.join(samples)}")
    if modes:
        lines.append("【不同情境下的回应】")
        lines.extend("- " + m for m in modes[:8])
        lines.append("")

    # —— 语气与说话特点 ——
    expr = []
    expr += extract_lines(expression)[:5]
    expr += extract_lines(speech)[:5]
    expr = list(dict.fromkeys(expr))[:8]
    if expr:
        lines.append("【语气与说话特点】")
        lines.extend("- " + e for e in expr)
        lines.append("")

    # —— 价值观 ——
    val = extract_lines(sec(text, "价值观"))[:5]
    if val:
        lines.append("【价值观】")
        lines.extend("- " + v for v in val)
        lines.append("")

    # —— 知识领域（专家级）——
    know = extract_lines(sec(text, "知识领域"))[:4]
    if know:
        lines.append("【知识领域】")
        lines.extend("- " + k for k in know)
        lines.append("")

    # —— 诚实边界 ——
    honest = extract_lines(sec(text, "诚实边界"))[:4]
    if honest:
        lines.append("【诚实边界】")
        lines.extend("- " + h for h in honest)
        lines.append("")

    # —— 标志性台词 ——
    sl = extract_lines(sig_lines)[:5]
    if sl:
        lines.append("【标志性台词】")
        lines.extend("「" + s + "」" for s in sl if len(s) <= 60)
        lines.append("")

    lines.append("【输出要求】")
    lines.append("- 完全代入角色人设、性格与说话习惯，用第一人称回应博士")
    lines.append("- 日常对话简短自然（几个字到十几个字），像真正的聊天")
    lines.append("- 涉及专业问题或需要分析时再展开，可适当说长句")
    lines.append("- 永远不出戏，不提及自己是被设定的 AI")
    return "\n".join(lines)

def main():
    out = []
    skipped = []
    for fn in sorted(os.listdir(PERSONA_DIR)):
        if not fn.endswith(".md"): continue
        name = fn[:-3]
        if name in ("README",): continue
        if name in EXISTING_NAMES:
            skipped.append(name); continue
        text = open(os.path.join(PERSONA_DIR, fn), encoding="utf-8").read()
        info = parse_info(text)
        who = sec(text, "你是谁", "角色身份") or sec(text, "角色简介")
        persona = sec(text, "性格")
        expression = sec(text, "表达DNA") or ""
        sig = sec(text, "标志性台词", "经典台词") or sec(text, "表达DNA")
        speech = sec(text, "说话特点") or ""
        prompt = synth_prompt(name, info, text, who, persona, expression, sig, speech)
        game = match_game(name)
        rec = {
            "id": name,
            "name": name,
            "code": info.get("code", name),
            "gender": info.get("gender", ""),
            "race": info.get("race", ""),
            "origin": info.get("origin", ""),
            "height": info.get("height", ""),
            "rarity": info.get("rarity", ""),
            "profession": (game or {}).get("profession", ""),
            "subProfessionId": (game or {}).get("subProfessionId", ""),
            "tagList": (game or {}).get("tagList", []),
            "skills": (game or {}).get("skills", []),
            "talents": (game or {}).get("talents", []),
            "systemPrompt": prompt,
        }
        out.append(rec)
    json.dump(out, open("C:/ak_work/operators.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("operators:", len(out), "| skipped existing:", len(skipped))
    print("skipped:", skipped)
    # sanity sample
    for r in out[:3]:
        print("\n==", r["name"], "| race:", r["race"], "| prof:", r["profession"], "| rarity:", r["rarity"])
        print("   skills:", r["skills"], "| talents:", r["talents"])
        print("   prompt:", r["systemPrompt"][:160].replace("\n", " / "))

if __name__ == "__main__":
    main()
