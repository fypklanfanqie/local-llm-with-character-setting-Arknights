# Direct Volcano Engine TTS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace CloudBase-proxied TTS with direct Volcano Engine HTTP Chunked V3, model a per-language voice/resource binding, correct authentication and response parsing, and cover the integration with automated tests.

**Architecture:** `VolcTtsClient` owns the direct HTTP V3 transport, credential selection, request validation, response parsing, and error conversion. `TtsManager` selects a validated language-specific `VoiceConfig` and remains responsible for Android playback. Settings persist richer `VoicePair` records and show credentials plus per-language voice/resource fields. Existing string-only voice maps migrate lazily when read.

**Tech Stack:** Kotlin, kotlinx.serialization, OkHttp, MockWebServer, Android DataStore Preferences, Jetpack Compose, JUnit 4, Android instrumentation tests.

## Global Constraints

- Direct endpoint is exactly `https://openspeech.bytedance.com/api/v3/tts/unidirectional`.
- Use HTTP Chunked JSON lines only; do not add SSE transport support.
- New auth: `X-Api-Key` + `X-Api-Resource-Id`; old auth: `X-Api-App-Id` + `X-Api-Access-Key` + `X-Api-Resource-Id`.
- API Key has precedence over old credentials; never send both credential styles in one request.
- Never write API keys, access keys, authorization headers, or request body credentials to logs or test fixtures.
- A nonblank voice ID requires a nonblank Resource ID and vice versa, separately for Chinese and Japanese.
- Official `code == 0` is an audio chunk and `code == 20000000` is successful completion.
- Preserve old stored `Map<String, {zh: string, ja: string}>` data by decoding it into empty-resource `VoiceConfig` records.
- Do not modify or deploy CloudBase code; delete only local references whose sole purpose is TTS proxying.
- Verify Kotlin changes with `./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache` using Temurin 17, then run targeted tests.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/rhodesisland/terminal/config/AppConfig.kt` | Direct official endpoint and TTS constants only. |
| `app/src/main/java/com/rhodesisland/terminal/data/model/Config.kt` | `TtsConfig`, `VoiceConfig`, and per-language `VoicePair` data schema. |
| `app/src/main/java/com/rhodesisland/terminal/data/local/SettingsStore.kt` | Backward-compatible TTS credential and voice-map persistence. |
| `app/src/main/java/com/rhodesisland/terminal/data/repository/SettingsRepository.kt` | Existing TTS snapshots and convenience getters updated for rich voice bindings. |
| `app/src/main/java/com/rhodesisland/terminal/tts/VolcTtsClient.kt` | Direct official request construction, auth policy, Chunked parser, diagnostic errors. |
| `app/src/main/java/com/rhodesisland/terminal/manager/TtsManager.kt` | Language-specific voice/resource selection and local validation before transport. |
| `app/src/main/java/com/rhodesisland/terminal/AppContainer.kt` | Inject a dedicated finite-timeout TTS `OkHttpClient` and direct endpoint client. |
| `app/src/main/java/com/rhodesisland/terminal/ui/settings/SettingsScreen.kt` | Credential inputs and paired voice/resource inputs, validation, preservation on save. |
| `app/src/main/java/com/rhodesisland/terminal/ui/settings/GuideDialog.kt` | Accurate direct-call and resource-binding user guidance. |
| `app/src/test/java/com/rhodesisland/terminal/tts/VolcTtsClientTest.kt` | MockWebServer request and response contract tests. |
| `app/src/test/java/com/rhodesisland/terminal/data/model/VoiceConfigTest.kt` | Pure credential and voice/resource validation tests. |
| `app/src/androidTest/java/com/rhodesisland/terminal/data/local/TtsSettingsStoreTest.kt` | DataStore rich-schema and old-schema persistence tests. |

### Task 1: Define TTS endpoint and durable voice/resource schema

**Files:**
- Modify: `app/src/main/java/com/rhodesisland/terminal/config/AppConfig.kt:7-22`
- Modify: `app/src/main/java/com/rhodesisland/terminal/data/model/Config.kt:17-94`
- Create: `app/src/test/java/com/rhodesisland/terminal/data/model/VoiceConfigTest.kt`

**Interfaces:**
- Produces: `AppConfig.TTS_DIRECT_URL: String`.
- Produces: `VoiceConfig(voiceId: String, resourceId: String)` with `isComplete` and `isEmpty` predicates.
- Produces: `VoicePair.zh` and `.ja` typed as `VoiceConfig`.
- Produces: `TtsConfig.authMode(): TtsAuthMode` and `TtsConfig.validationError(): String?`.

- [ ] **Step 1: Write failing pure-schema tests**

```kotlin
class VoiceConfigTest {
    @Test fun `voice binding requires voice and resource together`() {
        assertNull(VoiceConfig().validationError("中文"))
        assertNull(VoiceConfig("S_voice", "seed-icl-2.0").validationError("中文"))
        assertEquals("中文音色已填写，但缺少对应 Resource ID", VoiceConfig("S_voice", "").validationError("中文"))
        assertEquals("中文 Resource ID 已填写，但缺少对应音色 ID", VoiceConfig("", "seed-icl-2.0").validationError("中文"))
    }

