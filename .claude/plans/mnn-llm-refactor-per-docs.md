# 按 MNN 官方文档重构本地 LLM 推理（修 FFFFF + 慢）

## 诊断（已核实）

**症状**：推理很久才输出 + 输出全是 FFFFF。

**根因链**（已逐条验证，非推测）：
1. 旧计划 `.claude/plans/cpu-inference-optimization.md` 提议在 `set_config` 加 `attention_mode=10`（flash + Q/K/V int8 KV 量化）+ `dynamic_option=2`（激活动态 int8 量化），已实施。
2. 实测这两键正是症状元凶（与 memory `mnn-cpu-inference-tuning` 记录一致）：
   - `attention_mode=10` → 对未带 int8 KV scale 的模型损坏 KV 缓存 → decode 退化为重复乱码 **FFFF**。
   - `dynamic_option=2` → 在不支持构建上走慢速回退 → **prefill 数分钟级**（"运算很久才输出"）。
3. 今天源码 `app/src/main/cpp/mnn_jni.cpp` 已移除这两键（当前 `set_config` 只设 backend_type/thread_num/cache_path/precision/power/kv_max_length/采样参数/lookahead）。
4. **但 `app/.cxx` 旧 CMake 缓存仍在** → 极大概率部署的 `libmnn_jni.so` 是**移除前的旧产物**（仍带 attention_mode=10/dynamic_option=2）。memory `native-build-setup` 明确："改了 cpp/CMake 后 `rm -rf app/.cxx` 清 CMake 缓存"；memory `gradle-kotlin-verify` 警告 build cache 会假象 UP-TO-DATE。源码改了但 .so 没真正重编 = 用户仍跑旧代码 = 仍 FFFF。

**已排除**：模型自带 `config.json` 不是元凶——实测 ModelScope `MNN/Qwen3-4B-MNN`、`Qwen2.5-1.5B-Instruct-MNN`、`Qwen3-1.7B-MNN` 的 config.json 均干净（仅 backend_type/thread_num/precision=low/memory=low/sampler，**无** attention_mode/dynamic_option）。

**另外两个偏离官方文档的问题**（影响 GPU 路径速度/正确性）：
- OpenCL 后端 `thread_num` 传的是用户值（4），但 MNN 文档明确："当选择opencl后端时，thread_num需设为68"（OpenCL 的 buffer 存储 + tuning wide 模式，非线程数）。传 4 → GPU 慢且可能行为异常。
- 未设 `use_mmap`（文档："手机上建议设成true"，避免多 GB 权重溢出 RAM）。
- 未设 `reuse_kv`（文档默认 false；多轮对话不复用 KV → 第 2 轮起重新 prefill 全历史 → 慢）。

## 目标
按 https://mnn-docs.readthedocs.io/en/latest/transformers/llm.html 及其子页，把本地 LLM 推理配置改为文档对齐的安全-快速默认，并**确保真正重编部署**。

## 改动

### 1. `app/src/main/cpp/mnn_jni.cpp`（核心）
重写 `nativeCreate` 的 `set_config`，按文档补齐/钉死安全值（merge 语义 = 覆盖模型 config 与任何旧值）：

| 键 | 值 | 依据 |
|---|---|---|
| `backend_type` | 传入 | 文档硬件配置 |
| `thread_num` | cpu/qnn=用户值；**opencl=68** | 文档："opencl后端thread_num需设为68" |
| `cache_path` | 模型目录/mnn_cachefile.bin | 已有，保留（修相对路径不可写崩，见 memory `mnn-crash-cachepath`） |
| `precision` | `"low"` | 文档默认（fp16/ARM82，最快且安全） |
| `memory` | `"low"` | 文档默认（运行时量化） |
| `use_mmap` | `true` | 文档："手机上建议设成true" |
| `reuse_kv` | `true` | 文档多轮对话复用 KV（第 2 轮起提速） |
| `attention_mode` | `8` | **文档默认推荐**：FlashAttention + KV 不量化。**显式钉 8 以覆盖任何旧/模型值**，根除 FFFF |
| `dynamic_option` | `0` | **文档默认**：不动态量化。**显式钉 0 以覆盖旧值**，根除慢 prefill |
| `temperature`/`topP`/`repetition_penalty` | 传入 | 采样（键名 topP/repetition_penalty 经实测二进制有效，保留） |
| `power` | `"high"`（仅 cpu） | BackendConfig Power_High（用大核），保留 |
| `kv_max_length` | context_len（仅 cpu） | 保留（KV 上限，无害） |
| `speculative_type` lookahead 等 | 仅 cpu+lookahead | 保留 |

