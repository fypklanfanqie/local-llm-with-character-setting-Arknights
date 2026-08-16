# Seedance 人物参考图左右拆分 — 设计文档

日期：2026-08-16
状态：待评审

## 目标

视频生成提交参考图时，把「人物参考照片」（角色立绘）沿竖中线裁成左、右两半，作为第 1、第 2 张参考图提交；生成提示词同步说明「第 1 张 = 人物左半、第 2 张 = 人物右半」，让模型拼合还原完整形象。背景图（若有）顺延为第 3 张。

## 已确认决策

1. 拆分形态：沿竖中线 `x ∈ [0, w/2]`、`x ∈ [w/2, w]` 裁成左半、右半各一张，作为**两张独立参考图**提交。
2. 拆分发生层：**快照阶段**（`SeedanceReferenceStore.snapshot`），即时对所有角色（内置/自定义/存量/新上传）生效；`characterImagePath` 仍保存完整立绘，供「邂逅」页背景展示，不为半图所替代。
3. 宽高比兜底：裁完若半图宽高比 < 0.4（竖立绘常见），居中补白到 0.4（人物不缩放、不变形）。
4. 半图格式统一 **PNG**（无损、支持透明补白）。

## 现状（改动前）

- `SeedanceReferenceStore.snapshot` 复制角色整图到 `{targetRoot}/{taskUuid}/references/character.{ext}`，及可选背景图；返回 `SeedanceReferenceSnapshot`（单个 `characterPath/Mime/Sha256`）。
- `SeedancePipelineCoordinator.advanceSnapshot` 把整图路径写回 `SeedanceVideo.characterImagePath`（此列同时被「邂逅」背景 `EncounterBackdrop` 读取）。
- `advanceSubmission` 用 `characterImagePath/Mime/Sha256` 编码单张角色图，构造 `CreateSeedanceTask(char, background?)`，交 `SeedanceClient` 提交。
- `SeedanceClient`：ARK 协议 content = `[text, image_url(角色, role=reference_image), image_url(背景, reference_image)]`；媒体协议 `params.images = [角色, 背景]`。
- `SeedancePromptGenerator`：`buildReferenceDirective(hasBackgroundReference)` 产出「第 1 张=角色 / 第 2 张=背景」，写入最终提示词；`SYSTEM_PROMPT` 第 2、8 条与 `buildUserMessage`【参考图】小节同口径。
- 数据库 `AppDatabase` version 5；`seedance_video` 表已含 `characterImagePath/Mime/Sha256`。

## 详细设计

### 1. 数据模型 + Room 迁移

`SeedanceVideo`（领域）与 `SeedanceVideoEntity`（Room）各新增两列，置于现有 `characterImageSha256` 之后：

- `characterLeftImagePath: String?`
- `characterRightImagePath: String?`

约束：

- `characterImagePath` 语义不变 = **完整立绘**（邂逅背景展示 + 校验兜底参照）。
- 左/右半图统一 PNG，因此**不新增** mime/sha 列；提交编码用 `"image/png"`，幂等指纹继续用整图 `characterImageSha256`（半图由整图确定性导出）。
- 幂等指纹 `requestFingerprint` 追加一个拆分 schema 版本常量 `"refsplit=1"`，防止未来改动拆分算法后在途歧义任务被误判为同一请求。

迁移：

- `AppDatabase` version 5 → 6；新增 `MIGRATION_5_6`：
  ```sql
  ALTER TABLE seedance_video ADD COLUMN characterLeftImagePath TEXT
  ALTER TABLE seedance_video ADD COLUMN characterRightImagePath TEXT
  ```
- `build.gradle.kts` `versionCode` +1（参照 ee45301 注释：触发客户端缓存刷新不用，但迁移需版本号推进）。

### 2. 快照与拆分

新增可注入接口（避免 `android.graphics.Bitmap` 进 JVM 单测）：

```kotlin
/** 把一张立绘沿竖中线裁成左右两半写入 targetDir，返回两半绝对路径；失败返回 null。 */
fun interface CharacterImageSplitter {
    fun split(source: ProbeSource, targetDir: File): SplitPair?  // SplitPair(left, right)
}
```

生产实现 `AndroidCharacterImageSplitter`（`android.graphics.Bitmap`）：

1. 解码 `ProbeSource` 为 `Bitmap`（失败返回 null）。
2. 左半 = `Bitmap.createBitmap(src, 0, 0, w/2, h)`；右半 = `createBitmap(src, w/2, 0, w - w/2, h)`。
3. 各自做宽高比兜底：若 `halfW/h < 0.4`，平铺到 `newW = ceil(h * 0.4)` 的透明画布并居中（半图统一 PNG，透明边不引入额外内容，模型应忽略空白边）。
4. `Bitmap.compress(PNG, 100)` 写入 `targetDir/character_left.png`、`character_right.png`（沿用「先写 `.tmp` 再原子改名」的既有约定）。