    @Test fun `api key is preferred over legacy credentials`() {
        assertEquals(TtsAuthMode.API_KEY, TtsConfig(apiKey = "key", appId = "id", accessKey = "token").authMode())
        assertEquals(TtsAuthMode.LEGACY, TtsConfig(appId = "id", accessKey = "token").authMode())
        assertEquals(TtsAuthMode.NONE, TtsConfig(appId = "id").authMode())
    }
}
```

- [ ] **Step 2: Run the test to prove the new API is absent**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:testDebugUnitTest --tests "com.rhodesisland.terminal.data.model.VoiceConfigTest" --rerun-tasks --no-build-cache
```

Expected: compilation failure because `VoiceConfig` and `TtsAuthMode` do not exist.

- [ ] **Step 3: Add the minimal schema and direct endpoint**

Replace the TTS proxy constant with:

```kotlin
/** 火山引擎语音合成 HTTP V3 Chunked 官方直连地址。 */
const val TTS_DIRECT_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
```

Add to `Config.kt`:

```kotlin
enum class TtsAuthMode { API_KEY, LEGACY, NONE }

@Serializable
data class VoiceConfig(
    val voiceId: String = "",
    val resourceId: String = "",
) {
    val isEmpty: Boolean get() = voiceId.isBlank() && resourceId.isBlank()
    val isComplete: Boolean get() = voiceId.isNotBlank() && resourceId.isNotBlank()

    fun validationError(label: String): String? = when {
        isEmpty || isComplete -> null
        voiceId.isNotBlank() -> "$label音色已填写，但缺少对应 Resource ID"
        else -> "$label Resource ID 已填写，但缺少对应音色 ID"
    }
}

@Serializable
data class VoicePair(
    val zh: VoiceConfig = VoiceConfig(),
    val ja: VoiceConfig = VoiceConfig(),
)

fun TtsConfig.authMode(): TtsAuthMode = when {
    apiKey.isNotBlank() -> TtsAuthMode.API_KEY
    appId.isNotBlank() && accessKey.isNotBlank() -> TtsAuthMode.LEGACY
    else -> TtsAuthMode.NONE
}

fun TtsConfig.validationError(): String? = when (authMode()) {
    TtsAuthMode.API_KEY, TtsAuthMode.LEGACY -> null
    TtsAuthMode.NONE -> "请填写 API Key，或同时填写 App ID 与 Access Key"
}
```

Update all callers of `VoicePair.zh` and `.ja` to use `.voiceId`; do not change behavior elsewhere yet.

- [ ] **Step 4: Run the schema test**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 5: Commit the schema task**

