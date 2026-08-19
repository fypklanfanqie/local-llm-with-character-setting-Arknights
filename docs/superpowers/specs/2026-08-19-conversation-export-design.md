# 聊天记录导出设计

**日期：** 2026-08-19

## 目标

让用户在“对话记录”页面选择任意一条会话，将已保存的完整聊天记录导出到自己选择的本地位置。支持完整 TXT，以及“自动分页多张 PNG”或“单张超长 PNG”两种图片模式。

## 已确认决策

- 入口位于对话记录底部弹层标题栏：`[导出记录] [＋ 新建对话]`，导出按钮在新建按钮左侧。
- 点击导出后先从当前角色的所有会话中选择一条会话。
- TXT 始终导出所选会话的全部已持久化消息。
- 图片由用户选择“自动分页多张图”或“一张超长图”。
- 所有文件均通过 Android 系统文件选择器让用户选择保存位置：TXT/单张图使用 `CreateDocument`，多图使用 `OpenDocumentTree`。
- 不申请宽泛存储权限；不上传或同步导出内容；取消选择器不产生残留文件。
- 图片不截取 Compose 页面，使用独立 Android Canvas 渲染器，确保长会话稳定性。

## 用户流程

```text
对话记录
  ├─ 导出记录
  │    └─ 选择会话
  │         └─ 选择格式：TXT / 图片
  │              ├─ TXT ──> 系统选择文件名与位置 ──> 写入完整 .txt
  │              └─ 图片 ──> 分页多张 / 单张超长图
  │                            └─ 系统选择位置 ──> 生成 PNG
  └─ ＋ 新建对话
```

选择会话列表展示标题、最后更新时间、当前会话标记和已保存消息数。会话导出只读取数据库中已落库的消息，不包含正在流式生成的临时 UI 消息。

## TXT 内容

TXT 使用 UTF-8，按时间顺序完整输出：

```text
罗德岛通讯记录
角色：阿米娅
会话：新对话
导出时间：2026-08-19 20:30

[2026-08-19 20:01] 博士
今天辛苦了。

[2026-08-19 20:02] 阿米娅
博士，您也辛苦了。

[附件：任务清单.pdf]
```

- 单聊助手名称取会话角色。
- 群聊按每条消息的 `characterId` 显示实际发言人。
- 图片/文件/视频以安全可读的附件占位文本显示，不复制私有文件路径或内容。
- 已保存的思考内容以正文形式导出；不导出数据库未保存的流式状态。

建议文件名：

```text
罗德岛通讯记录_<角色>_<会话>_<yyyyMMdd_HHmmss>.txt
```

文件名必须清理系统不允许的字符。

## 图片渲染

采用独立 Canvas 渲染，固定宽度 `1080 px`，深色 PRTS 风格：深蓝黑背景、金色标题、左右聊天气泡、角色名和时间。

- 文字自动换行；每一条消息、附件卡片和标题按测量高度布局。
- 分页模式安全页高为 `1920 px`，跨页时整条消息不能被截断；若单条消息过高，允许该消息在连续页内分段但保留发言人标记。
- 单张长图先测量总高；超过 `16384 px` 或预估 bitmap 内存不安全时不创建位图，提示用户使用分页或 TXT。
- 分页文件名添加 `_01.png`、`_02.png` 等序号。
- 图片模式的附件、视频、代码和思考内容均以文本卡片渲染；不下载远程图片，也不把附件二进制嵌入图片。

## 架构

新增 `conversationexport` 包：

```text
conversationexport/
├── ConversationExportModels.kt
├── ConversationExportService.kt
├── ConversationTextExporter.kt
├── ConversationImageRenderer.kt
└── ConversationExportWriter.kt
```

| 单元 | 职责 |
|---|---|
| `ConversationExportModels` | 导出格式、图片模式、导出文档、渲染块、结果与文件名纯模型。 |
| `ConversationExportService` | 读取会话与历史、补齐角色名、构建中间文档、调度 TXT/图片生成。 |
| `ConversationTextExporter` | 将中间文档完整格式化为 UTF-8 文本。 |
| `ConversationImageRenderer` | 仅在后台线程使用 `Bitmap`/`Canvas` 生成 PNG 页或长图。 |
| `ConversationExportWriter` | 用 `ContentResolver` 把字节流写进用户在 SAF 中选择的 `Uri`/目录。 |
| `ChatScreen`/`ConversationSheet` | 驱动选择状态、文件选择器、进度和用户反馈；不含导出业务格式。 |

`ChatRepository` 保持读取消息职责；导出不写回会话或聊天数据库。

## SAF 与写入

- TXT：`ActivityResultContracts.CreateDocument("text/plain")`。
- 长图：`ActivityResultContracts.CreateDocument("image/png")`。
- 分页图：`ActivityResultContracts.OpenDocumentTree()`，用返回目录 `Uri` 创建编号 PNG。
- 用户取消后清理内存状态，不报错。
- 写入失败、空间不足、目录无写权限时展示用户可读错误，不泄露 API Key、私有绝对路径或内部异常堆栈。

## 异常与边界

- 会话已删除：提示“该会话已不存在”。
- 无已保存消息：提示“没有可导出的已保存消息”。
- 图片长图超过安全限制：提示切换分页模式或 TXT。
- 图片渲染/写入失败：提示“图片生成失败，请尝试分页导出或 TXT”。
- 导出期间同一任务禁用重复提交并显示生成中。
- 不需要存储权限；不经过网络；不修改当前活跃会话。

## 测试

- 纯 JVM：文本完整性、角色名/群聊发言人、附件占位、文件名清理、空记录、长图决策、分页页数。
- Android 本地：Canvas 小样本 PNG 非空、图片尺寸、`ContentResolver` 写入临时 URI。
- Compose/instrumentation：导出按钮位置、会话选择、格式选择、图片模式选择和 SAF launcher 路由。
- 回归：新建、切换、重命名和删除会话行为保持不变。

## 非目标

- 不导出仍在流式输出的临时消息。
- 不导出图片/文件/视频的二进制内容。
- 不使用 MediaStore 自动保存或申请外部存储权限。
- 不支持跨角色批量导出；每次导出一条选择的会话。
