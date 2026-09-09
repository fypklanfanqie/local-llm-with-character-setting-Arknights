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

    // ===== ベンチマーク／認証の否決理由（静的な文言のみ）=====
    "可靠性未执行（totalRounds=0）" to "信頼性テストは未実行（totalRounds=0）",
    "设备过热，可靠性基准未执行" to "端末が高温のため、信頼性ベンチマークは実行されませんでした",
    "候选正确性校验未通过（UTF-8/EOS/复读/KV 失配）" to
        "候補は正確性チェックに合格しませんでした（UTF-8/EOS/繰り返し/KV の不一致）",
    "正确性校验未通过（UTF-8/EOS/复读/KV 失配）" to
        "正確性チェックに合格しませんでした（UTF-8/EOS/繰り返し/KV の不一致）",
    "热启动样本无效，需冷启重测" to "ウォームスタートのサンプルが無効です。コールドスタートから再測定してください",
    "候选 native 身份缺失（mnnCommit/nativeBuildId 空白）" to
        "候補のネイティブ識別情報がありません（mnnCommit/nativeBuildId が空）",
    "基线 native 身份缺失" to "ベースラインのネイティブ識別情報がありません",
    "候选身份与基线相同（mnnCommit/nativeBuildId 未变化，无升级价值）" to
        "候補の識別情報がベースラインと同じです（mnnCommit/nativeBuildId が未変更で昇格の価値なし）",
    "GPU 候选实际 GPU 样本数不足（全回退不可作为 GPU 收益证据）" to
        "GPU 候補の実際の GPU サンプル数が不足しています（全回フォールバックは GPU 効果の証拠になりません）",
    "prefill 证据缺失（需完整 prefill 样本的 prefillTps 与 TTFT；KV 复用污染样本不计）" to
        "prefill の証拠がありません（完全な prefill サンプルの prefillTps と TTFT が必要。KV 再利用で汚染されたサンプルは無効）",

    // ===== ベンチマーク場面 / 四象限の名称 =====
    "冷启动加载" to "コールドスタート読み込み",
    "短首字延迟" to "短い TTFT",
    "长前缀填充" to "長いプレフィックスの prefill",
    "固定长度解码" to "固定長デコード",
    "第二轮 KV 复用" to "2 周目の KV 再利用",
    "空回答检查" to "空応答チェック",
    "CPU 思考关" to "CPU・思考オフ",
    "CPU 思考开" to "CPU・思考オン",
    "GPU 思考关" to "GPU・思考オフ",
    "GPU 思考开" to "GPU・思考オン",

    // ===== 思考レベル（LocalThinkingLevel。「中」は設定画面のサイズ項目と衝突するため未収録）=====
    "自动" to "自動",
    "短" to "短",
    "长" to "長",

    // ===== 数値を含む否決理由（データ層は {0} テンプレート + L10nRuntime.format）=====
    "样本数不足（需 ≥{0}，候选={1}，基线={2}）" to
        "サンプル数が不足（{0} 以上が必要。候補={1}、ベースライン={2}）",
    "decode 提升不足 10%（候选={0} vs 基线={1}）" to
        "decode の改善が 10% 未満（候補={0} vs ベースライン={1}）",
    "decode 劣化超 30%（候选={0} vs 基线={1}）" to
        "decode の劣化が 30% 超（候補={0} vs ベースライン={1}）",
    "TTFT 劣化超 30%（候选={0} vs 基线={1}）" to
        "TTFT の劣化が 30% 超（候補={0} vs ベースライン={1}）",
    "峰值 PSS 劣化超 30%（候选={0} vs 基线={1}）" to
        "ピーク PSS の劣化が 30% 超（候補={0} vs ベースライン={1}）",
    "KV 复用率回归（候选={0} vs 基线={1}）" to
        "KV 再利用率の後退（候補={0} vs ベースライン={1}）",
    "候选空响应率过高（{0} > {1}）" to "候補の空応答率が高すぎます（{0} > {1}）",
    "GPU 候选混入非 GPU 样本（MNN_GPU={0} / 总样本={1}，实际后端={2}）" to
        "GPU 候補に非 GPU サンプルが混入（MNN_GPU={0} / 総サンプル={1}、実際のバックエンド={2}）",
    "prefill 提升不足（prefill {0} vs {1} tps；TTFT {2} vs {3} ms）" to
        "prefill の改善が不足（prefill {0} vs {1} tps；TTFT {2} vs {3} ms）",
    "可靠性未满分（{0} < 1.0，含空响应/乱码/复读轮）" to
        "信頼性が満点ではありません（{0} < 1.0。空応答・文字化け・繰り返しのラウンドを含む）",
    "出现后端回退（{0} 轮）" to "バックエンドのフォールバックが発生（{0} ラウンド）",

)
