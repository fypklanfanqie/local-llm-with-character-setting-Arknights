# Rhodes Island Terminal — YouTube Video Script (English)

> **Target length**: 8–12 minutes
> **Tone**: Developer-friendly, passionate, genuine — not clickbait
> **Target audience**: Arknights fans + Android devs + AI/LLM enthusiasts

---

## SECTION 0 — HOOK (0:00–0:30)

**[Screen recording: App opening animation → PRTS terminal boot sequence → chat screen with Amiya's first greeting]**

**Voiceover:**

What if I told you — you can build a fully offline AI chat app with 20 Arknights operators, each with their own personality, voice, and backstory — running entirely on your phone, using nothing but Kotlin and an open-source inference engine?

And what if it also came with a PRTS-inspired sci-fi terminal UI, a 26-track BGM player, Japanese and Chinese TTS voice synthesis, and a real-time performance overlay that shows CPU, GPU, and NPU usage — all in a single APK?

This is Rhodes Island Terminal. A fan-made, fully open-source Arknights AI roleplay app for Android. And in this video, I'm going to show you how it works, how it's built, and how you can run it yourself.

---

## SECTION 1 — WHY I BUILT THIS (0:30–1:30)

**[B-roll: scrolling through various AI chat apps on Play Store; showing their limitations — paywalls, ads, no offline mode, generic chatbots]**

So, why build yet another AI chat app?

A few months ago, I was playing around with some Arknights-themed character chatbots on WeChat mini-programs. They were fun, but they had three major problems.

First — they were always online. If you had spotty internet, the experience fell apart.

Second — the characters felt shallow. Generic system prompts. No real personality depth. No voice. No music. Just a text box with an avatar.

And third — every decent one was behind a paywall. Tokens. Credits. Subscriptions. You name it.

So I asked myself: what would it take to build the *ultimate* Arknights AI companion? One that works offline, respects your privacy, looks incredible, and is actually free?

The answer: about 14,000 lines of Kotlin, a custom MNN JNI bridge, and a lot of late nights.

**[Cut to GitHub repo star count / README]**

And today, it's completely open-source under the MIT license.

---

## SECTION 2 — THE OPERATORS (1:30–3:00)

**[Screen recording: Character selection screen → scrolling through the 20 operator cards → tapping into one to show details → starting a chat]**

Let's start with the heart of the app: the operators.

There are 20 of them. Amiya, Surtr, Texas, Logos, Mudrock, Goldenglow — if you play Arknights, you know these names. Each one has a hand-crafted system prompt that defines not just *what* they know, but *how* they speak.

**[Show Amiya's character card with system prompt excerpt]**

Take Amiya for example. Her prompt doesn't just say "you are Amiya from Arknights." It defines her core personality — her unconditional trust in the Doctor, her composure as a leader, the subtle vulnerability she only shows in private. It defines her speech patterns — how she uses "Doctor" to address you, her softness, her occasional moment of youthful hesitation.

**[Show La Pluma (羽毛笔) chatting — highlight her short, dreamy responses with "唔" and "嗯" filler words]**

Or La Pluma. Her prompt specifies that she speaks in short, drifting sentences, constantly uses filler words like "mm" and "oh", and sometimes zones out mid-conversation. The model actually does this. It's not just a generic chatbot — it *performs* the character.

**[Show switching between multiple characters quickly]**

And you can swap between all 20 of them instantly. Every conversation is isolated. Every character is consistent.

This is what separates a character *roleplay* app from just another ChatGPT wrapper. The prompt engineering is the product.

---

## SECTION 3 — CLOUD AI BACKEND (3:00–4:00)

**[Screen recording: Settings → Backend Settings → showing DeepSeek / OpenAI / Qwen / GLM configurations]**

Now, how does the AI actually work?

The app supports two modes: cloud and local. Let me walk through both.

For cloud mode, you can connect directly to DeepSeek, OpenAI, Qwen, or GLM. You plug in your API key, choose your model, and you're good to go. All communication uses SSE streaming — so you get that real-time token-by-token typing effect, just like ChatGPT.

**[Show streaming response in chat — highlight tokens appearing one by one]**

The cool part is that the API endpoints and models are fully configurable. You're not locked into any single provider. If you have a self-hosted LLM endpoint — Ollama, vLLM, whatever — you can point it there. The app just speaks OpenAI-compatible chat completions over SSE.

**[Show a fast side-by-side: same prompt sent to DeepSeek vs Qwen, both streaming]**

And here's the thing: this all works out of the box. The app ships with presets for four major Chinese AI providers. You just enter your key.

But cloud mode is only half the story.

---

## SECTION 4 — LOCAL AI: MNN + NPU OFFLINE INFERENCE (4:00–6:30)

**[Dramatic transition — screen recording of toggling the "Local AI" switch in chat, then sending a message with Wi-Fi and mobile data both turned off]**

**[Show network toggle off → message still streams in real-time]**

This is where it gets really interesting.

The app can run large language models *entirely on your phone*, with no internet connection. It uses Alibaba's MNN inference engine — the same lightweight framework that powers on-device AI in a bunch of Chinese apps.

But the real magic is the NPU acceleration.

**[Show tech diagram or screen recording of Qualcomm HTP working]**

If your phone has a Qualcomm Snapdragon chip — which covers basically every flagship Android phone from the last few years — the app can offload inference to the Hexagon Tensor Processor, Qualcomm's NPU. This is the same hardware that powers camera AI and voice assistants. And we're using it to run a 4-billion-parameter language model.

**[Show performance overlay while local model is generating — highlight NPU usage bars]**

The MNN engine talks to the NPU through Qualcomm's QNN SDK. There's a custom JNI bridge — written in C++, compiled through CMake — that handles tokenization, inference loop, KV cache management, and streaming output back to Kotlin. All of this runs in-process. No external server. No cloud dependency.

**[Show model management screen — downloading Qwen3-4B-MNN model, SHA256 verification progress]**

Models are downloaded through a built-in manager with pause, resume, and SHA256 integrity verification. You grab a `.mnn` model once, and you can chat forever without burning API credits.

Now, I'll be honest — a 4B model running on a phone isn't going to match GPT-4. But for character roleplay? It's surprisingly good. The system prompts do a lot of the heavy lifting. And the fact that it runs at all, on a device in your pocket, with zero network calls — that still feels like magic to me.

---

## SECTION 5 — VOICE SYNTHESIS (TTS) (6:30–7:30)

**[Screen recording: tapping the speaker icon on an operator's message → hearing the voice line play]**

What's a character without a voice?

Every operator in the app has voiced lines. And I'm not talking about pre-recorded audio files — those exist too, for the in-game voice lines — but I'm talking about full text-to-speech synthesis.

**[Show Volcano Engine TTS configuration]**

The app integrates ByteDance's Volcano Engine TTS — that's the same Doubao voice synthesis that powers a lot of Chinese AI products. It supports both Chinese and Japanese voices, which is perfect for Arknights characters who canonically speak both languages.

**[Demo: type a message, Amiya responds with text, then tap play → hear Japanese TTS read her reply]**

You can have Amiya reply to you in Japanese, or La Pluma in Chinese. The voice engine renders the actual AI-generated response — not just preset lines. So every conversation becomes a voice conversation.

**[Show the TTS settings — voice selection, speed control]**

And yes, there are speed controls. And yes, it works with local AI mode too. Fully offline text-to-speech is on the roadmap.

---

## SECTION 6 — BGM PLAYER (7:30–8:00)

**[Screen recording: Music tab → scrolling through 26 Arknights OST tracks → playing "Speed of Light" or another iconic track]**

There's also a built-in BGM player with 26 tracks from the Arknights original soundtrack.

**[Show music playing in background while chatting]**

It plays in the background while you chat. So you can have a conversation with Texas while "Renegade" plays in the background, and it genuinely feels like you're inside the Arknights universe.

Small detail: the music player is built on ExoPlayer — Android's media3 library — so it handles background playback, audio focus, and all the edge cases properly.

---

## SECTION 7 — PRTS TERMINAL UI DESIGN (8:00–9:30)

**[Slow pan through the app UI — show the dark theme, gold accents, chat bubbles, bottom navigation]**

Let's talk about the UI, because I spent an unreasonable amount of time on this.

The entire design language is based on PRTS — the in-universe operating system from Arknights. Deep charcoal backgrounds. Warm gold accents. Monospace terminal fonts. Glowing borders.

**[Show the Color.kt file with the PRTS color palette]**

Every color in the app comes from a custom palette. The background is `#0A0A0F` — nearly black, but with just enough blue to feel cold and sci-fi. The gold is `#C9A87C` — warm, muted, like aged brass. Not bright yellow. Not orange. Gold. It took about six iterations to get right.

**[Show the chat screen in detail — Modern IM-style bubble radii (16dp), the input bar, animated send button]**

The chat interface uses a modern IM-style bubble design with 16dp rounded corners, a subtle tail on each bubble, and smooth crossfade animations. Streaming text fades in character by character with a soft glow.

**[Show the Liquid Glass bottom navigation bar — highlight the frosted glass effect]**

And then there's the Liquid Glass navigation bar at the bottom. This uses a real Gaussian blur and refraction shader — it's not a static gradient. The content behind the bar actually bends and blurs through it. This is implemented at the native View level with a custom `LiquidGlassView` that lives as a sibling to the Compose content, recording and refracting the root view's drawing.

**[Show day/night comparison, or just emphasize the dark sci-fi atmosphere throughout]**

The whole thing is built with Jetpack Compose and Material 3, forced dark mode only. There is no light theme. This is a terminal. Terminals don't have light mode.

---

## SECTION 8 — PERFORMANCE OVERLAY (9:30–10:30)

**[Screen recording: local AI chat with performance overlay floating on screen — show CPU %, GPU %, NPU %, temperature]**

One feature I'm particularly proud of is the performance overlay.

When you're running a local model, you want to know what's happening under the hood. So the app includes a real-time performance monitor that floats on top of the chat.

**[Point to each metric on screen]**

It shows CPU usage per core, GPU utilization, NPU activity, and chip temperature. On non-rooted devices, we can't read per-process GPU and NPU counters — those require root — but we use `Process.getElapsedCpuTime` for CPU, and label GPU/NPU as monitored but N/A without root. On rooted devices, you get full visibility.

**[Show the overlay toggling on/off]**

The overlay itself is built on the same Liquid Glass technology — it's a frosted glass panel that blends into the UI. It's not a separate window. It's rendered in-process, inside the app, using the Android view system. Zero performance impact from IPC or separate processes.

**[Show code snippet of PerformanceCollector or LiquidGlassRenderer]**

Under the hood, there's a background coroutine that samples `/proc/stat` and `/sys/class/thermal` every second, parses the raw values, and pushes them into a Compose `StateFlow`. The glass renderer reads the CPU topology — big core IDs, frequencies — from a custom JNI library that queries the Linux sysfs directly. It's all Kotlin coroutines, no third-party monitoring SDKs.

---

## SECTION 9 — TECH STACK DEEP DIVE (10:30–12:00)

**[Screen recording: scrolling through the project structure in Android Studio / VS Code]**

Alright, let's do a quick tech stack tour for the developers watching.

**[Show architecture diagram — MVVM + Repository + Manager]**

The app follows MVVM architecture with manual dependency injection through an `AppContainer`. No Dagger. No Hilt. Just a plain Kotlin object graph wired up at app startup. It keeps things simple and compile times fast.

**[Show key directories: data/remote/, data/repository/, provider/cloud/, provider/local/]**

The data layer is split into remote and local. Remote uses Retrofit2 + OkHttp3 for API calls. Local uses Room for conversation persistence and DataStore for user preferences.

The AI providers are pluggable. There's a `ChatProvider` interface with cloud and local implementations, and a `BackendManager` that handles switching between them. The cloud provider implements SSE streaming by wrapping OkHttp's response body in a `BufferedSource` and parsing `data:` chunks from the event stream. The local provider wraps the MNN JNI bridge — it pipes input text through the tokenizer, runs the inference loop, decodes tokens one by one, and emits them through a Kotlin `Flow`.

**[Show mnn_jni.cpp code excerpt — the inference loop]**

The JNI layer is about 500 lines of C++. It handles model loading, `forward()` calls, KV cache allocation, and the token-by-token decode loop. Since the original MNN `PipelineModule::load()` had issues with relative cache paths and some devices with stub `libOpenCL.so`, there's a lot of defensive coding around backend selection and fallback paths.

**[Show build.gradle.kts — CMake externalNativeBuild config]**

On the build side, the native code compiles through CMake as an `externalNativeBuild` target in Gradle. The prebuilt `libMNN.so`, `libQnnHtp.so`, and `libQnnSystem.so` are bundled in `jniLibs`. The whole thing targets `minSdk 24` and ships as a single APK.

**[Quick stats on screen]**

Quick stats: the entire project is about 14,000 lines of Kotlin, 500 lines of C++, 20 character system prompts, 26 BGM tracks, supports 4 cloud AI providers plus local MNN inference, and renders Markdown with VS Code Dark+ syntax highlighting for code blocks.

---

## SECTION 10 — BUILD IT YOURSELF (12:00–13:00)

**[Screen recording: terminal — `git clone` → `./gradlew assembleDebug` → APK output]**

If you want to try this yourself, it's fully open-source on GitHub. The link is in the description.

You'll need Android Studio Hedgehog or later, JDK 21, and NDK 27c. Clone the repo, open it in Android Studio, and hit build. The `cpu_sys_jni` native module compiles automatically — no extra config needed.

For the MNN local AI features, you'll need to set up the MNN prebuilt directory and point `MNN_DIR` in `gradle.properties`. There's full documentation in the README.

**[Show the app running on a physical device]**

And if you just want the APK without the local AI, you can compile without MNN and use it as a cloud-only chat client. It still has all the characters, TTS, BGM, and the full PRTS UI experience.

---

## SECTION 11 — DISCLAIMER & WHAT'S NEXT (13:00–13:45)

**[Show disclaimer text on screen]**

Important note: this is a fan project. All Arknights characters, artwork, and music are copyrighted by Hypergryph. This app is for learning and community purposes only — it's not a commercial product. All payment and ad features have been explicitly removed.

**[Show GitHub issues / roadmap]**

What's next? I'm working on:
- llama.cpp backend as an alternative to MNN, for models in GGUF format
- Fully offline TTS using on-device voice models
- Conversation export and sharing
- More operators — the prompt system makes it easy to add new characters

If you're an Arknights fan, an Android developer, or just someone who thinks running LLMs on phones is cool — give it a star on GitHub, and try building it yourself.

---

## SECTION 12 — OUTRO (13:45–14:30)

**[Screen recording: slow scroll through the app — showing chat with Amiya at night, BGM playing in background, performance overlay floating, Liquid Glass navigation bar glowing]**

**Voiceover:**

There's something really special about having these characters live on your device. Not through a server. Not through an API that might disappear tomorrow. But actually *on your phone*, in your pocket, ready to talk whenever you are.

That's the vision behind Rhodes Island Terminal. A little piece of Terra, running locally, with no strings attached.

If you enjoyed this, like and subscribe. Drop a comment if you have questions about the MNN integration, the prompt engineering, or anything else — I read every comment.

Links to the GitHub repo, build instructions, and model download guide are in the description.

Thanks for watching. Doctor, Rhodes Island awaits.

**[End screen: QR code to GitHub + "Rhodes Island Terminal" title card + background BGM fade out]**

---

## APPENDIX — SHOT LIST

| Timestamp | Visual | Notes |
|-----------|--------|-------|
| 0:00–0:30 | App opening, boot animation, Amiya greeting | Hook segment — fast cuts |
| 0:30–1:30 | Play Store AI apps, WeChat mini programs, GitHub repo | Problem → solution |
| 1:30–3:00 | Character screen, detailed prompt views, chatting with different operators | Core feature demo |
| 3:00–4:00 | Settings → Backend config, SSE streaming demo | Cloud AI |
| 4:00–6:30 | Toggle to local AI, network off, streaming still works, model download, performance overlay, code excerpts | Local AI — main technical highlight |
| 6:30–7:30 | TTS demo — tap speaker icons, Japanese + Chinese voices | Voice synthesis |
| 7:30–8:00 | Music tab, OST playback, music playing during chat | BGM player |
| 8:00–9:30 | Slow UI pans, color palette, code excerpt of theme, Liquid Glass nav bar | UI/design deep dive |
| 9:30–10:30 | Performance overlay in action, metric explanations, code excerpt | Performance monitoring |
| 10:30–12:00 | Android Studio project structure, architecture diagram, key code files | Tech stack for developers |
| 12:00–13:00 | Terminal: clone → build, APK output, phone demo | Build guide |
| 13:00–13:45 | Disclaimer text, GitHub issues/roadmap | Wrap-up |
| 13:45–14:30 | Cinematic app walkthrough, outro narration | Emotional close |

---

## APPENDIX — KEY TALKING POINTS (if you go off-script)

- **Why Kotlin and not Flutter/React Native?** — Native performance for MNN JNI integration, Compose is the future of Android UI, and the Liquid Glass effects need direct access to the Android view system.
- **Why MNN and not llama.cpp?** — MNN has first-class Qualcomm NPU support through QNN, which is critical for fast local inference on Snapdragon devices. The project originally targeted llama.cpp but migrated to MNN for the NPU acceleration.
- **Model performance** — Qwen3-4B quantized for MNN runs at about 8–15 tokens/second on a Snapdragon 8 Gen 2 with NPU acceleration. Without NPU, CPU-only mode gets about 3–5 tokens/second.
- **Privacy** — In local mode, nothing leaves your device. No telemetry. No analytics. The app doesn't even have internet permission required for local-only use — you can firewall it completely.
