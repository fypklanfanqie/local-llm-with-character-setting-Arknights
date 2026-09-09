package com.rhodesisland.terminal.i18n

/**
 * 批次 09SystemB —— 数据层与远端错误文案
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 英文译文。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 */
internal val En09SystemBEntries: List<Pair<String, String>> = listOf(
    // ===== 朋友圈生图（MomentImageGenClient / MediaTaskApi）=====
    "生图 API 未配置" to "Image generation API is not configured",
    "生图失败" to "Image generation failed",
    "提交失败" to "Submission failed",
    "任务 {0}：{1}" to "Task {0}: {1}",
    "任务 {0}：任务完成但未返回结果链接" to "Task {0}: task finished but returned no result URL",
    "任务 {0}：结果类型为 {1}，预期 image" to "Task {0}: result type is {1}, expected image",
    "任务 {0}：轮询超时（{1}s）仍未完成" to "Task {0}: not finished after the polling timeout ({1}s)",
    "无任务成功" to "no task succeeded",
    "生图任务失败：" to "Image generation tasks failed: ",
    "图片下载/解码失败" to "Image download/decode failed",
    "查询失败 HTTP {0}: {1}" to "Query failed HTTP {0}: {1}",
    "查询异常: {0}" to "Query error: {0}",
    "生图回复中未找到图片（回复开头：{0}）" to "No image found in the image generation reply (reply starts with: {0})",
    "生图请求失败 HTTP {0}: {1}（{2}）" to "Image generation request failed HTTP {0}: {1} ({2})",
    "生图请求异常: {0}（{1}）" to "Image generation request error: {0} ({1})",
    "提交响应不是 JSON 对象: {0}" to "Submit response is not a JSON object: {0}",
    "提交被拒（code={0}）：{1}" to "Submission rejected (code={0}): {1}",
    "提交成功但未返回 task_id: {0}" to "Submission succeeded but returned no task_id: {0}",
    "状态响应不是 JSON: {0}" to "Status response is not JSON: {0}",
    "网关瞬时故障" to "Transient gateway failure",
    "任务失败（state=failed，平台已自动退款）" to "Task failed (state=failed, the platform has refunded automatically)",

    // ===== 直连 LLM 异常（DirectLlmException）=====
    "云端请求失败" to "Cloud request failed",
    "网络连接失败" to "Network connection failed",
    "云端返回为空" to "Cloud response was empty",

    // ===== Seedance（SeedanceClient：探测结论 + 网络错误分类）=====
    "网络错误，无法确认任务状态：{0}" to "Network error, task status cannot be confirmed: {0}",
    "接口正常，服务地址可用" to "Endpoint is normal, the service address is usable",
    "接口可达，但 API Key 无效或未授权" to "Endpoint reachable, but the API key is invalid or unauthorized",
    "接口可达，但服务暂时繁忙，请稍后重试" to "Endpoint reachable, but the service is temporarily busy. Please retry later",
    "接口可达，路径正确（探测任务返回预期结果）" to "Endpoint reachable and the path is correct (the probe task returned the expected result)",
    "中转站地址请填写完整「创建任务」接口（如 https://api.lk888.ai/v1/media/generate），或直接填该站点主机" to
        "For a relay station, enter the full Create task endpoint (e.g. https://api.lk888.ai/v1/media/generate), or just the site host",
    "官方地址填 base（含 /api/v3）；中转站请粘贴完整的「创建任务」接口地址（如 https://xxx/v1/media/generate），不要只填主机或 /v1" to
        "For the official address enter the base (including /api/v3); for a relay station paste the full Create task endpoint (e.g. https://xxx/v1/media/generate), not just the host or /v1",
    "接口可达，但路径可能不正确：{0}" to "Endpoint reachable, but the path may be incorrect: {0}",
    "接口可达，但返回异常，请检查服务地址" to "Endpoint reachable, but the response is abnormal. Please check the service address",
    "无法连接服务，请检查地址与网络" to "Cannot connect to the service. Please check the address and network",
    "连接或读取超时" to "Connection or read timeout",
    "无法解析服务器地址（DNS 失败或域名被墙）" to "Cannot resolve the server address (DNS failure or the domain is blocked)",
    "无法连接到服务器（连接被拒绝或端口不通）" to "Cannot connect to the server (connection refused or port unreachable)",
    "安全连接失败（TLS/证书问题）" to "Secure connection failed (TLS/certificate problem)",
    "网络异常" to "Network error",

    // ===== TTS / 模型标签（Config.kt，渲染处已 t() 包装）=====
    "手机系统语音" to "Phone system voice",
    "云端（火山豆包）" to "Cloud (Volcano Doubao)",
    "温柔女声" to "Gentle female",
    "元气少女" to "Energetic girl",
    "沉稳男声" to "Steady male",
    "清爽少年" to "Bright youth",
    "童声" to "Child voice",
    "{0} Resource ID 已保存，但缺少音色 ID" to "{0} Resource ID is saved, but the voice ID is missing",
    "请填写火山引擎 API Key" to "Please enter the Volcano Engine API key",
    "云端 AI" to "Cloud AI",
    "本地 AI" to "Local AI",

    // ===== 本地模型说明（LocalModel.kt，数据层原文，渲染处包 t()）=====
    "MNN 优化版，专为移动端或嵌入式设备设计，体积小、效率高。" to
        "MNN-optimized build designed for mobile or embedded devices: small footprint and high efficiency.",
    "MNN 优化版，适合中端设备运行。" to "MNN-optimized build suitable for mid-range devices.",
    "MNN 优化版，兼顾性能与移动端适配。" to "MNN-optimized build balancing performance and mobile adaptation.",
    "MNN 优化版，适合高性能移动设备或边缘计算场景。" to
        "MNN-optimized build for high-performance mobile devices or edge computing.",
    "超大参数 MNN 优化模型，可能采用稀疏化或量化技术，适用于高端设备或服务器部署。" to
        "Very large MNN-optimized model that may use sparsification or quantization; suitable for high-end devices or server deployment.",

    // ===== 文档 / 图片提取（DocumentRepository）=====
    "文件过大，无法上传" to "The file is too large to upload",
    "暂不支持 .{0} 文档直连解析，请转为 PDF 后上传" to
        ".{0} documents are not supported for direct parsing. Please convert to PDF and upload",
    "请先在设置页配置 API Key" to "Please configure the API key in Settings first",
    "PDF 提取需多模态模型，请在设置切换（如 GPT-4o / Qwen-VL）" to
        "PDF extraction requires a multimodal model. Please switch in Settings (e.g. GPT-4o / Qwen-VL)",
    "PDF 文件过大" to "The PDF file is too large",
    "无法读取 PDF 文件" to "Cannot read the PDF file",
    "PDF 无可渲染页面" to "The PDF has no renderable page",
    "[仅前 {0} 页已提取]" to "[Only the first {0} pages were extracted]",
    "图片识别需多模态模型，请在设置切换（如 GPT-4o / Qwen-VL）" to
        "Image recognition requires a multimodal model. Please switch in Settings (e.g. GPT-4o / Qwen-VL)",
    "无法读取图片" to "Cannot read the image",

    // ===== 小说仓库（NovelRepository）=====
    "故事名不能为空" to "Story name cannot be empty",
    // 与批次 08Novel 同译文（该 key 已在 En08Novel.kt 收录，重复即一致）
    "第 {0} 话" to "Episode {0}",
    "本话已达 {0} 行上限" to "This chapter has reached the {0}-line limit",

    // ===== BGM 分类（AssetRepository.BgmTrack.ep）=====
    "系统" to "System",
    "其他" to "Other",

    // ===== 世界书导入（LorebookJson）=====
    "不是有效的 JSON 文件" to "Not a valid JSON file",
    "未找到 entries 字段，不是世界书或角色卡 JSON" to
        "No entries field found; this is not a lorebook or character card JSON",
    "世界书没有任何条目" to "The lorebook has no entries",
    "没有可识别的条目（content 均为空）" to "No recognizable entries (all content is empty)",
    "条目数超过上限 {0}，已截断导入前 {0} 条" to
        "Entry count exceeds the limit of {0}; only the first {0} entries were imported",
)