```bash
git add app/src/main/java/com/rhodesisland/terminal/config/AppConfig.kt app/src/main/java/com/rhodesisland/terminal/data/model/Config.kt app/src/test/java/com/rhodesisland/terminal/data/model/VoiceConfigTest.kt
git commit -m "feat: model TTS voice resource bindings" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 2: Preserve rich and legacy TTS voice maps in DataStore

**Files:**
- Modify: `app/src/main/java/com/rhodesisland/terminal/data/local/SettingsStore.kt:293-307`
- Modify: `app/src/main/java/com/rhodesisland/terminal/data/repository/SettingsRepository.kt:38-43,115-120,200-223`
- Create: `app/src/androidTest/java/com/rhodesisland/terminal/data/local/TtsSettingsStoreTest.kt`

**Interfaces:**
- Consumes: `VoiceConfig`, `VoicePair` from Task 1.
- Produces: `SettingsStore.ttsVoiceMap: Flow<Map<String, VoicePair>>` that accepts legacy values.
- Produces: `SettingsStore.setTtsVoiceMap(map: Map<String, VoicePair>)` with richer JSON persistence.

- [ ] **Step 1: Write failing DataStore round-trip tests**

```kotlin
@Test fun richVoicePair_roundTrips() = runTest {
    val expected = mapOf(
        "amiya" to VoicePair(
            zh = VoiceConfig("S_cn", "seed-icl-2.0"),
            ja = VoiceConfig("S_ja", "seed-icl-1.0"),
        ),
    )
    store.setTtsVoiceMap(expected)
    assertEquals(expected, store.ttsVoiceMap.first())
}

@Test fun legacyStringVoicePair_decodesWithEmptyResourceIds() = runTest {
    writeRawPreference("tts_voice_map", "{\"amiya\":{\"zh\":\"S_cn\",\"ja\":\"S_ja\"}}")
    assertEquals(
        VoicePair(VoiceConfig("S_cn"), VoiceConfig("S_ja")),
        store.ttsVoiceMap.first().getValue("amiya"),
    )
}
```

Use the existing `SeedanceSettingsTest` DataStore test setup and its isolated Context pattern; implement `writeRawPreference` using the same Preferences DataStore backing store rather than production storage.

- [ ] **Step 2: Run the instrumentation test to prove legacy decoding fails**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:connectedDebugAndroidTest --instrumentation-arg class=com.rhodesisland.terminal.data.local.TtsSettingsStoreTest
```

Expected: FAIL because the current serializer expects `String` values and cannot produce `VoiceConfig` values.

- [ ] **Step 3: Implement two-shape decoding**

Decode rich maps first; if rich decoding fails, decode this private legacy DTO and map it:

```kotlin
@Serializable
private data class LegacyVoicePair(val zh: String = "", val ja: String = "")

private fun decodeVoiceMap(raw: String): Map<String, VoicePair> = runCatching {
    voiceJson.decodeFromString<Map<String, VoicePair>>(raw)
}.recoverCatching {
    voiceJson.decodeFromString<Map<String, LegacyVoicePair>>(raw).mapValues { (_, value) ->
        VoicePair(zh = VoiceConfig(voiceId = value.zh), ja = VoiceConfig(voiceId = value.ja))
    }
}.getOrDefault(emptyMap())
```

Use `decodeVoiceMap(raw)` in the `ttsVoiceMap` flow. Keep `setTtsVoiceMap` writing the rich `Map<String, VoicePair>` form. Keep all `TtsConfig` fields unchanged during saves.

- [ ] **Step 4: Run the test again**

Run the command from Step 2.

Expected: PASS on a connected emulator/device. If no device is connected, run `:app:compileDebugKotlin` and explicitly record instrumentation verification as blocked by device availability.

- [ ] **Step 5: Commit persistence compatibility**