并加：
- **构建版本标记日志**：`MNN_LOGI("mnn_jni build: refactor-2026-07-21 (attn=8/dyn=0/mmap/reuse_kv)")`——logcat 见此串即确认新 .so 已部署（不见 = 旧 .so 仍在，需重装 APK）。
- **诊断**：`createLLM` 后读模型目录 `config.json` 前 2KB 打日志——确认模型自带配置干净。
- 更新文件顶部大段注释，反映文档对齐后的键与"为何显式钉 attention_mode=8/dynamic_option=0"。

> 注：`attention_mode=8` 是文档"默认推荐"，MNN 官方 taobao-mnn 模型 config 均不设此键（即用默认 8）且在其官方 demo 正常工作，故 8 对这些模型安全。若个别模型仍乱码，注释里留 fallback：改 `attention_mode=0`（关 flash）单行可恢复。

### 2. `app/src/main/java/.../llm/backend/MnnBackend.kt`
`initialize` 在 `nativeCreate` 前读模型目录 `config.json` + `llm_config.json` 打日志（纯 Kotlin，无需 native 重编即可看模型自带键），便于核对。

### 3. `app/src/main/java/.../download/DownloadManager.kt`
`listMnnRepoFiles` 兜底文件集：`tokenizer.txt` → **`tokenizer.mtok`**（MNN 实际 tokenizer 文件，文档 `tokenizer_file` 默认 `tokenizer.mtok`）；补 `llm.mnn.json`。正常路径走 HF API 不受影响，仅 API 失败兜底时修正。

### 4. 注释订正（`tokenizer.txt` → `tokenizer.mtok`）
`ModelPathResolver.kt`、`MnnBridge.kt`、`LocalModel.kt`、`cpp/CMakeLists.txt` 的过时注释。

### 5. 清理重编（**关键，真正修 bug 的一步**）
```
rm -rf "D:/ai/cc Programm/聊天终端安卓本地/app/.cxx"
JAVA_HOME="D:/新建文件夹/jdk-21" ./gradlew :app:assembleDebug --console=plain
```
验收：`unzip -p app/build/outputs/apk/debug/app-debug.apk lib/arm64-v8a/libmnn_jni.so | strings | grep "refactor-2026-07-21"` 命中即新 .so 已打包。

### 6. 更新 memory
`mnn-cpu-inference-tuning.md`：记录"显式钉 attention_mode=8/dynamic_option=0 覆盖旧值/模型值"为新做法，加 use_mmap/reuse_kv、OpenCL thread_num=68，并记"源码改后必须 rm .cxx 重编，否则部署旧 .so 仍 FFFF"这一坑。

## 不做
- 不改 AUTO 回退链（仍 [GPU, CPU]）：OpenCL 桩已删不会崩（memory `mnn-crash-cachepath`），thread_num=68 修正后若 GPU 可用则用、不可用回退 CPU。NPU 仍仅显式选择（SELinux 限制，memory `mnn-qnn-htp-selinux-blocked`）。
- 不加 attention_mode/use_mmap 的 UI 开关：硬编码文档安全默认，避免脚枪；lookahead 已有开关足够。
- 不动采样器链（mixed_samplers）：交模型 config 自带，只覆盖 temperature/topP。
- 不重导模型：纯运行期 set_config + 重编 .so。

## 风险与回退
- 若重编后 CPU 仍 FFFF：说明非 stale-build，而是 libMNN.so 与模型版本不匹配（memory `mnn-crash-cachepath` 候选）或 ARM82 fp16 kernel 对某模型异常 → 注释 fallback 改 `attention_mode=0` 再测；必要时用 logcat 的模型 config.json + set_config 日志进一步定位。
- OpenCL 若仍异常：用户切 MNN_CPU（8 Elite Oryon CPU 本就快，memory 称"可靠路径"）。
