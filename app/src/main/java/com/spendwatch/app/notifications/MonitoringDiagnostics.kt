package com.spendwatch.app.notifications

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import android.os.Build
import com.spendwatch.app.BuildConfig

data class SourceDiagnostic(val at: Long, val outcome: String)
data class DiagnosticEvent(val at: Long, val source: String, val outcome: String)

/** Saves only processing results, never notification title, body, amount, or account data. */
object MonitoringDiagnostics {
    private const val FILE = "monitoring_diagnostics"
    val revision = MutableStateFlow(0)

    fun setConnected(context: Context, connected: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("connected", connected).apply()
        record(context, "监听服务", if (connected) "系统已连接监听服务" else "系统已断开监听服务")
    }

    fun connected(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getBoolean("connected", false)

    @Synchronized fun record(context: Context, packageName: String, outcome: String) {
        val now = System.currentTimeMillis()
        val entries = (listOf(DiagnosticEvent(now, packageName, outcome)) + history(context)).take(30)
        val json = JSONArray()
        entries.forEach { json.put(JSONObject().put("at", it.at).put("source", it.source).put("outcome", it.outcome)) }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong("$packageName.at", now)
            .putString("$packageName.outcome", outcome).putString("history", json.toString()).apply()
        revision.update { it + 1 }
    }

    fun history(context: Context): List<DiagnosticEvent> = runCatching {
        val array = JSONArray(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("history", "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { row ->
            DiagnosticEvent(row.getLong("at"), row.getString("source"), row.getString("outcome"))
        } }
    }.getOrDefault(emptyList())

    fun report(context: Context): String = buildString {
        appendLine("月度花销 ${BuildConfig.VERSION_NAME}")
        appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}；Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        appendLine("通知访问：${AppCatalog.notificationAccess(context)}；监听连接：${PaymentNotificationService.isConnected()}")
        AppCatalog.apps.forEach { appendLine("${it.name}：安装=${AppCatalog.installed(context, it)}，开关=${AppCatalog.enabled(context, it)}") }
        val format = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Shanghai"))
        history(context).forEach { appendLine("${format.format(java.time.Instant.ofEpochMilli(it.at))} ${it.source}：${it.outcome}") }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
        revision.update { it + 1 }
    }

    fun source(context: Context, packageName: String): SourceDiagnostic? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val at = prefs.getLong("$packageName.at", 0)
        if (at == 0L) return null
        return SourceDiagnostic(at, prefs.getString("$packageName.outcome", "").orEmpty())
    }
}