```bash
git add app/src/main/java/com/rhodesisland/terminal/data/local/SettingsStore.kt app/src/main/java/com/rhodesisland/terminal/data/repository/SettingsRepository.kt app/src/androidTest/java/com/rhodesisland/terminal/data/local/TtsSettingsStoreTest.kt
git commit -m "feat: persist TTS voice resource bindings" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 3: Implement direct HTTP Chunked transport and official response semantics

**Files:**
- Modify: `app/src/main/java/com/rhodesisland/terminal/tts/VolcTtsClient.kt:1-222`
- Modify: `app/src/main/java/com/rhodesisland/terminal/AppContainer.kt:102-123,303-305`
- Create: `app/src/test/java/com/rhodesisland/terminal/tts/VolcTtsClientTest.kt`

**Interfaces:**
- Consumes: `TtsAuthMode`, `TtsConfig.validationError()`, `VoiceConfig` from Task 1.
- Produces: `suspend fun synthesize(text: String, characterId: String, ttsConfig: TtsConfig, voice: VoiceConfig): ByteArray`.
- Produces: direct official endpoint, auth-specific headers, and Chunked parser behavior.

- [ ] **Step 1: Write failing MockWebServer contracts**

```kotlin
@Test fun apiKeyRequest_usesOfficialHeadersAndVoiceResource() = runTest {
    server.enqueue(MockResponse().setBody(
        "{\"code\":0,\"message\":\"\",\"data\":\"YWJj\"}\n" +
        "{\"code\":20000000,\"message\":\"ok\",\"data\":null}\n",
    ))
    val bytes = client.synthesize(
        text = "你好",
        characterId = "amiya",
        ttsConfig = TtsConfig(apiKey = "test-api-key"),
        voice = VoiceConfig("S_cn", "seed-icl-2.0"),
    )

    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/api/v3/tts/unidirectional", request.path)
    assertEquals("test-api-key", request.getHeader("X-Api-Key"))
    assertEquals("seed-icl-2.0", request.getHeader("X-Api-Resource-Id"))
    assertNull(request.getHeader("X-Api-App-Id"))
    assertNull(request.getHeader("X-Api-Access-Key"))
    assertTrue(request.getHeader("X-Api-Request-Id")!!.isNotBlank())
    assertEquals("abc", bytes.decodeToString())
}

@Test fun legacyRequest_usesOfficialLegacyHeaders() = runTest {
    server.enqueue(successResponse())
    client.synthesize("你好", "amiya", TtsConfig(appId = "app", accessKey = "access"), VoiceConfig("S_cn", "seed-icl-2.0"))
    val request = server.takeRequest()
    assertEquals("app", request.getHeader("X-Api-App-Id"))
    assertEquals("access", request.getHeader("X-Api-Access-Key"))
    assertEquals("seed-icl-2.0", request.getHeader("X-Api-Resource-Id"))
    assertNull(request.getHeader("X-Api-Key"))
}

@Test fun parser_acceptsOfficialChunkAndFinalCodes() = runTest { /* first test response proves this */ }
@Test fun incompleteCredentials_orVoiceBinding_doNotOpenNetworkCall() = runTest { /* assertThrows and server.requestCount == 0 */ }
@Test fun parser_rejectsServerError_andInvalidBase64() = runTest { /* code 55000000 and malformed data */ }
```

Construct the test client with `server.url("/").toString().removeSuffix("/")` so the production constant can be injected. Add test dependencies only if MockWebServer is not already declared, matching the project’s OkHttp version.

- [ ] **Step 2: Run the new contract tests**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:testDebugUnitTest --tests "com.rhodesisland.terminal.tts.VolcTtsClientTest" --rerun-tasks --no-build-cache
```

Expected: FAIL because the client still requires language, uses a proxy URL, hard-codes its resource ID, and labels `20000000` as an error.

- [ ] **Step 3: Refactor `VolcTtsClient` around direct transport**

Use the constructor:

```kotlin
class VolcTtsClient(
    private val endpoint: String,
    private val client: OkHttpClient,
)
```

Build auth headers with exactly one branch:

```kotlin
when (ttsConfig.authMode()) {
    TtsAuthMode.API_KEY -> addHeader("X-Api-Key", ttsConfig.apiKey)
    TtsAuthMode.LEGACY -> {
        addHeader("X-Api-App-Id", ttsConfig.appId)
        addHeader("X-Api-Access-Key", ttsConfig.accessKey)
    }
    TtsAuthMode.NONE -> throw IllegalArgumentException(ttsConfig.validationError()!!)
}
addHeader("X-Api-Resource-Id", voice.resourceId)
addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
```

Validate before `client.newCall`:

```kotlin
require(text.isNotBlank()) { "没有可朗读的文本" }
require(voice.isComplete) { voice.validationError("当前语言") ?: "请配置音色与 Resource ID" }
```

Keep `namespace = "BidirectionalTTS"`, `format = "mp3"`, `sample_rate = 24000`, and JSON-string additions. Replace error detection with:

```kotlin
when (code) {
    null, 0, 20000000 -> Unit
    else -> errorInfo = obj
}
```

If a non-success code is observed, do not return audio silently; after consuming the body throw `Exception("火山引擎错误 $code: $message${logIdSuffix}")`. Read `X-Tt-Logid` from the response only for the exception message; never include headers containing credentials. Decode only bare JSON lines; skip blank lines; a `data:` prefix must fail as unsupported response format rather than be treated as a successful empty response.

