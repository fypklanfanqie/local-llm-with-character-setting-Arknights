package com.rhodesisland.terminal.i18n

/**
 * 日文词典 —— 批次 11Diagnostics：推理引擎诊断层文案
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 日文译文。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 */
internal val Ja11DiagnosticsEntries: List<Pair<String, String>> = listOf(

    // ===== 推論バックエンド名と説明（InferenceBackend）=====
    "MNN · CPU 推理，兼容性最好" to "MNN · CPU 推論、互換性が最も高い",
    "MNN · OpenCL GPU 加速" to "MNN · OpenCL GPU アクセラレーション",
    "MNN · 高通 Hexagon NPU" to "MNN · Qualcomm Hexagon NPU",
    "自动（推荐）" to "自動（推奨）",
    "强制 MNN CPU" to "MNN CPU を強制",
    "强制 MNN GPU" to "MNN GPU を強制",
    "强制 MNN NPU" to "MNN NPU を強制",

    // ===== NPU 対応判定（NpuSupportDetector）=====
    "旗舰 (骁龙8 Gen2+)" to "フラッグシップ（Snapdragon 8 Gen2+）",
    "高端 (骁龙8 Gen1/8+ Gen1)" to "ハイエンド（Snapdragon 8 Gen1/8+ Gen1）",
    "中端 (骁龙7/6系)" to "ミドルレンジ（Snapdragon 7/6 系）",
    "不支持 NPU" to "NPU 非対応",
    "Android 版本过低，需要 Android 12 以上" to "Android のバージョンが古すぎます。Android 12 以上が必要です",
    "芯片等级不足以支持 NPU 推理" to "チップの階級が NPU 推論に足りません",

    // ===== 推論パフォーマンスモード（InferencePerformanceMode）=====
    "平衡" to "バランス",
    "极速" to "最高速度",

    // ===== 温度状態（ThermalMonitor）=====
    "正常" to "正常",
    "轻微发热" to "やや発熱",
    "中等发热" to "中程度の発熱",
    "严重发热" to "重大な発熱",
    "危险温度" to "危険な温度",
    "紧急温度" to "緊急温度",

    // ===== バックエンド健全性の判断理由（BackendHealthCoordinator）=====
    "崩溃黑名单（直到指纹变化或显式重置）" to
        "クラッシュのブラックリスト（フィンガープリントの変化か明示的なリセットまで）",
    "冷却期已过，重新探测验证" to "クールダウンが明けたため、再検出して検証します",
    "冷却中（跳过 OpenCL 尝试）" to "クールダウン中（OpenCL の試行をスキップ）",

)
