# 干员数据管线（operator_pipeline）

从「384 份人格档案 + 游戏数据 + PRTS wiki」自动生成全量干员，产出：

- `app/src/main/java/com/rhodesisland/terminal/config/ExtraCharacters.kt` — 364 位干员（20 位手写基础干员之外的自动生成部分），含人格 system prompt + 游戏技能/天赋名
- `app/src/main/java/com/rhodesisland/terminal/config/ExtraArtPaths.kt` — 精二/皮肤/精一立绘 PRTS wiki 直链 URL（网络加载）

## 数据源

| 来源 | 用途 |
|---|---|
| [missercatos/arknights-skill](https://github.com/missercatos/arknights-skill)（384 份人格档案） | 干员人设 system prompt、种族/职业 |
| `Kengxxiao/ArknightsGameData` 的 `character_table.json` + `skill_table.json` | 干员名单、技能名、天赋名 |
| `media.prts.wiki` | 立绘直链（MediaWiki md5 哈希路径，URL 可直接由文件名算出，无需 API） |

## 步骤

```bash
# 1) 下载人格仓库 zip 到 C:/ak_work/personas，游戏数据到 C:/ak_work/*.json
# 2) 构建干员 JSON
python3 build_operators.py        # -> operators.json
# 3) 探测立绘 URL（md5 + HEAD 验证，不调 API）
python3 build_art.py              # -> art_map.json
# 4) 生成 Kotlin
python3 gen_extra_characters.py   # -> ExtraCharacters.kt（拷入 app）
# （ExtraArtPaths.kt 由同步骤的 python 内联生成）
```

注：脚本内数据目录硬编码为 `C:/ak_work`，改动后请自行调整。