In `AppContainer`, make a dedicated finite client:

```kotlin
private val ttsHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(120, TimeUnit.SECONDS)
    .build()

val ttsClient: VolcTtsClient by lazy { VolcTtsClient(AppConfig.TTS_DIRECT_URL, ttsHttpClient) }
```

- [ ] **Step 4: Run unit tests and Kotlin compilation**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:testDebugUnitTest --tests "com.rhodesisland.terminal.tts.VolcTtsClientTest" --rerun-tasks --no-build-cache
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache
```

Expected: both PASS.

- [ ] **Step 5: Commit direct transport**

```bash
git add app/src/main/java/com/rhodesisland/terminal/tts/VolcTtsClient.kt app/src/main/java/com/rhodesisland/terminal/AppContainer.kt app/src/test/java/com/rhodesisland/terminal/tts/VolcTtsClientTest.kt
git commit -m "feat: call Volcano TTS directly" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 4: Select per-language bindings and preserve playback semantics

**Files:**
- Modify: `app/src/main/java/com/rhodesisland/terminal/manager/TtsManager.kt:52-118,150-198`
- Modify: `app/src/main/java/com/rhodesisland/terminal/ui/chat/ChatViewModel.kt:1084-1151` only if it assumes `VoicePair` contains strings.
- Test: `app/src/test/java/com/rhodesisland/terminal/tts/VolcTtsClientTest.kt` and existing TTS-related tests.

**Interfaces:**
- Consumes: `VoicePair` and `VoiceConfig` from Task 1; direct `VolcTtsClient.synthesize` from Task 3.
- Produces: cloud TTS receives the selected Chinese or Japanese `VoiceConfig` and fails locally when mapping is incomplete.

- [ ] **Step 1: Add a failing selection test through a pure helper**

Extract a top-level internal helper in `TtsManager.kt`:

```kotlin
internal fun selectVoiceConfig(pair: VoicePair?, language: TtsLanguage): VoiceConfig = when (language) {
    TtsLanguage.ZH -> pair?.zh ?: VoiceConfig()
    TtsLanguage.JA -> pair?.ja ?: VoiceConfig()
}
```

Test it in `VoiceConfigTest.kt`:

```kotlin
@Test fun selectVoiceConfig_usesLanguageSpecificBinding() {
    val pair = VoicePair(VoiceConfig("S_cn", "seed-icl-2.0"), VoiceConfig("S_ja", "seed-icl-1.0"))
    assertEquals(VoiceConfig("S_cn", "seed-icl-2.0"), selectVoiceConfig(pair, TtsLanguage.ZH))
    assertEquals(VoiceConfig("S_ja", "seed-icl-1.0"), selectVoiceConfig(pair, TtsLanguage.JA))
}
```

- [ ] **Step 2: Run the test to prove selection uses old string fields**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:testDebugUnitTest --tests "com.rhodesisland.terminal.data.model.VoiceConfigTest" --rerun-tasks --no-build-cache
```

Expected: compilation failure until the helper and updated model use are added.

- [ ] **Step 3: Update cloud TTS binding selection**

Replace string extraction in `speakCloud` with:

```kotlin
val voice = selectVoiceConfig(settings.getTtsVoiceMapNow()[characterId], language)
if (!voice.isComplete) {
    throw Exception(
        voice.validationError(if (language == TtsLanguage.ZH) "中文" else "日文")
            ?: "请先在设置页为该角色配置${language.label}音色与 Resource ID",
    )
}
val audioBytes = client.synthesize(cleanText, characterId, ttsConfig, voice)
```

Replace the hard-coded timeout fallback with `AppConfig.TTS_DEFAULT_VOLUME`:

```kotlin
val volume = withTimeoutOrNull(5000) { settings.ttsVolume.first() }
    ?: AppConfig.TTS_DEFAULT_VOLUME
```

Do not create arbitrary default `S_` voices: an unmapped cloud voice must fail with configuration guidance rather than send an unverifiable resource pairing.

- [ ] **Step 4: Run focused unit compilation and existing TTS tests**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:testDebugUnitTest --tests "com.rhodesisland.terminal.data.model.VoiceConfigTest" --tests "com.rhodesisland.terminal.tts.SystemVoiceTemplateTest" --rerun-tasks --no-build-cache
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache
```

