# Conversation Record Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users select one conversation from the conversation-history sheet and export its complete persisted history as TXT, paginated PNGs, or one safe-height PNG through Android’s system file picker.

**Architecture:** A new `conversationexport` package converts persisted conversation history into an immutable export document. Pure models, filename generation, text formatting, layout/pagination decisions are JVM-testable; Android Canvas and SAF writing sit behind focused adapters. `ChatScreen` owns export wizard state and Activity Result launchers, while `ConversationSheet` only displays export entry and selection callbacks.

**Tech Stack:** Kotlin, Room repositories, kotlinx.coroutines, Android Bitmap/Canvas, Compose Material 3, Storage Access Framework (`CreateDocument`, `OpenDocumentTree`), JUnit 4, Android instrumentation tests.

## Global Constraints

- Export exactly one user-selected conversation per operation; never implicitly export all conversations.
- TXT always contains every persisted message in chronological order; do not truncate it.
- Do not export streaming-only UI messages, API credentials, raw private filesystem paths, or attachment binary contents.
- Image export supports `PAGINATED` and `LONG_IMAGE`; fixed width is 1080 px and paginated page height is 1920 px.
- Reject a long image whose measured height exceeds 16,384 px; offer a readable error that suggests paginated PNG or TXT.
- All saves use user-mediated SAF: TXT/long PNG via `CreateDocument`, paginated PNGs via `OpenDocumentTree`.
- Do not request broad storage permission, use MediaStore automatic saving, or transmit content over network.
- Rendering and I/O run off the main thread; UI only coordinates state/progress.
- Use names sanitized for Android document providers; suggested files begin `罗德岛通讯记录_`.
- Build verification uses Temurin 17 and `:app:compileDebugKotlin --rerun-tasks --no-build-cache`.
- Existing unrelated broken unit tests may block the unit-test source set; report them faithfully and use focused production compile plus instrumentation where available.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationExportModels.kt` | Export format/mode/result/document/block models, filename sanitation, image layout limits. |
| `app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationTextExporter.kt` | Pure UTF-8 textual representation of a complete export document. |
| `app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationImageLayout.kt` | Pure image block layout and page/long-image safety decisions. |
| `app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationImageRenderer.kt` | Android Canvas PNG rendering using prepared pages. |
| `app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationExportWriter.kt` | `ContentResolver` stream writes and tree document creation. |
| `app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationExportService.kt` | Repository-to-document mapping, role/name resolution, text/image orchestration. |
| `app/src/main/java/com/rhodesisland/terminal/AppContainer.kt` | Own singleton `ConversationExportService`. |
| `app/src/main/java/com/rhodesisland/terminal/ui/chat/ChatViewModel.kt` | Load a selected conversation’s export document and expose no UI/SAF code. |
| `app/src/main/java/com/rhodesisland/terminal/ui/chat/ChatScreen.kt` | Export wizard, SAF launchers, progress/result UI, ConversationSheet callback. |
| `app/src/test/java/com/rhodesisland/terminal/conversationexport/ConversationTextExporterTest.kt` | Pure complete-text/filename/pagination contract tests. |
| `app/src/androidTest/java/com/rhodesisland/terminal/conversationexport/ConversationImageRendererTest.kt` | Canvas output and URI-writing integration tests. |

### Task 1: Define export document models, safe filenames, and text export

**Files:**
- Create: `app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationExportModels.kt`
- Create: `app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationTextExporter.kt`
- Create: `app/src/test/java/com/rhodesisland/terminal/conversationexport/ConversationTextExporterTest.kt`

**Interfaces:**
- Produces: `enum class ConversationExportFormat { TEXT, IMAGE }`.
- Produces: `enum class ConversationImageMode { PAGINATED, LONG_IMAGE }`.
- Produces: `ConversationExportDocument(title, ownerName, createdAt, exportedAt, messages)`.
- Produces: `ConversationExportMessage(timestamp, senderName, content, attachments)`.
- Produces: `ConversationTextExporter.render(document): String`.
- Produces: `suggestedExportBaseName(ownerName, title, exportedAt): String`.

- [ ] **Step 1: Write failing pure tests**

```kotlin
class ConversationTextExporterTest {
    @Test fun renderIncludesEveryMessageInChronologicalOrder() {
        val document = ConversationExportDocument(
            title = "雨夜行动",
            ownerName = "阿米娅",
            createdAt = 1_720_000_000_000L,
            exportedAt = 1_720_000_120_000L,
            messages = listOf(
                ConversationExportMessage(1_720_000_001_000L, "博士", "今晚辛苦了。"),
                ConversationExportMessage(1_720_000_002_000L, "阿米娅", "博士也请早点休息。", listOf("附件：行动记录.pdf")),
            ),
        )
        val text = ConversationTextExporter.render(document)
        assertTrue(text.indexOf("博士\n今晚辛苦了。") < text.indexOf("阿米娅\n博士也请早点休息。"))
        assertTrue(text.contains("附件：行动记录.pdf"))
        assertTrue(text.contains("会话：雨夜行动"))
    }

