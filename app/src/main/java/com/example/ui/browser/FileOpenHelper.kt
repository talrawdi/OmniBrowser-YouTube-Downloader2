package com.example.ui.browser

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.local.entity.DownloadEntity
import java.io.File

fun openDownloadedFile(context: Context, item: DownloadEntity) {
    try {
        val uri = if (item.localPath.startsWith("content://")) {
            android.net.Uri.parse(item.localPath)
        } else {
            val file = File(item.localPath)
            if (!file.exists()) {
                Toast.makeText(context, "الملف غير موجود", Toast.LENGTH_SHORT).show()
                return
            }
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }
        val extension = item.fileName.substringAfterLast('.', "").lowercase()
        val type = item.mimeType.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        val size = if (item.localPath.startsWith("content://")) {
            runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: 0L }.getOrDefault(0L)
        } else File(item.localPath).length()
        if (size == 0L) {
            Toast.makeText(context, "الملف فارغ أو غير مكتمل", Toast.LENGTH_SHORT).show()
            return
        }
        val effectiveType = when {
            extension == "apk" || type.contains("android.package") -> "application/vnd.android.package-archive"
            extension == "srt" -> "application/x-subrip"
            extension == "vtt" -> "text/vtt"
            extension == "zip" -> "application/zip"
            extension == "rar" -> "application/vnd.rar"
            else -> type
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, effectiveType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "فتح الملف"))
    } catch (e: Exception) {
        val extension = item.fileName.substringAfterLast('.', "").lowercase()
        val message = if (extension == "apk") {
            "تعذر فتح مثبت APK. تحقق من السماح بالتثبيت من هذا المصدر في إعدادات Android."
        } else "لا يوجد تطبيق مناسب لفتح هذا الملف (${item.mimeType})"
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