Expected: PASS.

- [ ] **Step 5: Commit binding selection**

```bash
git add app/src/main/java/com/rhodesisland/terminal/manager/TtsManager.kt app/src/main/java/com/rhodesisland/terminal/ui/chat/ChatViewModel.kt app/src/test/java/com/rhodesisland/terminal/data/model/VoiceConfigTest.kt
git commit -m "feat: select TTS resource per language" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 5: Make cloud TTS credentials and bindings configurable in settings

**Files:**
- Modify: `app/src/main/java/com/rhodesisland/terminal/ui/settings/SettingsScreen.kt:120-139,289-415,540-590`
- Modify: `app/src/main/java/com/rhodesisland/terminal/ui/settings/GuideDialog.kt:130-150`
- Test: `app/src/androidTest/java/com/rhodesisland/terminal/data/local/TtsSettingsStoreTest.kt`

**Interfaces:**
- Consumes: `TtsConfig`, `VoiceConfig`, `VoicePair`, validation functions from Task 1.
- Produces: saved settings that preserve API Key, App ID, Access Key, and rich per-language bindings.

- [ ] **Step 1: Add a failing configuration preservation test**

```kotlin
@Test fun updatingApiKey_preservesLegacyFieldsUntilUserChangesThem() = runTest {
    store.setTtsConfig(TtsConfig(apiKey = "old", appId = "app", accessKey = "access"))
    store.setTtsConfig(TtsConfig(apiKey = "new", appId = "app", accessKey = "access"))
    assertEquals(TtsConfig(apiKey = "new", appId = "app", accessKey = "access"), store.ttsConfig.first())
}
```

- [ ] **Step 2: Run the instrumentation test**

Run the same `connectedDebugAndroidTest` command from Task 2.

Expected: initially PASS at store level but exposes that the UI has no model state for legacy fields; use it as a regression guard before wiring UI.

- [ ] **Step 3: Add settings state, inputs, and validation**

At the top of `SettingsScreen`, maintain all saved fields:

```kotlin
var ttsApiKey by remember(ttsConfig) { mutableStateOf(ttsConfig.apiKey) }
var ttsAppId by remember(ttsConfig) { mutableStateOf(ttsConfig.appId) }
var ttsAccessKey by remember(ttsConfig) { mutableStateOf(ttsConfig.accessKey) }
```

Under the cloud engine section, show API Key plus optional legacy fields using `PasswordField`:

```kotlin
PasswordField("API Key（推荐）", ttsApiKey, showTtsKey, { ttsApiKey = it }, { showTtsKey = !showTtsKey })
PasswordField("App ID（旧版，可选）", ttsAppId, showTtsAppId, { ttsAppId = it }, { showTtsAppId = !showTtsAppId })
PasswordField("Access Key（旧版，可选）", ttsAccessKey, showTtsAccessKey, { ttsAccessKey = it }, { showTtsAccessKey = !showTtsAccessKey })
Text("优先使用 API Key；如不填 API Key，则 App ID 与 Access Key 必须同时填写。", ...)
```

For each language in `VoiceField`, replace its single string field with two fields bound to `VoiceConfig.voiceId` and `.resourceId`; label them `中·音色` / `中·资源` and `日·音色` / `日·资源`. Build updated pairs only with `copy`:

```kotlin
voiceEdit = voiceEdit + (id to current.copy(zh = current.zh.copy(voiceId = value)))
```

On save, validate every pair and preserve all credentials:

```kotlin
val newConfig = TtsConfig(ttsApiKey.trim(), ttsAppId.trim(), ttsAccessKey.trim())
val credentialError = newConfig.validationError()
val voiceError = voiceEdit.values.firstNotNullOfOrNull { it.zh.validationError("中文") ?: it.ja.validationError("日文") }
if (credentialError != null || voiceError != null) {
    ttsPreviewError = credentialError ?: voiceError
    return@launch
}
container.settingsRepository.setTtsConfig(newConfig)
container.settingsRepository.setTtsVoiceMap(voiceEdit.filterValues { !it.zh.isEmpty || !it.ja.isEmpty })
```

For preview with cloud engine, require a selected real character binding; do not invoke `speak(..., "")`, which has no binding. Add a compact TTS preview character dropdown using `voiceCharacters` and pass the selected id.

Update guidance to say direct calls go to Volcano Engine; each `S_xxx` must be paired with its console-displayed Resource ID; do not mention CloudRun/CloudBase.

- [ ] **Step 4: Run compilation and settings persistence tests**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:connectedDebugAndroidTest --instrumentation-arg class=com.rhodesisland.terminal.data.local.TtsSettingsStoreTest
```