    @Test fun suggestedNameSanitizesProviderUnsafeCharacters() {
        assertEquals("罗德岛通讯记录_阿米娅_行动_报告_20260819_203000", suggestedExportBaseName("阿米娅", "行动:报告?", 1_724_096_200_000L))
    }
}
```

- [ ] **Step 2: Run the test and verify it fails because the export API is absent**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:testDebugUnitTest --tests "com.rhodesisland.terminal.conversationexport.ConversationTextExporterTest" --rerun-tasks --no-build-cache
```

Expected: failure due to missing `conversationexport` types. If compilation is stopped by existing unrelated tests, record their paths and use the resulting missing-type diagnostics as the RED evidence.

- [ ] **Step 3: Implement the smallest pure model and renderer**

Create model definitions:

```kotlin
const val EXPORT_IMAGE_WIDTH_PX = 1080
const val EXPORT_PAGE_HEIGHT_PX = 1920
const val EXPORT_LONG_IMAGE_MAX_HEIGHT_PX = 16_384

data class ConversationExportDocument(
    val title: String,
    val ownerName: String,
    val createdAt: Long,
    val exportedAt: Long,
    val messages: List<ConversationExportMessage>,
)

data class ConversationExportMessage(
    val timestamp: Long,
    val senderName: String,
    val content: String,
    val attachments: List<String> = emptyList(),
)
```

Use `SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)` only inside rendering helpers and `SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)` for filenames. Sanitize `\\ / : * ? \" < > |` and control whitespace into `_`, collapse repeated underscores, and use `"未命名会话"`/`"未知角色"` fallbacks. Render UTF-8-ready text with a title header, owner/title/export timestamp, then every message in input order, with attachment lines below each message.

- [ ] **Step 4: Re-run the focused test and production compile**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:testDebugUnitTest --tests "com.rhodesisland.terminal.conversationexport.ConversationTextExporterTest" --rerun-tasks --no-build-cache
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache
```

Expected: export tests pass if the existing unrelated test source compilation allows it; production compile must pass.

- [ ] **Step 5: Commit pure text export**

```bash
git add app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationExportModels.kt app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationTextExporter.kt app/src/test/java/com/rhodesisland/terminal/conversationexport/ConversationTextExporterTest.kt
git commit -m "feat: format conversation text exports" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 2: Add image layout decisions and Canvas PNG renderer

**Files:**
- Create: `app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationImageLayout.kt`
- Create: `app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationImageRenderer.kt`
- Create: `app/src/androidTest/java/com/rhodesisland/terminal/conversationexport/ConversationImageRendererTest.kt`
- Modify: `app/src/test/java/com/rhodesisland/terminal/conversationexport/ConversationTextExporterTest.kt`

**Interfaces:**
- Consumes: `ConversationExportDocument`, image constants from Task 1.
- Produces: `ConversationImageLayout.plan(document, mode): ImageRenderPlan`.
- Produces: `ConversationImageRenderer.render(plan): List<ByteArray>` as PNG pages.
- Produces: `LongImageTooTallException(height: Int)` when a long image is unsafe.

- [ ] **Step 1: Write failing image-mode decision and renderer tests**

