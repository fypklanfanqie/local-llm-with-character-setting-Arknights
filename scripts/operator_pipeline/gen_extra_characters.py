#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate ExtraCharacters.kt from operators.json + art_map.json."""
import json, io

OPS = json.load(open("C:/ak_work/operators.json", encoding="utf-8"))
ART = json.load(open("C:/ak_work/art_map.json", encoding="utf-8"))

PROF_CN = {"PIONEER":"先锋","WARRIOR":"近卫","TANK":"重装","SNIPER":"狙击",
           "CASTER":"术师","MEDIC":"医疗","SUPPORT":"辅助","SPECIAL":"特种"}

def sanitize_prompt(p):
    p = p.replace("$", "${'$'}")
    p = p.replace('"""', '"""')
    return p

def quote_list(items):
    inner = ", ".join('"' + i.replace('"','\\"') + '"' for i in items)
    return "listOf(" + inner + ")"

def esc(s):
    return s.replace('\\','\\\\').replace('"','\\"')

buf = []
buf.append("package com.rhodesisland.terminal.config")
buf.append("")
buf.append("import com.rhodesisland.terminal.data.model.Character")
buf.append("")
buf.append("/**")
buf.append(" * 全量干员（自动生成，勿手改）：384 份人格档案 + 游戏技能/天赋名 + 精二/皮肤立绘 URL。")
buf.append(" * 生成脚本见仓库 scripts/operator_pipeline/。")
buf.append(" */")
buf.append("object ExtraCharacters {")
buf.append("")
buf.append("    val ORDER: List<String> = listOf(")
ids = [o["id"] for o in OPS]
for i in range(0, len(ids), 8):
    chunk = ids[i:i+8]
    buf.append("        " + ", ".join('"%s"' % c for c in chunk) + ("," if i+8 < len(ids) else ""))
buf.append("    )")
buf.append("")
buf.append("    val ALL: Map<String, Character> = linkedMapOf(")
for o in OPS:
    nm = o["name"]
    role = PROF_CN.get(o.get("profession",""), o.get("profession",""))
    role_str = (role + "干员") if role else ""
    race = o.get("race","")
    code = o.get("code","") or nm
    skills = quote_list(o.get("skills", [])[:3])
    talents = quote_list(o.get("talents", [])[:2])
    prompt = sanitize_prompt(o["systemPrompt"])
    buf.append(f'        "{nm}" to Character(')
    buf.append(f'            id = "{nm}", name = "{nm}", code = "{esc(code)}",')
    buf.append(f'            role = "{esc(role_str)}", race = "{esc(race)}",')
    buf.append(f'            skills = {skills}, talents = {talents},')
    buf.append(f'            systemPrompt = """{prompt}""",')
    buf.append("        ),")
buf.append("    )")
buf.append("}")
buf.append("")
io.open("C:/ak_work/ExtraCharacters.kt","w",encoding="utf-8").write("\n".join(buf))
print("wrote ExtraCharacters.kt with", len(OPS), "operators,", round(len("\n".join(buf))/1024), "KB")
