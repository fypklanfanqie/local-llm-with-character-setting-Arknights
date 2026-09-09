package com.rhodesisland.terminal.i18n

/**
 * 英文词典 —— 批次 11Diagnostics：推理引擎诊断层文案
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 英文译文。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 *
 * 说明：本批次的字符串定义在 llm/（数据层枚举 displayName / 诊断原因），
 * 按 playbook 2.6 在渲染处包 t(...) / L10nRuntime.t(...)。
 * LLM 提示词与异常匹配比较值不在本批次、不翻译。
 */
internal val En11DiagnosticsEntries: List<Pair<String, String>> = listOf(

    // ===== 推理后端名称与说明（InferenceBackend）=====
    "MNN · CPU 推理，兼容性最好" to "MNN · CPU inference, best compatibility",
    "MNN · OpenCL GPU 加速" to "MNN · OpenCL GPU acceleration",
    "MNN · 高通 Hexagon NPU" to "MNN · Qualcomm Hexagon NPU",
    "自动（推荐）" to "Auto (recommended)",
    "强制 MNN CPU" to "Force MNN CPU",
    "强制 MNN GPU" to "Force MNN GPU",
    "强制 MNN NPU" to "Force MNN NPU",

    // ===== NPU 能力探测（NpuSupportDetector）=====
    "旗舰 (骁龙8 Gen2+)" to "Flagship (Snapdragon 8 Gen2+)",
    "高端 (骁龙8 Gen1/8+ Gen1)" to "High-end (Snapdragon 8 Gen1/8+ Gen1)",
    "中端 (骁龙7/6系)" to "Mid-range (Snapdragon 7/6 series)",
    "不支持 NPU" to "NPU not supported",
    "Android 版本过低，需要 Android 12 以上" to "Android version too old; Android 12 or newer is required",
    "芯片等级不足以支持 NPU 推理" to "This chip tier is not powerful enough for NPU inference",

    // ===== 推理性能模式（InferencePerformanceMode）=====
    "平衡" to "Balanced",
    "极速" to "Maximum speed",

    // ===== 温度状态（ThermalMonitor）=====
    "正常" to "Normal",
    "轻微发热" to "Slightly warm",
    "中等发热" to "Moderately warm",
    "严重发热" to "Severely hot",
    "危险温度" to "Critical temperature",
    "紧急温度" to "Emergency temperature",

    // ===== 后端健康决策理由（BackendHealthCoordinator）=====
    "崩溃黑名单（直到指纹变化或显式重置）" to
        "Crash blacklist (until the fingerprint changes or is reset explicitly)",
    "冷却期已过，重新探测验证" to "Cooldown elapsed; probing again to verify",
    "冷却中（跳过 OpenCL 尝试）" to "Cooling down (skipping the OpenCL attempt)",

    // ===== 基准/认证否决原因（静态文案；含插值的仍在数据层保持中文）=====
    "可靠性未执行（totalRounds=0）" to "Reliability run was not executed (totalRounds=0)",
    "设备过热，可靠性基准未执行" to "Device overheated; the reliability benchmark was not run",
    "候选正确性校验未通过（UTF-8/EOS/复读/KV 失配）" to
        "Candidate failed the correctness check (UTF-8/EOS/repetition/KV mismatch)",
    "正确性校验未通过（UTF-8/EOS/复读/KV 失配）" to
        "Correctness check failed (UTF-8/EOS/repetition/KV mismatch)",
    "热启动样本无效，需冷启重测" to "Warm-start samples are invalid; re-measure from a cold start",
    "候选 native 身份缺失（mnnCommit/nativeBuildId 空白）" to
        "Candidate is missing native identity (mnnCommit/nativeBuildId empty)",
    "基线 native 身份缺失" to "Baseline is missing native identity",
    "候选身份与基线相同（mnnCommit/nativeBuildId 未变化，无升级价值）" to
        "Candidate identity matches the baseline (mnnCommit/nativeBuildId unchanged; nothing to promote)",
    "GPU 候选实际 GPU 样本数不足（全回退不可作为 GPU 收益证据）" to
        "The GPU candidate has too few actual GPU samples (all-fallback runs are not evidence of a GPU gain)",
    "prefill 证据缺失（需完整 prefill 样本的 prefillTps 与 TTFT；KV 复用污染样本不计）" to
        "Prefill evidence is missing (needs prefillTps and TTFT from full prefill samples; KV-reuse-contaminated samples do not count)",

    // ===== 基准场景 / 四象限名称 =====
    "冷启动加载" to "Cold-start load",
    "短首字延迟" to "Short TTFT",
    "长前缀填充" to "Long-prefix prefill",
    "固定长度解码" to "Fixed-length decode",
    "第二轮 KV 复用" to "Second-pass KV reuse",
    "空回答检查" to "Empty-reply check",
    "CPU 思考关" to "CPU, thinking off",
    "CPU 思考开" to "CPU, thinking on",
    "GPU 思考关" to "GPU, thinking off",
    "GPU 思考开" to "GPU, thinking on",

    // ===== 思考档位（LocalThinkingLevel；「中」与设置页尺寸档位冲突，故不收录）=====
    "自动" to "Auto",
    "短" to "Short",
    "长" to "Long",

)
