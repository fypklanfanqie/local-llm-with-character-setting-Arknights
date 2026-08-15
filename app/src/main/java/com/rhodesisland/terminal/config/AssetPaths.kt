package com.rhodesisland.terminal.config

/**
 * 资源路径配置。
 *
 * 角色立绘已恢复为 AI 生成的原创立绘（assets/characters/ 下的 20 张 WebP）：
 * - 头像优先展示立绘；缺失时 UI 层兜底为 monogram 渐变头像。
 * - 音乐走网易云搜索 + 本地导入（见 NeteaseApiService / MusicScreen）。
 * - 聊天背景走 MeshBackground 渐变 + 用户自定义图片。
 */
object AssetPaths {

    /** 角色立绘路径（通讯页 / 聊天页）。 */
    val PICTURES: Map<String, String> = mapOf(
        "neighbor" to "characters/neighbor.webp",
        "tsundere" to "characters/tsundere.webp",
        "senpai" to "characters/senpai.webp",
        "yandere" to "characters/yandere.webp",
        "kouhai" to "characters/kouhai.webp",
        "mature" to "characters/mature.webp",
        "ceo-f" to "characters/ceo-f.webp",
        "sister" to "characters/sister.webp",
        "sharp" to "characters/sharp.webp",
        "scholar-f" to "characters/scholar-f.webp",
        "fox" to "characters/fox.webp",
        "butler" to "characters/butler.webp",
        "ceo-m" to "characters/ceo-m.webp",
        "bookman" to "characters/bookman.webp",
        "knight" to "characters/knight.webp",
        "villain" to "characters/villain.webp",
        "grumpy" to "characters/grumpy.webp",
        "chuuni" to "characters/chuuni.webp",
        "teacher" to "characters/teacher.webp",
        "bamboo" to "characters/bamboo.webp",
        "baiyue" to "characters/baiyue.webp",
        "idol" to "characters/idol.webp",
        "actress" to "characters/actress.webp",
        "esport-f" to "characters/esport-f.webp",
        "hacker" to "characters/hacker.webp",
        "cop-f" to "characters/cop-f.webp",
        "doctor-f" to "characters/doctor-f.webp",
        "teacher-f" to "characters/teacher-f.webp",
        "fortune" to "characters/fortune.webp",
        "catgirl" to "characters/catgirl.webp",
        "vampire" to "characters/vampire.webp",
        "saint" to "characters/saint.webp",
        "artgirl" to "characters/artgirl.webp",
        "killa-f" to "characters/killa-f.webp",
        "vlogger" to "characters/vlogger.webp",
        "radio" to "characters/radio.webp",
        "junior-sis" to "characters/junior-sis.webp",
        "hkstar" to "characters/hkstar.webp",
        "senior-m" to "characters/senior-m.webp",
        "hitman" to "characters/hitman.webp",
        "coder" to "characters/coder.webp",
        "doctor-m" to "characters/doctor-m.webp",
        "soldier" to "characters/soldier.webp",
        "prince" to "characters/prince.webp",
        "immortal" to "characters/immortal.webp",
        "singer" to "characters/singer.webp",
        "esport-m" to "characters/esport-m.webp",
        "brat" to "characters/brat.webp",
        "bigbro" to "characters/bigbro.webp",
        "detective" to "characters/detective.webp",
    )

    /** 角色选择页立绘路径（复用通讯页立绘）。 */
    val SELECTION_PICTURES: Map<String, String> = PICTURES

    /** 角色语音文件路径（已清空）。 */
    val VOICES: Map<String, String> = emptyMap()

    /** BGM 播放列表（已清空，音乐改走网易云搜索 + 本地导入）。 */
    val BGM: List<BgmTrack> = emptyList()

    /** 背景轮播图（已清空，改用 MeshBackground + 用户自定义）。 */
    val BACKGROUNDS: List<BackgroundItem> = emptyList()
}

data class BgmTrack(
    val path: String,
    val name: String,
    val key: String,
)

data class BackgroundItem(
    val cloud: String,
)
