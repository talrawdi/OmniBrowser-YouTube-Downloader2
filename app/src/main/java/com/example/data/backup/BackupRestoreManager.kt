package com.example.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.diagnostics.DiagnosticLogger
import com.example.data.local.AppDatabase
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.HistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object BackupRestoreManager {
    private const val DEFAULT_ENCRYPTION_KEY = "OmniBrowserSecureBackupMasterKey" // 32 bytes

    suspend fun createBackupFile(context: Context, customPassword: String? = null): File? = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val bookmarks = db.bookmarkDao().getAllBookmarks().first()
            val history = db.historyDao().getRecentHistory().first()

            val rootJson = JSONObject().apply {
                put("app", "OmniBrowser")
                put("version", 1)
                put("createdAt", System.currentTimeMillis())

                val bArray = JSONArray()
                bookmarks.forEach { b ->
                    bArray.put(JSONObject().apply {
                        put("title", b.title)
                        put("url", b.url)
                        put("category", b.category)
                        put("createdAt", b.createdAt)
                    })
                }
                put("bookmarks", bArray)

                val hArray = JSONArray()
                history.forEach { h ->
                    hArray.put(JSONObject().apply {
                        put("title", h.title)
                        put("url", h.url)
                        put("visitTime", h.visitTime)
                        put("visitCount", h.visitCount)
                    })
                }
                put("history", hArray)
            }

            val plainBytes = rootJson.toString().toByteArray(Charsets.UTF_8)
            val encryptedBytes = encryptData(plainBytes, customPassword ?: DEFAULT_ENCRYPTION_KEY)

            val dir = File(context.filesDir, "backups").apply { mkdirs() }
            val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "omnibrowser_backup_$timeStr.obak")
            file.writeBytes(encryptedBytes)

            DiagnosticLogger.s("Backup", "تم إنشاء نسخة احتياطية مشفرة بنجاح: ${file.name} (${bookmarks.size} إشارة مرجعية، ${history.size} سجل)")
            file
        } catch (e: Exception) {
            DiagnosticLogger.e("Backup", "فشل إنشاء النسخة الاحتياطية: ${e.message}", e)
            null
        }
    }

    suspend fun restoreFromFile(context: Context, fileUri: Uri, customPassword: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(fileUri) ?: return@withContext false
            val encryptedBytes = inputStream.readBytes()
            inputStream.close()

            val decryptedBytes = decryptData(encryptedBytes, customPassword ?: DEFAULT_ENCRYPTION_KEY)
            val jsonString = String(decryptedBytes, Charsets.UTF_8)
            val root = JSONObject(jsonString)

            val db = AppDatabase.getInstance(context)

            // Restore bookmarks
            val bArray = root.optJSONArray("bookmarks")
            if (bArray != null) {
                for (i in 0 until bArray.length()) {
                    val item = bArray.getJSONObject(i)
                    db.bookmarkDao().insertBookmark(
                        BookmarkEntity(
                            title = item.optString("title", "بدون عنوان"),
                            url = item.optString("url", ""),
                            category = item.optString("category", "عام"),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            }

            // Restore history
            val hArray = root.optJSONArray("history")
            if (hArray != null) {
                for (i in 0 until hArray.length()) {
                    val item = hArray.getJSONObject(i)
                    db.historyDao().insertHistory(
                        HistoryEntity(
                            title = item.optString("title", ""),
                            url = item.optString("url", ""),
                            visitTime = item.optLong("visitTime", System.currentTimeMillis()),
                            visitCount = item.optInt("visitCount", 1)
                        )
                    )
                }
            }

            DiagnosticLogger.s("Restore", "تم استعادة البيانات والنسخة الاحتياطية بنجاح!")
            true
        } catch (e: Exception) {
            DiagnosticLogger.e("Restore", "فشل استعادة البيانات من النسخة المحددة: ${e.message}", e)
            false
        }
    }

    fun shareBackupFile(context: Context, backupFile: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", backupFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "نسخة احتياطية مشفرة - OmniBrowser")
                putExtra(Intent.EXTRA_TEXT, "نسخة احتياطية مشفرة لبيانات متصفح OmniBrowser")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "مشاركة النسخة الاحتياطية"))
        } catch (e: Exception) {
            DiagnosticLogger.e("Backup", "فشل مشاركة ملف النسخ الاحتياطي: ${e.message}", e)
        }
    }

    private fun encryptData(data: ByteArray, password: String): ByteArray {
        val keyBytes = password.padEnd(32, '0').take(32).toByteArray(Charsets.UTF_8)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val iv = ByteArray(16) { 0x4F } // Fixed initialization vector for backup compatibility
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(data)
    }

    private fun decryptData(data: ByteArray, password: String): ByteArray {
        val keyBytes = password.padEnd(32, '0').take(32).toByteArray(Charsets.UTF_8)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val iv = ByteArray(16) { 0x4F }
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(data)
    }
}
