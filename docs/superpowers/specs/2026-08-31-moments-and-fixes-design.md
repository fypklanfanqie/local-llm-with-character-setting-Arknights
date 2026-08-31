# 设计文档：v3.1 细节优化 + 朋友圈功能

日期：2026-08-31　状态：已获用户批准

## 背景

五项需求：(1) 群聊 @ 定向回答；(2) 礼物商店自定义礼物可删除；(3) 版本号 3.1；(4) 聊天快速上翻被瞬间拉回底部的 bug；(5) 新增仿微信朋友圈功能（角色经云端 LLM+用户自有生图 API 发圈、点赞评论必回、自动定时发圈）。生图走中转站兼容的 OpenAI 聊天格式（参考图 image_url）；用户自己也能发圈；每条图数可选。

## 1. 群聊 @ 定向回答

- `GroupSpeakerPicker` 新增纯函数 `resolveReplySpeakers(memberIds, mentionIds, random)`：有 @ → 仅被 @ 成员按提及顺序回答（过滤无效 id，不去随机补人）；无 @ → 保持现有随机 1..cap。
- `GroupChatViewModel.sendMessage` 改用该函数。JVM 单测覆盖。

## 2. 礼物删除

- 数据层已就绪（`AffinityDao.deleteGift`、`GiftImageStore.deleteDefinitionImage` 零调用）。`AffinityRepository.deleteGift(giftId)`：先取 imagePath → 事务删 definition + inventory → 删图片文件。`gift_history` 冗余礼物名/图，礼物墙不受影响。
- `CheckinShopScreen` 礼物卡加删除入口 + 确认弹窗（提示已购库存一并删除）。

## 3. 版本号

- `build.gradle.kts` 已是 versionName 3.1 / versionCode 8（工作区未提交，保留）。
- `SettingsScreen` 关于区硬编码 `v1.0.0` → `BuildConfig.VERSION_NAME`。

## 4. 滚动回退 bug

根因：跟随效果的 `delay(16)` 窗口横跨「滚动落定→策略更新」竞态窗口时，`followBottom` 仍为 true 而 `isScrollInProgress` 已 false → `scrollToItem` 拉底。

修法（单聊 `ChatMessageList`）：引入**跟随锚点** `followAnchorTotal`（最近一次确认在底部时的总项数，settle 判定为 nearBottom 时刷新）。跟随滚动前校验 `lastVisibleIndex >= followAnchorTotal - 1`（旧末项仍在视口内）：内容自然增长时必成立；用户上翻后必不成立。谓词提为 `ChatAutoScrollPolicy.shouldFollowBottom` 纯函数可测。

群聊 `GroupChatMessageList` 目前无条件滚底，同样升级为「用户接管优先」策略（复用 `ChatAutoScrollPolicy` + 回到底部按钮 + 锚点校验）。

## 5. 朋友圈

### 数据（Room v13→v14，非破坏式迁移）
- `moment_post`（id/authorType user|character/characterId/content/imagesJson/createdAt/imagePrompt）
- `moment_comment`（postId/authorType/characterId/content/createdAt）
- `moment_like`（postId/characterId/createdAt，unique(postId, characterId)）
- 新文件 `data/local/MomentEntities.kt` + `MomentDao`；最近 100 条窗口。

### 生图链路（仅云端）
1. 主云端 LLM（现有 `DirectLlmClient.chatOnce` + ApiConfig）生成 JSON `{caption, imagePrompt}`（宽松解析，容忍代码围栏）；system 含人设 + 近期聊天上下文。
2. 新 `ImageGenClient`（data/remote）：OpenAI 聊天格式 `/chat/completions` 非流式，content = text(生图提示词) + image_url(角色立绘 data URL，经压缩)；中转站可用。独立配置（baseUrl/apiKey/model），与主 LLM 分离。
3. `MomentImageExtractor` 纯函数从回复提取图片：JSON url/b64_json、markdown ![]()、裸 URL、data URI；URL 经 OkHttp 下载、b64 解码，落 `filesDir/moment_images/`。
4. 图数 1-3 用户可选；生图未配置/失败 → 降级纯文字发圈。

### 互动
- 点赞（防重）；评论后**发帖角色必回**：LLM 以人设 + 帖子 + 评线程生成回复落库；继续评论继续回，仅发帖者参与。
- 用户可自发自写圈（文字 + 相册图片，不走 API）。

### 自动定时发圈
- 复用 GreetingWorker 成熟模式：15 分钟 PeriodicWork 心跳 + DataStore `moment_next_fire_at` 门控；间隔用户可调（默认 6h，抖动 0.85-1.15）；时段 8-23 点；角色严格轮换；启动时 `ensureScheduled`。无通知（打开朋友圈可见）。

### UI / 导航
- `ui/moment/MomentsScreen`：仿微信——顶部封面（可自定义，DataStore `moment_cover_path`）+ 右下角自己头像昵称 + 相机按钮；帖子列表 = 左头像 + 名字 + 文字 + 图（1 大图 / 2-3 宫格）+ 相对时间 + 「···」弹赞/评论；赞与评论浅灰缩进块。
- 新 `util/RelativeTime.kt`（刚刚/X分钟前/X小时前/昨天/MM-dd，纯函数 + 单测）。
- `MomentsViewModel` 承载生成流程与 UI 状态。
- 导航：`MOMENTS_ROUTE = "moments"`；通讯页顶栏「全部角色」按钮替换为「朋友圈」（角色页仍从底部 Tab 进）。
- 设置页新增「朋友圈」区：生图 API 三项 + 自动发圈开关/间隔/默认图数 + 保存；封面图选择。

### 常量
`AppConfig.Moment`：MAX_CONTEXT_MESSAGES、GENERATE_TIMEOUT_MS、FEED_WINDOW=100、DEFAULT_INTERVAL_HOURS=6、HOUR_START=8、HOUR_END=23、MAX_IMAGES=3。

## 验证

- JVM 单测：resolveReplySpeakers、shouldFollowBottom、RelativeTime、MomentImageExtractor、caption 解析。
- `JAVA_HOME=D:/jdk-temurin-17/jdk-17.0.20+8 ./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache` 验证编译（防 build cache 假象）。
- `./gradlew :app:testDebugUnitTest` 全量套件须全绿（结果 XML 在 D:/ai-build/rhodesisland）。
