# 修复：确保本地小模型对中国用户可下载

## 背景（本机已实测验证）
本机在国内：`huggingface.co` 被墙（curl HTTP 000/超时），`www.modelscope.cn` 与 `hf-mirror.com` 可访问。下载代码 `DownloadManager` 的镜像回退顺序为 ModelScope → hf-mirror → HuggingFace（被墙）。

排查发现两个独立问题，导致中国用户无法成功/完整下载清单内模型：

### 问题 1：4 个模型根本不存在（任何用户都下不了）
`DEFAULT_MNN_MODELS`（`LocalModel.kt`）中 4 个 `Qwen3.5-{0.8B,2B,4B,27B}-Claude-4.6-Opus-Reasoning-Dist`，在 HuggingFace `taobao-mnn/`、ModelScope `MNN/`、hf-mirror `taobao-mnn/` **三源全部 404**。hf-mirror 上 `taobao-mnn` 命名空间完整模型列表里也没有任何 Claude-Dist。这 4 个是占位/虚构条目。

### 问题 2：文件列表用被墙的 HF API（影响全部 9 个真实模型）
`DownloadManager.listMnnRepoFiles()` 只查询 `huggingface.co/api/models/<repo>`，国内被墙 → 两个 repo 各超时 30s 后回退到**硬编码文件列表**：
```kotlin
listOf("config.json","llm_config.json","llm.mnn","llm.mnn.weight",
       "embeddings_bf16.bin","tokenizer.txt","splits_info.json")
```
真实文件列表（以 `MNN/Qwen3.5-2B-MNN` 为例，ModelScope API 实测）含 `visual.mnn`、`visual.mnn.weight`(186MB)、`llm.mnn.json`、`configuration.json`、`export_args.json`，硬编码全部漏掉。更严重的是文件名对不上：如 `DeepSeek-R1-1.5B-Qwen-MNN` 真实用 `tokenizer.mtok` + `embeddings_int4.bin`，硬编码写的是 `tokenizer.txt` + `embeddings_bf16.bin` → 关键文件漏下。漏 `visual.*`/权重会导致 MNN `PipelineModule::load` 原生 SIGSEGV（见 memory `mnn-crash-cachepath` 同类崩溃）。

已验证：ModelScope 文件列表 API 国内可用 `https://www.modelscope.cn/api/v1/models/<ns>/<id>/repo/files` → `Data.Files[].{Path,Type,Size}`；ModelScope 与 hf-mirror 对大权重 `llm.mnn.weight` 均支持 Range(206) 断点续传（含 302 重定向后）。

## 方案（3 处改动）

### 改动 1 — `DownloadManager.listMnnRepoFiles()`：用国内可访问 API 拉真实文件列表
改为按国内可访问性依次尝试（任一命中即返回）：
1. **ModelScope API**（命中 `MNN/<id>`）— 国内主源，文件最全
2. **hf-mirror API**（命中 `taobao-mnn/<id>`）— 国内备源
3. **HuggingFace API** — 非国内用户兜底（保留，不破坏海外）
4. 硬编码核心文件集 — 全部不可达时最后兜底（保留现有列表）

- 新增 ModelScope 响应数据类：`MsRepoFiles{Data:{Files:[{Path,Type,Size}]}}`（用 `@SerialName` 映射大写 JSON 键）。
- 解析时过滤 `Type=="tree"`（目录）、`SKIP_FILES`、`.git*`，与现有 HF 解析一致。
- 抽出 `tryModelscopeFileList(repo)` 与 `tryHfFileList(apiUrl)` 两个私有函数，复用现有 `HfModelInfo` 解析。
- 需新增 import `kotlinx.serialization.SerialName`。

### 改动 2 — `DownloadManager.buildMnnFileUrls()`：调整镜像优先级为 source-major
当前是 repo-major（每个 repo 内 ModelScope→hf-mirror→HF），导致首个 URL 永远是 `modelscope.cn/models/taobao-mnn/<id>`（ModelScope 上无此 namespace，必 404）。
改为 source-major：先遍历两 repo 拼 ModelScope URL，再 hf-mirror，再 HF。使可靠的 `modelscope.cn/models/MNN/<id>` 命中靠前，国内用户完全不触碰被墙的 HF，减少无效 404。

### 改动 3 — `LocalModel.kt DEFAULT_MNN_MODELS`：替换 4 个不存在模型
删除 4 个 `qwen35(...Claude-4.6-Opus-Reasoning-Dist...)` 行，替换为 4 个**已验证国内双源可下**的真实 MNN 推理模型（精确字节数取自 ModelScope API 实测，复用现有 `mnn()` helper，`sizeGb*1e9` 精确还原）：

| 模型 ID | vendor | tags | 字节数 |
|---|---|---|---|
| `DeepSeek-R1-1.5B-Qwen-MNN` | DeepSeek | Think | 1,020,644,886 |
| `Qwen3-4B-MNN` | Qwen | Think | 2,713,766,729 |
| `DeepSeek-R1-7B-Qwen-MNN` | DeepSeek | Think | 4,647,473,365 |
| `DeepSeek-R1-0528-Qwen3-8B-MNN` | DeepSeek | Think | 5,507,637,931 |

- 同步更新第 110-112 行注释（去掉「Claude-4.6-Opus 蒸馏推理版」描述，改为说明推理模型为独立真实条目）。
- 保留 5 个 `Qwen3.5-*-MNN` 与 4 个 `mnn()`（Llama/gemma/SmolLM）不变。

## 不改动
- 现有 9 个可用模型的 `size`：已验证近似值含 visual 文件，与真实合计吻合（如 `Qwen3.5-2B-MNN` 标 1.29 GiB ≈ 真实合计 1.387GB）。
- `downloadMnnFile` 的 404 跳过、断点续传、call.cancel 中断逻辑（已正确）。
- `SKIP_FILES` 集合（已正确过滤 `.gitattributes`/`README` 等）。

## 验证
1. `./gradlew compileDebugKotlin --rerun-tasks --no-build-cache` 编译通过（按 memory `gradle-kotlin-verify`，避开 build cache 假象）。
2. 逻辑核对：改动后 `listMnnRepoFiles` 对 `Qwen3.5-2B-MNN` 返回含 `visual.mnn`/`llm.mnn.json` 的 12 文件列表；对 `DeepSeek-R1-1.5B-Qwen-MNN` 返回含 `tokenizer.mtok`/`embeddings_int4.bin` 的真实列表。
3. 运行时（需设备/模拟器，本轮不做）：下载 `Qwen3.5-2B-MNN` 与 `DeepSeek-R1-1.5B-Qwen-MNN` 完整、切换本地 AI 可加载流式对话。

## 风险
- ModelScope API 偶发限流/变更 → 已有 hf-mirror → HF → 硬编码三级回退兜底。
- `mnn()` helper 的 `name` 自动派生（如 `DeepSeek R1 1.5B Qwen`）较朴素，可接受，后续可改。
