# Rhodes Island Terminal

[简体中文](README.md) ｜ **English** ｜ [日本語](README_JA.md)

> A fan-made Arknights AI role-play chat app: on-device **MNN local LLM inference** (adaptive CPU / GPU / NPU + deep thinking) plus a cloud dual-engine, with built-in Arknights BGM / voice / character art. All local conversation data stays on your device — the app works fully offline.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)
[![MNN](https://img.shields.io/badge/Local%20LLM-MNN-00C4A7?logo=alibabacloud)](https://github.com/alibaba/MNN)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 🖼️ Feature Tour

| | | |
|:---:|:---:|:---:|
| <img src="docs/screenshots/01-feed.jpg" width="360" alt="Feed / character cards"/><br>**① Feed · Character Cards**<br><sub>Swipe through full-screen art, start chatting in one tap</sub> | <img src="docs/screenshots/02-local-models.jpg" width="360" alt="Local models"/><br>**② Local LLMs**<br><sub>Adaptive MNN CPU/GPU/NPU, fully offline</sub> | <img src="docs/screenshots/03-moments.jpg" width="360" alt="Moments"/><br>**③ Moments**<br><sub>Characters post on their own; like and comment</sub> |
| <img src="docs/screenshots/04-cloud-api.jpg" width="360" alt="Cloud API setup"/><br>**④ Cloud AI · API Setup**<br><sub>Preset providers — just paste your key</sub> | <img src="docs/screenshots/05-novel.jpg" width="360" alt="Interactive novel"/><br>**⑤ Interactive Novel**<br><sub>Narration + dialogue script, AI writes whole chapters</sub> | <img src="docs/screenshots/06-worldview-lorebook.jpg" width="360" alt="Worldview and lorebook"/><br>**⑥ Worldview & Lorebook**<br><sub>Settings injected into chat, keyword-triggered</sub> |
| <img src="docs/screenshots/07-guide.jpg" width="360" alt="In-app guide"/><br>**⑦ In-App Guide**<br><sub>Searchable manual for every feature</sub> | <img src="docs/screenshots/08-daily-supply.jpg" width="360" alt="Daily supply and shop"/><br>**⑧ Daily Supply & Gift Shop**<br><sub>Check in for LMD, give gifts for affinity</sub> | <img src="docs/screenshots/09-multilang.jpg" width="360" alt="Multilingual UI"/><br>**⑨ Multilingual UI**<br><sub>Chinese / English / Japanese — AI replies follow</sub> |

---

## 📖 Feature Details

### ① Feed · Character Cards

- A TikTok-style full-screen card feed: swipe vertically through **384 Rhodes Island operators** (20 with built-in voice and local art + 364 auto-generated, each with in-game skills / talents in their persona)
- Each card jumps straight to **Start Chat / Affinity / Novel**; the top glass bar opens **Moments / Encounters / Group Chat / New conversation**
- The character art's dominant color drives the buttons and dock accent in real time

### ② Local LLMs · On-Device MNN Inference

- **MNN CPU / OpenCL GPU / QNN NPU** adaptive backend scheduling (auto-recommended or forced), with automatic fallback to CPU when GPU fails — fully offline, data never leaves the device
- Built-in model market: **13 MNN models including Qwen3.5**, multi-mirror download (ModelScope → hf-mirror → HuggingFace), resumable transfers and SHA-256 integrity checks
- **Local deep thinking** levels (AUTO / SHORT / MEDIUM / LONG) with a collapsible reasoning block; when memory runs short the context is halved per round instead of crashing
- Experimental speedups (lookahead / multi-token decoding) only activate after passing on-device benchmarks
- **Liquid-glass performance overlay**: live token/s, CPU / GPU / NPU, temperature and memory

### ③ Moments

- A WeChat-Moments-style feed where characters **post on their own** (AI-written captions + AI-generated images); you can like and comment, and they reply to your comments
- You can post with images too; scheduled auto-posting (8 AM – 11 PM) keeps the feed alive

### ④ Cloud AI · LLM API Setup

- OpenAI-compatible `/chat/completions` with **direct SSE streaming**; preset providers include DeepSeek / OpenAI / Qwen / Zhipu — pick one, paste your API key, test the connection with one tap
- Switch between cloud and local engines at any time; conversations are stored per character
- Multimodal models accept images (up to 3), PDFs (first 6 pages) and plain text files

### ⑤ Interactive Novel

- A "narration + character dialogue" script format: the AI continues a whole chapter in one go, rendered as color-coded bubbles per speaker
- Chapters auto-save locally so you can pick up anytime; multiple stories and chapters supported

### ⑥ Worldview & Lorebook

- **Worldview**: custom premises (e.g. "the story takes place in a post-apocalyptic wasteland") injected into the prompt, bindable to a single character's DM or a specific group chat
- **Lorebook**: a keyword-triggered background library, with **SillyTavern lorebook JSON** import; scan depth, token budget and recursive scanning are configurable

### ⑦ In-App Guide

- A searchable manual inside the app: popular searches plus feature categories (Getting Started / Cloud AI / Local Models / Chat / TTS / Worldview & Lorebook / Characters · Greetings · Group Chat…)
- It follows the UI language (Chinese / English / Japanese) — type a keyword and get the answer

### ⑧ Daily Supply & Gift Shop

- Check in daily for **10,000 LMD**; the gift shop supports custom gifts (price / affinity bonus / stock) that you can buy and give to characters
- **Affinity system**: level cap 200, a gift wall you can revisit, and special encounter event archives

### ⑨ Multilingual UI · AI Replies Follow

- One-tap switch between **Follow system / 简体中文 / English / 日本語**
- It is not just the UI: **characters answer in the selected language too** (Chinese personas, worldviews and lorebooks need no translation — the AI understands them as-is)

---

## ✨ More Features

- **🔊 Dual TTS** — offline system TTS (default, zero config) plus Volcengine Doubao voice cloning in the cloud (per-character voice, Chinese and Japanese); thinking blocks are stripped before speaking
- **🎬 Encounters · Character Videos** — Seedance-generated short clips (auto-triggered / playback / export / history) with custom reference images and scenes
- **💬 Group Chat** — multi-character rooms: @-mentions always answer, members chat among themselves in the background, with new-message notifications
- **⏰ Proactive Greetings** — 15-minute periodic scheduling with exact-alarm fallback, so characters message you first; WeChat-style banner notifications that survive restarts
- **📝 Markdown Rendering** — code highlighting and math formulas, with collapsible deep-thinking blocks
- **🎵 Music Player** — built-in Arknights BGM plus a NetEase Cloud OST catalogue, online search and local imports, playing in the background (now under Settings → Music)
- **🎨 PRTS Dark Terminal UI** — deep navy with Rhodes Island gold accents, frosted-glass panels and serif headings

## 384 Operators

> The table below lists the 20 base operators (built-in voice and local art); the remaining 364 are generated from persona profiles with in-game skills / talents and E2 / outfit art (loaded online). All of them appear on the Characters page and in the feed.
>
> Names are kept in their original in-game Chinese form: the character data in this fan project is Chinese-primary, so the app displays these names in every UI language.

| # | Operator | Race | Role |
|---|----------|------|------|
| 1 | 羽毛笔 | 黎博利 (Liberi) | 近卫干员 / 调酒师 — Guard / Bartender |
| 2 | 阿米娅 | 卡特斯 / 奇美拉 (Cautus / Chimera) | 罗德岛公开领袖 — Public leader of Rhodes Island |
| 3 | 艾雅法拉 | 卡普里尼 (Caprinae) | 火山学家 / 天灾信使 — Volcanologist / Catastrophe Messenger |
| 4 | 澄闪 | 菲林 (Feline) | 理发师 / 驭械术师 — Barber / Caster |
| 5 | 泥岩 | 萨卡兹 (Sarkaz) | 萨卡兹雇佣兵 / 不屈者 — Sarkaz mercenary / Unyielding |
| 6 | 逻各斯 | 萨卡兹 / 妖 (Sarkaz / Banshee) | 精英术师 / 咒术大师 — Elite Caster / Master of curses |
| 7 | 蜜莓 | 札拉克 (Zalak) | 医疗部 / 草药医生 — Medical Department / Herbalist |
| 8 | 遥 | 阿戈尔 (Aegir) | 东国艺人 — Higashi performer |
| 9 | 维什戴尔 | 萨卡兹 (Sarkaz) | 雇佣兵领袖 / 巴别塔议长 — Mercenary leader / Babel speaker |
| 10 | 左乐 | 斐迪亚 (Pythia) | 司岁台秉烛人 — Candle-bearer of the Sui office |
| 11 | 麦哲伦 | 黎博利 (Liberi) | 莱茵生命外勤专员 — Rhine Lab field specialist |
| 12 | 黍 | 岁兽碎片 (Sui fragment) | 炎国农业天师 — Yan agricultural master |
| 13 | 史尔特尔 | 萨卡兹 (Sarkaz) | 近卫干员 — Guard |
| 14 | 晓歌 | 黎博利 (Liberi) | 先锋干员 / 情报官 — Vanguard / Intelligence officer |
| 15 | 林 | 札拉克 (Zalak) | 龙门合作者 — Lungmen collaborator |
| 16 | 拉普兰德 | 鲁珀 (Lupo) | 近卫干员 / 领主 — Guard / Lord |
| 17 | 送葬人 | 萨科塔 (Sankta) | 拉特兰公证所执行者 — Laterano Notarial Hall executor |
| 18 | Mon3tr | 未公开 (Classified) | 罗德岛特别顾问 — Rhodes Island special consultant |
| 19 | 星源 | 黎博利 (Liberi) | 莱茵生命能量科研究员 — Rhine Lab energy researcher |
| 20 | 德克萨斯 | 鲁珀 (Lupo) | 企鹅物流信使 / 先锋干员 — Penguin Logistics courier / Vanguard |

Every operator ships with a detailed system prompt defining personality, speech style and backstory.

## Tech Stack

| Area | Technology |
|------|------------|
| Language | Kotlin 100% (2.0.0) |
| UI | Jetpack Compose + Material 3, liquid glass (frosted blur + animated gradient mesh background) |
| Architecture | MVVM + Repository + Manager, manual DI (AppContainer) |
| Local inference | **MNN adaptive engine** (CPU / OpenCL GPU / QNN NPU), arm64-v8a only, NDK 27 prebuilt libraries |
| Deep thinking | Local thinking levels + byte budget + chat-template capability probing |
| Benchmarking | Six-scenario, four-quadrant benchmarks with DataStore certification and a device-side gate for experimental features |
| Video generation | Seedance 2.0 (Volcengine Ark / media relay protocol), WorkManager pipeline, ExoPlayer playback |
| TTS | Android system TTS + Volcengine Doubao voice cloning |
| Networking | Retrofit 2.11 / OkHttp 4.12 / kotlinx-serialization |
| Data | Room 2.6.1 / DataStore 1.1.1 |
| Media | Media3 1.3.1 (ExoPlayer) / Coil 2.6 |
| Background | WorkManager 2.9.1 (greeting cycle + Seedance video pipeline) |

## Project Structure

```
app/src/main/java/com/rhodesisland/terminal/
├── config/          # App config, operator table, model providers, asset paths (art/voice/BGM/background)
├── data/            # model / local (Room, DataStore) / remote (Retrofit, NetEase, Seedance) / repository
├── llm/             # ★ Local LLM core: backend (CPU/GPU/NPU scheduling, health, preheat), benchmark, 
│                    #   metrics, profile (performance modes/plans), template probing, thinking levels
├── provider/        # Chat provider abstraction and switching (cloud / local)
├── tts/             # Dual TTS engines (system TTS + Volcengine Doubao)
├── video/           # Seedance pipeline: prompt generation, validation, state machine, reference/scene storage, export
├── download/        # Multi-mirror MNN model downloads (resume, chunk merge, SHA-256/size validation)
├── manager/         # Audio / Model / Tts managers
├── perfmon/         # Liquid-glass performance overlay
├── notification/    # Proactive greeting notifications
├── service/         # Foreground service keeping local inference alive
├── work/            # WorkManager scheduling (greeting cycle / exact alarms / Seedance pipeline)
├── ui/              # glass components, chat / characters / feed / music / models / settings / theme / video / navigation
└── util/            # Utilities (battery whitelist & autostart guides, art storage, Markdown, etc.)
```

## Build

### Requirements

- Android SDK (compileSdk 34)
- **JDK 17+** (verified locally with Temurin 17: `D:/jdk-temurin-17/jdk-17.0.20+8`)
- NDK 27.2.12479018 (`ndkVersion` in `app/build.gradle.kts`)

### Notes

- **Native libraries are prebuilt** and committed under `app/src/main/jniLibs/arm64-v8a/` (`libMNN.so`, `libmnn_jni.so`, `libcpu_sys_jni.so`, `libbackend_probe.so`, `libc++_shared.so`). Gradle never invokes CMake, so **no `MNN_DIR` is required**.
- Only `arm64-v8a` is packaged, matching the prebuilt MNN libraries.

### Commands

```bash
# Compile (when verifying .kt changes always use --rerun-tasks --no-build-cache to avoid fake UP-TO-DATE)
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache

# Debug build
./gradlew :app:assembleDebug

# Release build (debug signing by default; configure your own signing before publishing)
./gradlew :app:assembleRelease
```

## Local AI: Getting Started

1. Open the app → **Settings** → **Model Manager**
2. Pick a `.mnn` model to download (e.g. `Qwen3.5-2B-MNN`, resumable)
3. Go back to chat → switch to **Local AI** → stream replies offline

Model files live in:
```
Android/data/com.rhodesisland.terminal/files/models/
```

## Assets

Character art, voice lines and BGM are stored under `app/src/main/assets/`:
- `picture/` — operator art (webp)
- `music/` — built-in BGM (mp3) + operator voice (wav)
- `background/` — backgrounds (webp/jpg)

---

## Disclaimer

> This is a fan-made Arknights project. All characters, artwork and music are copyright **Hypergryph**. It is intended for learning and exchange only and is not used commercially.

## Credits

> This app keeps improving thanks to the suggestions and feature ideas from friends in my Douyin and Bilibili fan groups — thank you all!
> 咕咕火 id V.I.P_520 白夜执 1185531741 不知道 buzhidao350543 辋川星梦 1023422036

## License

MIT License — see [LICENSE](LICENSE) for details.