```kotlin
@Test fun longImageOverSafetyLimitIsRejected() {
    val document = documentWithMessages(count = 1000, content = "长消息 ".repeat(120))
    assertThrows(LongImageTooTallException::class.java) {
        ConversationImageLayout.plan(document, ConversationImageMode.LONG_IMAGE)
    }
}

@Test fun paginatedModeCreatesNumberedNonemptyPngs() = runTest {
    val plan = ConversationImageLayout.plan(documentWithMessages(30, "测试消息"), ConversationImageMode.PAGINATED)
    val pngs = ConversationImageRenderer.render(plan)
    assertTrue(pngs.size >= 2)
    assertTrue(pngs.all { bytes -> bytes.size > 8 && bytes.take(8).toByteArray().contentEquals(PNG_SIGNATURE) })
}
```

Use an Android instrumentation test for `Bitmap`/`Canvas`; keep only the long-image planner assertion in the JVM suite.

- [ ] **Step 2: Run tests to establish RED**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:connectedDebugAndroidTest --instrumentation-arg class=com.rhodesisland.terminal.conversationexport.ConversationImageRendererTest
```

Expected: missing types before implementation. If no emulator/device is connected, state that device verification is blocked and run the pure planner test compilation instead.

- [ ] **Step 3: Implement deterministic layout and safe Canvas rendering**

Represent blocks as `ImageBlock(senderName, timestampText, content, attachments, heightPx)`. Use `TextPaint.breakText`/width measurement to determine wrapped line count, 1080 px width, 48 px horizontal inset, and page-safe block placement. Draw a dark `#07111F` background, gold header/accent `#D7B76A`, user bubbles aligned right, others aligned left, and attachment rectangles with their visible labels.

For paginated plans, begin a new 1920 px page before a normal block would cross the page footer. For blocks taller than a page, split wrapped content lines into continuation blocks with the sender label on the first continuation. For long mode, throw `LongImageTooTallException` before allocating if total planned height exceeds `EXPORT_LONG_IMAGE_MAX_HEIGHT_PX`.

Run renderer work under `Dispatchers.Default`; use `Bitmap.compress(Bitmap.CompressFormat.PNG, 100, ByteArrayOutputStream())`, recycle each bitmap after bytes are obtained.

- [ ] **Step 4: Re-run image verification and production compile**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:connectedDebugAndroidTest --instrumentation-arg class=com.rhodesisland.terminal.conversationexport.ConversationImageRendererTest
```

Expected: compile passes; instrumentation passes when a device is connected.

- [ ] **Step 5: Commit image export primitives**

```bash
git add app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationImageLayout.kt app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationImageRenderer.kt app/src/androidTest/java/com/rhodesisland/terminal/conversationexport/ConversationImageRendererTest.kt app/src/test/java/com/rhodesisland/terminal/conversationexport/ConversationTextExporterTest.kt
git commit -m "feat: render conversation export images" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 3: Build repository-backed export service and SAF writer

**Files:**
- Create: `app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationExportWriter.kt`
- Create: `app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationExportService.kt`
- Modify: `app/src/main/java/com/rhodesisland/terminal/AppContainer.kt:70-90`
- Modify: `app/src/main/java/com/rhodesisland/terminal/ui/chat/ChatViewModel.kt:280-392`
- Test: `app/src/test/java/com/rhodesisland/terminal/conversationexport/ConversationTextExporterTest.kt`

**Interfaces:**
- Consumes: `ConversationRepository`, `ChatRepository`, `CharacterRepository`, models/renderers from Tasks 1–2.
- Produces: `suspend fun ConversationExportService.prepare(conversationId: Long): ConversationExportDocument`.
- Produces: `suspend fun ConversationExportWriter.writeText(uri: Uri, text: String): Result<Unit>`.
- Produces: `suspend fun ConversationExportWriter.writePng(uri: Uri, bytes: ByteArray): Result<Unit>` and `writePngPages(treeUri, baseName, pages)`.
- Produces: `ChatViewModel.prepareConversationExport(id, onReady, onError)`.

- [ ] **Step 1: Add failing export document tests**

