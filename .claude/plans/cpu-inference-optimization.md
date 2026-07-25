# 本地 CPU 推理提速方案（MNN）

## 问题
JNI 层 `mnn_jni.cpp::nativeCreate` 的 `set_config` 只设了 `backend_type` / `thread_num` / `cache_path` 三个键，MNN 真正影响 CPU 速度的旋钮全未开启。已逐条核对 MNN 源码（`D:/MNN-src/transformers/llm/engine/src/llm.cpp::setRuntimeHint` + `Interpreter.hpp` HintMode 注释）确认可用性。

模型为 `taobao-mnn/*` 官方量化模型（int4/int8），与 KV int8 / 动态量化兼容。`set_config` 走 `config_.merge()` 且在 `load()` 之前调用，故运行期设置在 `initRuntime()` 生效。

## Phase 1 — CPU 推理调优（核心，立即实施）

**只改一个文件**：`app/src/main/cpp/mnn_jni.cpp` 的 `nativeCreate`。

把 `set_config` 的 JSON（当前 `char conf[512]` + snprintf 三键）改为 `std::string` 拼接，并在 **`backend_str == "cpu"`** 时追加 CPU 专用调优键（GPU/NPU 后端不加，避免误用）：

| 键 | 值 | 作用 | 出处 |
|---|---|---|---|
| `precision` | `"low"` | 钉死最快档（fp16/int8 计算路径），显式设置防模型默认漂移 | `llm.cpp:220` Precision_Low |
| `power` | `"high"` | BackendConfig Power_High，调度上更激进 | `llm.cpp:213` |
| `attention_mode` | `10` | flash attention(10/8=1) + Q/K/V int8 KV 量化(10%8=2) → KV 带宽 ~4× 降，**decode 提速主杠杆**（上下文越长越明显） | `Interpreter.hpp:222` ATTENTION_OPTION；`llm.cpp:167` |
| `dynamic_option` | `2` | 激活 block 动态量化 → int8 GEMM，linear 层提速 | `Interpreter.hpp` DYNAMIC_QUANT_OPTIONS；`llm.cpp:186` |

默认（不设）等价 `attention_mode=8`（flash 开、KV 不量化）+ `dynamic_option=0`（关）。改为 10/2 是纯运行期配置，无需重导模型。

**质量风险**：KV int8 / 激活动态量化有极小精度损失。若个别模型退化，把 `attention_mode` 调 `9`（Q/K int8、V float，更稳）或 `dynamic_option` 调 `0` 即可，单行改动。

**编译**：改 `.cpp` 后用 `./gradlew :app:assembleDebug`（externalNativeBuild 会重编 `libmnn_jni.so`；MNN_DIR 已在 gradle.properties 配好）。按 memory `native-build-setup`，必要时 `rm -rf app/.cxx` 清缓存。

**验收**：logcat 看 `MnnJni` 打出的新 config；用 `nativeGetMetrics` 的 `decode_us`/`gen_seq_len` 算 tps，对比改前后。长上下文多轮对话提升最明显。

## Phase 2 — Lookahead 投机解码（可选，下一步；默认关，opt-in）

`speculative_type: "lookahead"` 用 prompt/历史的 n-gram 投机解码，**无需 draft 模型**，CPU 上重复/代码类文本 1.5–3×。源码已编进 libMNN.so（`speculative_decoding/generate.cpp` 工厂支持 `lookahead`）。

风险：改生成循环、与流式/abort 交互需测；故做成**设置开关**（默认关），用户自行 A/B。

实施（比 Phase 1 多几处 plumbing）：
1. `SettingsStore` 加 `LLM_LOOKAHEAD`（booleanPreferencesKey，默认 false）+ `SettingsRepository.llmLookahead` flow/setter。
2. 纳入现有「配置变更检测」：加 `LLM_LAST_LOOKAHEAD`，与 threads/context/backend 一起进 `hasConfigChanged`/`acknowledgeLlmConfig`，使设置页「下次发送将自动重载」横幅对它生效（lookahead 在 `load()` 前读，改值需重载模型才生效）。
3. `MnnBridge.nativeCreate` 签名加 `lookahead: Boolean`；`MnnBackend.initialize` 透传；`LocalChatProvider.chat` 读设置传入。
4. JNI `nativeCreate` 加 `jboolean lookahead`，CPU 模式且为 true 时在 config 追加 `"speculative_type":"lookahead","ngram_match_maxlen":4,"draft_predict_length":3`（值见 `llmconfig.hpp:535+`）。
5. `BackendSettingsScreen` 加一个 Switch（挨着 CPU 提频开关）。

> lookahead 走 `llm->response()` 内部的 generation strategy，token 仍经 `JniStreamBuf` 回调，现有 `shouldAbort` 轮询不变；无需改流式架构。

## 不做
- `kvcache_mmap`：落盘 IO 反而拖慢 decode。
- 线程数超过大核：跑到小核更慢（现状已 `min(用户, 大核数, 温度上限)`，正确）。
- Phase 1 不加 UI 开关：硬编码安全-快速默认值，简化；如需可回退再加（Phase 1b）。

## 建议执行顺序
先落 Phase 1（单文件、低风险、立竿见影），实测 tps 后再决定是否上 Phase 2。
