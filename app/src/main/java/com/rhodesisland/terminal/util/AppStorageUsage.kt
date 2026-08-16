package com.rhodesisland.terminal.util

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 应用存储占用统计（设置「存储管理」）。
 *
 * [dirSize]/[formatBytes] 为纯 JVM 函数（可单测）；[computeItems] 用 Context 定位各分类目录。
 * 仅统计 filesDir/cacheDir/databases 下的一级分类，**不含模型文件**（模型在「模型」页管理）。
 */
object AppStorageUsage {

    /** 存储分类条目：名称、说明、目录（暗淡显示请清理体量）与占用字节数。 */
    data class StorageItem(
        val key: String,
        val name: String,
        val description: String,
        val sizeBytes: Long,
        val dir: File?,
    )

    /** 递归目录大小（不跟随符号链接；文件不存在的目录按 0）。 */
    fun dirSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        try {
            file.listFiles()?.forEach { child ->
                try {
                    if (child.isFile) total += child.length()
                    else if (child.isDirectory) total += dirSize(child)
                } catch (e: IOException) {
                    // 个别文件读取失败不影响统计
                }
            }
        } catch (e: Exception) {
            // listFiles 失败按 0
        }
        return total
    }

    /** 字节数 -> 人类可读（B/KB/MB/GB，一位小数）。 */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "$bytes B"
        else String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    /** 计算各分类占用。chatRecords/databases 目录兜底为单个数据库文件。 */
    fun computeItems(context: Context): List<StorageItem> {
        val filesDir = context.filesDir
        val dbFile = context.getDatabasePath("rhodes_chat.db")
        val dbDir = dbFile.parentFile ?: dbFile
        return listOf(
            StorageItem(
                key = "cache",
                name = "图片与临时缓存",
                description = "Coil 图片缓存、网络缓存等临时文件，可随时清除",
                sizeBytes = dirSize(context.cacheDir),
                dir = context.cacheDir,
            ),
            StorageItem(
                key = "videos",
                name = "Seedance 对话视频",
                description = "已生成并下载到本地的视频文件与任务快照",
                sizeBytes = dirSize(File(filesDir, "seedance/tasks")),
                dir = File(filesDir, "seedance/tasks"),
            ),
            StorageItem(
                key = "backgrounds",
                name = "聊天背景",
                description = "从相册导入的聊天背景图片",
                sizeBytes = dirSize(File(filesDir, "chat_backgrounds")),
                dir = File(filesDir, "chat_backgrounds"),
            ),
            StorageItem(
                key = "portraits",
                name = "自定义角色立绘",
                description = "自定义角色从相册导入的立绘图片",
                sizeBytes = dirSize(File(filesDir, "character_images")),
                dir = File(filesDir, "character_images"),
            ),
            StorageItem(
                key = "chatRecords",
                name = "聊天记录（数据库）",
                description = "全部单聊/群聊消息与 Seedance 任务记录",
                sizeBytes = dirSize(dbDir),
                dir = dbDir,
            ),
        )
    }
}