```kotlin
@Test fun preparedGroupConversationUsesMessageSpeakerInsteadOfConversationOwner() = runTest {
    val document = service.prepare(groupConversationId)
    assertEquals("能天使", document.messages.first { it.content == "我先来！" }.senderName)
}

@Test fun preparedDocumentUsesAttachmentLabelsNotPrivatePaths() = runTest {
    val document = service.prepare(conversationId)
    assertTrue(document.messages.single().attachments.single().startsWith("附件："))
    assertFalse(document.messages.single().attachments.single().contains("/data/user/"))
}
```

Use fake repository interfaces if existing repositories are concrete and difficult to instantiate; add a narrow `ConversationExportDataSource` interface implemented in `AppContainer` rather than making test-only methods on production repositories.

- [ ] **Step 2: Run the tests and verify the service is absent**

Run the focused JVM test command from Task 1.

Expected: failure because `ConversationExportService` and its data source are absent, subject to existing unrelated source-set errors.

- [ ] **Step 3: Implement service mapping and SAF writer**

`prepare` must:

1. get the `Conversation` by ID or throw `ConversationExportException("该会话已不存在")`;
2. get `chatRepository.getHistory(conversation.id)` sorted ascending by timestamp;
3. throw `ConversationExportException("没有可导出的已保存消息")` when history is empty;
4. resolve single-chat speaker from the conversation character, and group speaker per `message.characterId`, falling back to `"群聊成员"`;
5. turn images/files/fileNames into labels such as `"图片附件（N 张）"`, `"附件：<safe filename>"`; never include `AttachedFile.path`;
6. create the export document with `exportedAt = System.currentTimeMillis()`.

Write each SAF stream with `contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }`, returning a failure if null. For `OpenDocumentTree`, use `DocumentFile.fromTreeUri`, create `image/png` documents with sanitized numbered names, and delete any pages created in this invocation if a later page write fails.

Inject `ConversationExportService` in `AppContainer`; make the ViewModel launch only `prepare` and return a document to screen callbacks, not a Uri or Context.

- [ ] **Step 4: Run compile and available tests**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:testDebugUnitTest --tests "com.rhodesisland.terminal.conversationexport.*" --rerun-tasks --no-build-cache
```

Expected: production compile passes. Report pre-existing source-set compilation failures separately if they block the JVM task.

- [ ] **Step 5: Commit service and writer**

```bash
git add app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationExportWriter.kt app/src/main/java/com/rhodesisland/terminal/conversationexport/ConversationExportService.kt app/src/main/java/com/rhodesisland/terminal/AppContainer.kt app/src/main/java/com/rhodesisland/terminal/ui/chat/ChatViewModel.kt app/src/test/java/com/rhodesisland/terminal/conversationexport/ConversationTextExporterTest.kt
git commit -m "feat: prepare and save conversation exports" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 4: Add conversation-sheet export wizard and SAF UI coordination

**Files:**
- Modify: `app/src/main/java/com/rhodesisland/terminal/ui/chat/ChatScreen.kt:1-460,1515-1696`
- Test: `app/src/androidTest/java/com/rhodesisland/terminal/conversationexport/ConversationImageRendererTest.kt`

**Interfaces:**
- Consumes: ViewModel `prepareConversationExport`, export models, service/writer from Task 3.
- Produces: export button left of new-conversation button; selection dialogs; `CreateDocument`/`OpenDocumentTree` launch routing; status feedback.

- [ ] **Step 1: Write a failing UI intent test**

Add a Compose/instrumentation test that renders a `ConversationSheet` with two conversations and verifies:

```kotlin
composeRule.onNodeWithText("导出记录").assertExists()
composeRule.onNodeWithText("＋ 新建对话").assertExists()
composeRule.onNodeWithText("导出记录").performClick()
composeRule.onNodeWithText("选择要导出的对话").assertExists()
composeRule.onNodeWithText("雨夜行动").performClick()
composeRule.onNodeWithText("TXT（完整记录）").assertExists()
composeRule.onNodeWithText("图片（PNG）").assertExists()
```

Make the sheet receive an `onExportRequest(conversationId)` callback; tests assert intent dispatch rather than trying to invoke Android’s picker.

- [ ] **Step 2: Run the instrumentation test and verify RED**

Run the Task 2 instrumentation command. Expected: failure because export controls/state do not exist.