Expected: Kotlin compilation PASS; instrumentation PASS with a connected device/emulator, otherwise record the environmental blocker.

- [ ] **Step 5: Commit settings integration**

```bash
git add app/src/main/java/com/rhodesisland/terminal/ui/settings/SettingsScreen.kt app/src/main/java/com/rhodesisland/terminal/ui/settings/GuideDialog.kt app/src/androidTest/java/com/rhodesisland/terminal/data/local/TtsSettingsStoreTest.kt
git commit -m "feat: configure TTS credentials and resources" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 6: Verify end-to-end build and regression surface

**Files:**
- Modify only if verification identifies a compiler error or a proven TTS regression in a changed file.
- Test: `app/src/test/java/com/rhodesisland/terminal/tts/VolcTtsClientTest.kt`
- Test: `app/src/test/java/com/rhodesisland/terminal/data/model/VoiceConfigTest.kt`
- Test: `app/src/test/java/com/rhodesisland/terminal/tts/SystemVoiceTemplateTest.kt`
- Test: `app/src/androidTest/java/com/rhodesisland/terminal/data/local/TtsSettingsStoreTest.kt`

**Interfaces:**
- Consumes: completed Tasks 1–5.
- Produces: verified direct TTS implementation without a real user credential.

- [ ] **Step 1: Run all TTS-related JVM tests from a clean task graph**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:testDebugUnitTest --tests "com.rhodesisland.terminal.tts.*" --tests "com.rhodesisland.terminal.data.model.VoiceConfigTest" --rerun-tasks --no-build-cache
```

Expected: PASS.

- [ ] **Step 2: Build the debug APK**

Run:

```bash
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:assembleDebug --rerun-tasks --no-build-cache
```

Expected: `BUILD SUCCESSFUL` and an APK under `app/build/outputs/apk/debug/`.

- [ ] **Step 3: Run instrumentation coverage when a device is available**

Run:

```bash
C:/Users/Lfq06/AppData/Local/Android/Sdk/platform-tools/adb.exe devices
JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8' ./gradlew :app:connectedDebugAndroidTest --instrumentation-arg class=com.rhodesisland.terminal.data.local.TtsSettingsStoreTest
```

Expected: a device is listed and the test PASSes. If no device is listed, do not fabricate a pass; report the skipped device-only verification.

- [ ] **Step 4: Inspect the final diff for credential exposure and obsolete proxy references**

Run:

```bash
git diff master...HEAD -- app/src/main/java | git diff --check
git grep -n -i -E 'TTS_PROXY_URL|CloudBase.*TTS|CloudRun.*TTS' -- app/src/main || true
git status --short
```

Expected: no whitespace errors, no active TTS proxy URL/reference, no API key literal, and a clean worktree.

- [ ] **Step 5: Commit any final verified correction**

If and only if Step 1–4 required a source correction:

```bash
git add <corrected-files>
git commit -m "fix: finalize direct TTS verification" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

If no correction was needed, do not create an empty commit.

## Self-Review

- Spec coverage: Tasks 1–5 implement direct official endpoint, dual auth, per-language resource bindings, old persistence compatibility, official code parsing, settings UX, user guidance, and playback handoff. Task 6 verifies tests/build/no credential exposure.
- Scope: CloudBase deployment and SSE support are explicitly excluded; no unrelated chat/video/local-model work appears.
- Type consistency: Task 1 defines `VoiceConfig`, `VoicePair`, `TtsAuthMode`; Tasks 2–5 consume the same exact names. Task 3 defines the updated `VolcTtsClient.synthesize` signature used by Task 4.
- Known environmental caveat: instrumentation requires a connected device/emulator; JVM and compile verification remain mandatory regardless.