`SeedanceReferenceStore`：

- 构造器新增 `splitter: CharacterImageSplitter`；`production(context)` 传 `AndroidCharacterImageSplitter()`。
- `snapshot` 在 `copyReference(character, "character")` 成功后，追加拆分：以 `targetDir` 为目标目录、以原始 `ProbeSource` 为源调用 `splitter.split(...)`；失败按现有方式 `Result.failure`（中文原因「人物参考图左右拆分失败」）。
- `SeedanceReferenceSnapshot` 新增 `characterLeftPath: String`、`characterRightPath: String`（非空；整图/背景字段不变）。

说明：拆分源复用 `copyReference` 前的同一个 `ProbeSource`（可重复打开），与整图复制共享「不可变任务级快照」语义；半图只写一次，后续 Worker 幂等复用（沿用 `copyReference` 的「已存在则 SHA 校验/复用」思路，或在 splitter 内做同类处理）。

### 3. 提交路径

`CreateSeedanceTask`（`SeedanceClient.kt`）改为显式两半：

```kotlin
data class CreateSeedanceTask(
    val finalPrompt: String,
    val characterLeft: SeedanceImageContent,
    val characterRight: SeedanceImageContent,
    val background: SeedanceImageContent?,
    val variant: …, val resolution: …, val ratio: …, val durationSeconds: Int, val watermark: Boolean,
)
```

`SeedancePipelineCoordinator.advanceSubmission`：

- 校验由「`characterImagePath` 非空」改为「`characterLeftImagePath` 与 `characterRightImagePath` 均非空」。
- 编码：`encoder.encode(leftPath, "image/png", budget)`、`encoder.encode(rightPath, "image/png", budget)`；背景沿用 `backgroundImageMime ?: charMime`。
- `requestFingerprint` 追加 `"refsplit=1"` 常量。

`SeedanceClient`：

- ARK `buildCreateRequest`：content = `text` → `image_url(左半, reference_image)` → `image_url(右半, reference_image)` → `image_url(背景, reference_image)`。
- 媒体协议 `buildMediaCreateRequest`：`images = [左半, 右半, 背景?]`（顺序与 ARK 一致）。

### 4. 提示词（SeedancePromptGenerator）

`buildReferenceDirective` 改为（拆分恒开）：

- 无背景：`["角色形象以第 1、2 张参考图为准：第 1 张为角色左半身、第 2 张为角色右半身，请将两半拼合还原为完整角色形象"]`
- 有背景：追加 `["背景场景以第 3 张参考图为准"]`

同步改：

- `SYSTEM_PROMPT` 第 2 条（外貌一致性依据）与第 8 条（参考图角色映射）：角色 = 第 1（左半）+ 第 2（右半）拼合；背景 = 第 3（若有）。
- `buildUserMessage`【参考图】小节：描述第 1 张=角色左半、第 2 张=角色右半、第 3 张=背景（若有）。

`SeedancePromptInput` 无需新增字段：`hasBackgroundReference` 语义不变（是否附背景），拆分恒开由生成器自身的指令文本表达。

### 5. 接线与装配

- `AppContainer`：`SeedanceReferenceStore.production(context)` 内注入 `AndroidCharacterImageSplitter()`；其余 coordinator/client 装配不变。

## 错误处理

- 拆分失败（解码失败/压缩失败/写盘失败）→ `snapshot` 返回 `Result.failure`，协调器按现状转 `FAILED_SNAPSHOT`（中文原因），用户修复立绘后可重试；不产生任何远端费用、不改动聊天回复。
- 半图超预算：半图为整图之一半，理论恒 < 预算；若仍超限，`encode` 沿用现状压缩/抛错路径。

## 测试

- `SeedanceReferenceStoreTest`：注入假 splitter，断言整图 + `character_left.png` + `character_right.png` 三产物、快照两字段回填、拆分失败转 failure。
- `SeedancePipelineCoordinatorTest`：左/右两列写回、`CreateSeedanceTask` 携带两半、校验缺失半图时 `FAILED_SUBMISSION/MISSING_REFERENCE`。
- `SeedancePromptGeneratorTest`：无背景/有背景两种映射文案；`SYSTEM_PROMPT`、用户消息含「左半/右半/第 3 张」。
- `SeedanceClientTest`：ARK content 顺序（text/左/右/背景）、媒体协议 images 顺序（左/右/背景）。
- `Migration5To6Test`（新）：4→6 链式迁移（或 5→6）后列存在、旧行两列 null、数据不丢。
- `SeedanceVideoMappingTest`：新两列实体 ↔ 领域往返。

## 范围外

- 不改角色编辑/立绘上传流程（`CharactersScreen`/`CharacterImageStore`）。
- 不改背景图选取（`SeedanceSceneStore`）。
- 不改「邂逅」页展示（仍用整图）。
- 不做「过竖则退回整图」分支（已选补白方案）。