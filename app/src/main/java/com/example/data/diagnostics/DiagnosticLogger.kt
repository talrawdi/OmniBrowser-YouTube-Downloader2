package com.example.data.diagnostics

import android.os.Build
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val details: String? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR, SUCCESS
}

object DiagnosticLogger {
    private const val MAX_LOGS = 500
    private val logsQueue = ConcurrentLinkedDeque<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    init {
        log(LogLevel.INFO, "System", "OmniBrowser Core Initialized - Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    }

    fun log(level: LogLevel, tag: String, message: String, details: String? = null) {
        val entry = LogEntry(
            level = level,
            tag = tag,
            message = message,
            details = details
        )
        logsQueue.addFirst(entry)
        while (logsQueue.size > MAX_LOGS) {
            logsQueue.pollLast()
        }
        _logsFlow.value = logsQueue.toList()
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, throwable?.stackTraceToString())
    fun s(tag: String, message: String) = log(LogLevel.SUCCESS, tag, message)

    fun clear() {
        logsQueue.clear()
        _logsFlow.value = emptyList()
        i("System", "تم مسح سجلات التشخيص بنجاح.")
    }

    fun getSystemDiagnosticReport(): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sb.appendLine("=== OmniBrowser Diagnostic Report ===")
        sb.appendLine("Date: ${sdf.format(Date())}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            sb.appendLine("Available Storage: ${availableBytes / (1024 * 1024)} MB / ${totalBytes / (1024 * 1024)} MB")
        } catch (_: Exception) {}

        sb.appendLine("\n--- Recent Logs (${logsQueue.size}) ---")
        logsQueue.toList().reversed().forEach { log ->
            sb.appendLine("[${log.formattedTime}] [${log.level}] [${log.tag}] ${log.message}")
            if (!log.details.isNullOrBlank()) {
                sb.appendLine("  Details: ${log.details.take(200)}")
            }
        }
        return sb.toString()
    }
}
