package com.rhodesisland.terminal.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhodesisland.terminal.data.local.SettingsStore
import com.rhodesisland.terminal.data.model.SeedanceConfig
import com.rhodesisland.terminal.data.model.SeedanceModelVariant
import com.rhodesisland.terminal.data.model.SeedanceRatio
import com.rhodesisland.terminal.data.model.SeedanceResolution
import com.rhodesisland.terminal.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Seedance 设置 DataStore 往返 / 默认值仪器测试（Task 3，仅 CI/真机执行）。
 *
 * 使用独立测试文件 `filesDir/datastore/seedance_settings_test.preferences_pb`，
 * 不触碰用户真实设置的 `rhodes_settings`。覆盖：全新文件默认值、全字段往返、
 * 可空背景路径移除键、未知枚举存储键经 fromStorageKey 保守回落、时长越界回落默认值。
 */
@RunWith(AndroidJUnit4::class)
class SeedanceSettingsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SettingsStore
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "datastore/seedance_settings_test.preferences_pb")
        file.parentFile?.mkdirs()
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store = SettingsStore(context, dataStore)
        repository = SettingsRepository(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun freshStore_returnsAllDefaults() = runBlocking {
        val config = repository.getSeedanceConfigNow()
        assertEquals(SeedanceConfig(), config)
        // generateAudio 固定 true，无存储键
        assertTrue(config.generateAudio)
        assertEquals(SeedanceModelVariant.STANDARD, config.variant)
        assertEquals(SeedanceResolution.P720, config.resolution)
        assertEquals(SeedanceRatio.PORTRAIT, config.ratio)
        assertEquals(5, config.durationSeconds)
        assertTrue(!config.watermark)
        assertNull(config.backgroundImagePath)
        assertEquals("", config.sceneDescription)
        assertEquals("", config.apiKey)
    }

    @Test
    fun setSeedanceConfig_roundTripsAllFields() = runBlocking {
        val config = SeedanceConfig(
            baseUrl = "https://example.com/api/v3",
            apiKey = "sk-seedance-test",
            variant = SeedanceModelVariant.FAST,
            resolution = SeedanceResolution.P480,
            ratio = SeedanceRatio.LANDSCAPE,
            durationSeconds = 12,
            watermark = true,
            backgroundImagePath = "/data/user/0/com.rhodesisland.terminal/files/seedance_scene/background.jpg",
            sceneDescription = "雨夜的街道",
        )
        repository.setSeedanceConfig(config)
        assertEquals(config, repository.getSeedanceConfigNow())
        assertEquals(config, repository.seedanceConfig.first())
    }

    @Test
    fun backgroundImagePath_nullRemovesStoredKey() = runBlocking {
        repository.setSeedanceConfig(SeedanceConfig(backgroundImagePath = "/data/x/background.jpg"))
        assertTrue(repository.getSeedanceConfigNow().backgroundImagePath != null)

        repository.setSeedanceConfig(SeedanceConfig())
        assertNull(repository.getSeedanceConfigNow().backgroundImagePath)
        assertNull(repository.seedanceConfig.first().backgroundImagePath)
    }

    @Test
    fun unknownStoredEnumValues_fallBackToDefaults() = runBlocking {
        dataStore.edit { p ->
            p[stringPreferencesKey("seedance_variant")] = "doubao-seedance-unknown"
            p[stringPreferencesKey("seedance_resolution")] = "P16K"
            p[stringPreferencesKey("seedance_ratio")] = "32:9"
        }
        val config = repository.getSeedanceConfigNow()
        assertEquals(SeedanceModelVariant.STANDARD, config.variant)
        assertEquals(SeedanceResolution.P720, config.resolution)
        assertEquals(SeedanceRatio.PORTRAIT, config.ratio)
    }

    @Test
    fun durationOutOfRange_fallsBackToDefault() = runBlocking {
        dataStore.edit { p -> p[intPreferencesKey("seedance_duration")] = 3 }
        assertEquals(5, repository.getSeedanceConfigNow().durationSeconds)

        dataStore.edit { p -> p[intPreferencesKey("seedance_duration")] = 16 }
        assertEquals(5, repository.getSeedanceConfigNow().durationSeconds)

        dataStore.edit { p -> p[intPreferencesKey("seedance_duration")] = 8 }
        assertEquals(8, repository.getSeedanceConfigNow().durationSeconds)
    }
}
