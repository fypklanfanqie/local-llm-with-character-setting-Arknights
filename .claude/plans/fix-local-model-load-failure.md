# 修复：部分机型芯片本地模型「所有后端均加载失败」

## 根因
- 用户看到「所有后端均初始化失败」=> `MnnBridge.nativeAvailable` 为 true（库已加载，过了 LocalChatProvider:77 预检），但链中所有后端 `nativeCreate` 返回 0。AUTO 链 = [GPU, CPU]，**CPU 恒在链中**，故根因是 **CPU 的 `Llm::load()` 在某些芯片上失败**。
- 最可能：激进配置 `precision:"low"`(fp16/ARM82) / `memory:"low"`(运行时量化) 在不支持该指令集的芯片上 load 失败。
- 诊断黑洞：`InferenceBackend.lastErrorMessage`（InferenceBackend.kt:40，文档明写「解决『所有后端均初始化失败』无诊断信息」）**从未赋值**；`BackendManager.generate()` 在 `ensureLoaded` 返回 false 时不更新 `lastError`（只 catch 里设），故 `lastError` 保持 null -> 抛空洞文案；native 失败原因（`Llm::load 异常: e.what()` / `createLLM 返回 null`）只在 logcat。

## 改动（4 处）

### A. `app/src/main/cpp/mnn_jni.cpp` — 暴露 native 失败原因 + CPU 安全配置重试
1. 新增文件级 `static std::string g_last_load_error;`，在 `nativeCreate` 每个失败点写入具体原因：
   - `createLLM` 返回 null -> `"Llm::createLLM 返回 null（config.json 解析失败）"`
   - `load()` 抛异常 -> `"Llm::load 异常: <e.what()>"`（catch 块内）
   - `load()` 返回 false -> `"Llm::load() 失败 (backend=<X>)"`（区分 opencl/qnn/cpu）
2. **CPU 安全配置重试**：`load()` 失败且 `backend_str=="cpu"` 时，用「最小功能配置」重试一次 `load()`——保留 `backend_type/cache_path/use_mmap/thread_num/temperature/topP/repetition_penalty/mixed_samplers(penalty 修复不可丢)`，**丢弃** `precision/memory/attention_mode/dynamic_option/power` 等激进性能键（让 MNN/模型用安全默认）。成功则打 `MNN_LOGI("CPU 安全配置重试成功")` 并继续；失败则 `g_last_load_error` 记为安全配置的失败原因。
   - 理由：fp16/运行时量化在不支持芯片上 load 失败；丢激进键回退模型默认是最稳的「让它能跑」兜底。`use_mmap:true` 仍控内存。
   - 仅 CPU 重试（GPU/NPU 失败是 OpenCL/QNN 可用性问题，换配置无益，且失败本就回退 CPU）。
3. 新增 JNI `Java_..._nativeGetLastError` 返回 `g_last_load_error`（jstring）。load 成功时清空 `g_last_load_error=""`。

### B. `MnnBridge.kt` — 新增 external 声明
- `external fun nativeGetLastError(): String`

### C. `MnnBackend.kt` — `initialize()` 真正填充 `lastErrorMessage`
在 `initialize()` 各失败分支写入 `lastErrorMessage`，成功置 null：
- native 不可用 -> `"libMNN/libmnn_jni 未加载"`
- config 不存在 -> `"config.json 不存在: $modelPath"`
- nativeCreate 抛异常 -> `"nativeCreate 异常: ${e.message}"`
- nativeCreate 返回 0 -> `"模型加载失败 (backend=${mode.mnnBackendType}): ${bridge.nativeGetLastError()}"`
- 成功 -> `lastErrorMessage = null`
- 方法开头先清空旧值，避免跨调用残留。

### D. `BackendManager.kt` — `generate()` 收集各后端原因 + 详细报错
- 新增 `val failureReasons = mutableListOf<String>()`。
- `ensureLoaded` 返回 false 时（不仅 catch）：读 `backendFor(type).lastErrorMessage`，追加 `"${type.displayName}: <reason>"` 到 failureReasons；catch 分支同样追加。
- 链末全失败：`throw IllegalStateException("本地模型加载失败（所有后端均失败）。${failureReasons.joinToString("；")}")`，替换原空洞 `"所有后端均初始化失败"`。并 `Log.e` 完整明细。

## 验证
- `JAVA_HOME='D:/新建文件夹/jdk-21'` 原生重编 mnn_jni（rm .cxx 规避缓存假象）。
- `compileDebugKotlin --rerun-tasks --no-build-cache` 验 Kotlin。
- 故障机复现：现 Snackbar 会显示具体失败后端+原因（如「MNN CPU: Llm::load() 失败 (backend=cpu)」），logcat 见 `MNN_LOGE` 原始 MNN 错误 + 是否触发安全配置重试。据此可确认根因并迭代。