- [ ] **Step 3: Implement the UI wizard and SAF launchers**

In `ChatScreen`, create three launchers:

```kotlin
rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> ... }
rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> ... }
rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri -> ... }
```

Maintain a single `pendingExport: PendingConversationExport?`, `exportBusy`, and `exportMessage`. The wizard must have these stages:

1. `ConversationSheet` header button launches a select-conversation dialog.
2. Selecting a conversation triggers `viewModel.prepareConversationExport`; show loading state.
3. Ready document opens format dialog: `TXT（完整记录）`, `图片（PNG）`.
4. TXT launches text `CreateDocument` with `${baseName}.txt`.
5. Image opens mode dialog: `自动分页多张图`, `一张超长图`.
6. Long image prepares/render-checks then launches PNG `CreateDocument`; if too tall, show the prescribed fallback message.
7. Paginated image launches `OpenDocumentTree`; writer creates numbered PNGs.

At the `ConversationSheet` header, put:

```kotlin
Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    TextButton(onClick = onStartExport) { Text("导出记录") }
    TextButton(onClick = onNew) { Icon(...); Text("新建对话") }
}
```

Disable export/new buttons only while a write/render operation is active. On a null URI/tree URI, clear pending state silently. On completion, show a snackbar/toast with the number of written files. Never expose the document body in a toast.

- [ ] **Step 4: Run Kotlin compile and device UI test when available**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:connectedDebugAndroidTest --instrumentation-arg class=com.rhodesisland.terminal.conversationexport.ConversationImageRendererTest
```

Expected: production compile passes; device test passes when a device/emulator is connected.

- [ ] **Step 5: Commit export UI**

```bash
git add app/src/main/java/com/rhodesisland/terminal/ui/chat/ChatScreen.kt app/src/androidTest/java/com/rhodesisland/terminal/conversationexport/ConversationImageRendererTest.kt
git commit -m "feat: export selected conversation records" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 5: Final verification and regression inspection

**Files:**
- Modify only files with a demonstrated export bug found by verification.
- Test: all export test files from Tasks 1–4.

**Interfaces:**
- Consumes: completed Tasks 1–4.
- Produces: verified debug APK and evidence of no unintended export-side storage/network behavior.

- [ ] **Step 1: Run focused export tests**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:testDebugUnitTest --tests "com.rhodesisland.terminal.conversationexport.*" --rerun-tasks --no-build-cache
```

Expected: pass, or report the exact pre-existing unrelated test compiler errors that block running it.

- [ ] **Step 2: Build debug APK from scratch**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:compileDebugKotlin :app:assembleDebug --rerun-tasks --no-build-cache
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Verify no broad storage permission or network export path was added**

Run:

```bash
git diff master...HEAD -- app/src/main/AndroidManifest.xml
git grep -n -i -E 'WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|MediaStore|http' -- app/src/main/java/com/rhodesisland/terminal/conversationexport || true
git diff --check master...HEAD
git status --short
```

Expected: no storage permission additions, no outbound network handling in the export package, no whitespace errors, and a clean worktree.

- [ ] **Step 4: Verify device-only paths if a device is connected**

Run:

```bash
C:/Users/Lfq06/AppData/Local/Android/Sdk/platform-tools/adb.exe devices
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:connectedDebugAndroidTest --instrumentation-arg class=com.rhodesisland.terminal.conversationexport.ConversationImageRendererTest
```

Expected: a listed device and passing instrumentation. If no device is listed, report the skip rather than claiming device coverage.

- [ ] **Step 5: Commit only proven verification corrections**

```bash
git add <corrected-files>
git commit -m "fix: finalize conversation export verification" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Do not create an empty commit when Steps 1–4 require no source change.

## Self-Review

- All approved requirements map to Tasks 1–4: selected single conversation, complete TXT, image mode choice, Canvas renderer, paginated/long safety behavior, system-managed save destination, group/attachments, cancellation, and local-only security.
- Task 5 verifies the APK, no storage permissions/network export behavior, and available device paths.
- Every declared type/function is created before its consuming task.
- The plan avoids screenshotting Compose, wide permissions, implicit all-conversation export, and unbounded long bitmaps.
