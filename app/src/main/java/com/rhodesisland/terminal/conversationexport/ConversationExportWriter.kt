package com.rhodesisland.terminal.conversationexport

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConversationExportWriter(private val context: Context) {

    suspend fun writeText(destination: Uri, text: String): Result<Unit> =
        writeBytes(destination, text.encodeToByteArray())

    suspend fun writePng(destination: Uri, bytes: ByteArray): Result<Unit> = writeBytes(destination, bytes)

    suspend fun writePngPages(treeUri: Uri, baseName: String, pages: List<ByteArray>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val directory = DocumentFile.fromTreeUri(context, treeUri)
                    ?: error("无法访问所选目录")
                val created = mutableListOf<DocumentFile>()
                try {
                    pages.forEachIndexed { index, bytes ->
                        val file = directory.createFile("image/png", "${baseName}_${(index + 1).toString().padStart(2, '0')}.png")
                            ?: error("无法在所选目录创建图片文件")
                        created += file
                        context.contentResolver.openOutputStream(file.uri, "w")?.use { it.write(bytes) }
                            ?: error("无法写入图片文件")
                    }
                    pages.size
                } catch (error: Exception) {
                    created.forEach { runCatching { it.delete() } }
                    throw error
                }
            }
        }

    private suspend fun writeBytes(destination: Uri, bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(destination, "w")?.use { it.write(bytes) }
                ?: error("无法写入所选文件")
        }
    }
}
