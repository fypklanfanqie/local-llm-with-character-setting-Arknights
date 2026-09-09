package com.rhodesisland.terminal.i18n

/**
 * 日文词典 —— 批次 03GuideB：使用指南测验（GuideQuiz）
 *
 * key = 中文原文（与界面 t("...") 里的字符串一字不差），value = 日文译文。
 * 带占位符的模板用 {0} / {1}，下标与中文模板一致。缺词由 L10n 回退中文。
 */
internal val Ja03GuideBEntries: List<Pair<String, String>> = listOf(

    // ===== クイズ画面 =====
    "🏆 嘉豪认证考试" to "🏆 嘉豪（ジァハオ）認定試験",
    "第 {0} / {1} 题" to "第 {0} / {1} 問",
    "选定后立即锁定，无法修改" to "選択するとすぐに確定され、変更できません",
    "🎉 恭喜你" to "🎉 おめでとうございます",
    "恭喜你，你确实有几把刷子，凭此弹窗的截图可以找我，有奖励！" to
        "おめでとうございます、なかなかやりますね。このダイアログのスクリーンショットを持って私に連絡してください。報酬があります！",
    "收下奖励" to "報酬を受け取る",
    "💔 很遗憾" to "💔 残念",
    "很遗憾嘉豪你没有通过测试，本软件不再为你提供任何服务" to
        "残念ですが嘉豪さん、テストに合格しませんでした。このアプリは今後あなたにサービスを提供しません",
    "我知道了" to "わかりました",

    // ===== 第 1 問：KV cache の見積もり =====
    "本应用估算本地模型运行内存时，KV cache 部分的计算方式是？" to
        "このアプリがローカルモデルの実行メモリを見積もるとき、KV cache 部分の計算方法は？",
    "参数量(十亿) × 每参数字节数 × 批大小" to
        "パラメータ数（十億）× 1 パラメータあたりのバイト数 × バッチサイズ",
    "fp16(2字节) × 2 × 层数(layerCount) × 上下文长度 × KV头数 × 头维度" to
        "fp16（2 バイト）× 2 × レイヤ数（layerCount）× コンテキスト長 × KV ヘッド数 × ヘッド次元",
    "上下文长度 × hidden_size × 4字节 × 注意力头总数" to
        "コンテキスト長 × hidden_size × 4 バイト × アテンションヘッド総数",
    "层数 × 上下文长度 × 词表大小 × 2字节" to
        "レイヤ数 × コンテキスト長 × 語彙サイズ × 2 バイト",

    // ===== 第 2 問：Lookahead 投機的デコード =====
    "Lookahead 投机解码在什么情况下才真正参与推理？" to
        "Lookahead 投機的デコードが実際に推論に使われるのはどのような場合ですか？",
    "仅当解析出的组合命中 CPU_OPTIMIZED 变体的认证组合（profile resolver 门禁通过）" to
        "解決された組み合わせが CPU_OPTIMIZED バリアントの認証済み組み合わせに一致する場合のみ（profile resolver のゲート通過）",
    "打开后端设为 OpenCL GPU 并选最高速度性能模式即生效" to
        "バックエンドを OpenCL GPU にして最高速度パフォーマンスモードを選べば有効になる",
    "QNN NPU 后端的专属加速能力" to "QNN NPU バックエンド専用の加速機能",
    "AUTO 后端在任何设备上都会自动启用" to
        "AUTO バックエンドはどの端末でも自動的に有効になる",

    // ===== 第 3 問：Anthropic プロトコルの判定 =====
    "应用判定某个自定义接口走 Anthropic /v1/messages + x-api-key 协议的依据是？" to
        "アプリがあるカスタム API を Anthropic /v1/messages + x-api-key プロトコルと判定する根拠は？",
    "请求头中已经带了 x-api-key" to "リクエストヘッダにすでに x-api-key が含まれている",
    "Base URL 的域名字段精确等于 api.anthropic.com" to
        "Base URL のドメインが api.anthropic.com と完全一致する",
    "所填模型名以 claude 开头" to "入力したモデル名が claude で始まる",
    "URL 包含 anthropic/claude 字样，或路径以 /v1/messages 结尾" to
        "URL に anthropic/claude が含まれる、またはパスが /v1/messages で終わる",

    // ===== 第 4 問：思考過程のフィールド =====
    "云端 SSE 直连时，DeepSeek / Qwen 系模型的思考过程取自哪个字段？" to
        "クラウド SSE 直結時、DeepSeek / Qwen 系モデルの思考過程はどのフィールドから取得しますか？",
    "delta.content 里原始 <think> 标签的解析结果" to
        "delta.content 内の生の <think> タグを解析した結果",
    "choices[0].delta.reasoning_content（部分端点为 reasoning），随后被包装成 <think> 展示" to
        "choices[0].delta.reasoning_content（一部のエンドポイントでは reasoning）。その後 <think> としてラップして表示",
    "message.reasoning_summary" to "message.reasoning_summary",
    "usage 里的 completion_tokens_details" to "usage 内の completion_tokens_details",

    // ===== 第 5 問：無料チャンネルのキー保管場所 =====
    "「免费对话」通道的真实 API Key 实际存放在哪里？" to
        "「無料チャット」チャンネルの実際の API キーはどこに保存されていますか？",
    "编译期写入 APK 的 BuildConfig 常量里" to
        "コンパイル時に APK へ書き込まれる BuildConfig 定数",
    "随应用内置的 assets 配置文件分发" to "アプリに同梱される assets の設定ファイル",
    "Cloudflare Worker 的加密环境变量中，对话经 Worker 代理由服务端注入" to
        "Cloudflare Worker の暗号化環境変数。会話は Worker 経由でサーバー側から注入される",
    "Room 数据库的一张加密表里，首次联网时下载" to
        "Room データベースの暗号化テーブル。初回通信時にダウンロードされる",

    // ===== 第 6 問：挨拶配信の条件（否定形）=====
    "角色主动问候真正投递的必要条件，不包括以下哪项？" to
        "キャラクターからの自発的な挨拶が実際に配信される条件に含まれないものはどれですか？",
    "15 分钟周期 Work 到期且 next_fire_at 门控通过" to
        "15 分周期の Work が発火し、next_fire_at のゲートを通過する",
    "处于白天时段（08:00–23:00）" to "日中帯（08:00〜23:00）である",
    "当日配额未耗尽（默认每天 3 条，可调 1–10）" to
        "当日の割り当てが残っている（既定は 1 日 3 件、1〜10 で調整可）",
    "设备必须连接 Wi-Fi 网络" to "端末が Wi-Fi に接続している必要がある",

    // ===== 第 7 問：グループチャットの返信人数上限 =====
    "群聊中用户发出一条消息，最多由几名成员回复？" to
        "グループチャットで、ユーザーの 1 件のメッセージに最大何名のメンバーが返信できますか？",
    "2 名（与自动互聊每轮人数相同）" to
        "2 名（自動会話の 1 ラウンドあたりの人数と同じ）",
    "3 名（群成员数的三分之一）" to "3 名（グループメンバー数の 1/3）",
    "4 名（@ 指定的成员必答并占用名额）" to
        "4 名（@ で指定されたメンバーは必ず答え、枠を消費する）",
    "所有群成员依次作答" to "すべてのメンバーが順番に答える",

    // ===== 第 8 問：Seedance 中継サイトの API =====
    "Seedance 视频生成在「中转站」模式下，应用自动调用的是哪组接口？" to
        "Seedance の動画生成が「中継サイト」モードのとき、アプリが自動的に呼ぶ API はどれですか？",
    "/v1/media/generate 与 /v1/media/status" to "/v1/media/generate と /v1/media/status",
    "官方 /api/v3 下的任务创建/查询接口" to "公式 /api/v3 のタスク作成・照会 API",
    "/v1/video/create 与 /v1/video/poll" to "/v1/video/create と /v1/video/poll",
    "复用 /chat/completions 并附加 video 模态参数" to
        "/chat/completions を再利用し、video モダリティパラメータを付加する",

    // ===== 第 9 問：ロアブックの「青ランプ」=====
    "世界书条目处于「蓝灯」状态意味着什么？" to
        "ロアブック項目が「青ランプ」状態とはどういう意味ですか？",
    "条目已被禁用但仍占用 token 预算" to
        "項目は無効だがトークン予算を消費し続ける",
    "只有主关键词和次级关键词同时命中才注入" to
        "メインキーワードとサブキーワードが同時に一致したときのみ注入される",
    "常驻条目：无需关键词，每次请求都注入 system prompt" to
        "常駐項目：キーワード不要で、毎回のリクエストで system prompt に注入される",
    "仅在递归扫描第二遍时才会被注入" to
        "再帰スキャンの 2 周目でのみ注入される",

    // ===== 第 10 問：DataStore 破損時の動作 =====
    "偏好设置数据文件损坏（如国产 ROM 半写）时，应用的行为是？" to
        "設定データファイルが破損したとき（国産 ROM の書き込み途中など）、アプリの動作は？",
    "弹窗引导用户导出日志手动修复" to
        "ダイアログでログの書き出しと手動修復を案内する",
    "逐段尝试恢复仍可读的键值对" to
        "まだ読めるキーと値のペアを部分的に復元しようとする",
    "回退读取 Room 数据库里的一份备份" to
        "Room データベース内のバックアップを読み込む",
    "经 ReplaceFileCorruptionHandler 删除损坏文件并以默认值重建" to
        "ReplaceFileCorruptionHandler で破損ファイルを削除し、既定値で再作成する",

)
