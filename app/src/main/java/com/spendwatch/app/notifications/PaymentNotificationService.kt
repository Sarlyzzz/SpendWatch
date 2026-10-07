package com.spendwatch.app.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.spendwatch.app.SpendWatchApplication
import com.spendwatch.app.data.RecordKind
import com.spendwatch.app.importer.StatementParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class NotificationScanResult(val message: String, val inspected: Int = 0, val supported: Int = 0)

class PaymentNotificationService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        @Volatile private var active: PaymentNotificationService? = null

        fun isConnected(): Boolean = active != null

        /** Recheck notifications still present in the shade; dismissed ones cannot be recovered. */
        fun scanActive(context: Context): NotificationScanResult {
            val listener = active
            val result = when {
                !AppCatalog.notificationAccess(context) -> NotificationScanResult("通知访问未授权，请先在系统设置开启")
                listener != null -> listener.scanActiveInternal()
                else -> try {
                    requestRebind(ComponentName(context, PaymentNotificationService::class.java))
                    NotificationScanResult("通知访问已授权，但监听未连接；已请求系统重新连接")
                } catch (_: Exception) {
                    NotificationScanResult("通知访问已授权，但监听未连接；请在系统设置重新开启通知访问")
                }
            }
            MonitoringDiagnostics.record(context, "手动检查", result.message)
            return result
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        active = this
        MonitoringDiagnostics.setConnected(this, true)
        scanActiveInternal()
    }

    override fun onListenerDisconnected() {
        if (active === this) active = null
        MonitoringDiagnostics.setConnected(this, false)
        super.onListenerDisconnected()
    }

    private fun scanActiveInternal(): NotificationScanResult = try {
        val notifications = activeNotifications.orEmpty()
        notifications.filter { it.packageName == packageName && it.id == NotificationProbe.ID }
            .forEach { NotificationProbe.receive(this, it.notification) }
        val supported = notifications.filter { item -> AppCatalog.apps.any { it.packageName == item.packageName } }
        val groupsWithChildren = supported.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .map { it.groupKey }.toSet()
        supported.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 ||
            it.groupKey !in groupsWithChildren }.forEach(::safelyHandleNotification)
        val alipayTotal = supported.count { it.packageName == "com.eg.android.AlipayGphone" }
        val alipay = supported.count { it.packageName == "com.eg.android.AlipayGphone" &&
            it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        val possibleAlipayPackage = if (alipayTotal == 0) notifications.firstOrNull {
            it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.contains("支付宝") == true
        }?.packageName else null
        val detail = possibleAlipayPackage?.let { "；支付宝标题来自 $it" }.orEmpty()
        NotificationScanResult("已检查 ${notifications.size} 条当前通知；支持的来源 ${supported.size} 条，支付宝 $alipayTotal 条（独立 $alipay 条）$detail",
            notifications.size, supported.size)
    } catch (_: Exception) { NotificationScanResult("监听已连接，但读取当前通知失败") }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName && sbn.id == NotificationProbe.ID) {
            NotificationProbe.receive(this, sbn.notification)
            return
        }
        if (AppCatalog.apps.none { it.packageName == sbn.packageName }) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            scope.launch {
                delay(600)
                val hasChildren = runCatching { activeNotifications.orEmpty().any {
                    it.groupKey == sbn.groupKey && it.key != sbn.key &&
                        it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
                } }.getOrDefault(false)
                if (!hasChildren) safelyHandleNotification(sbn)
            }
            return
        }
        safelyHandleNotification(sbn)
    }

    private fun safelyHandleNotification(sbn: StatusBarNotification) {
        try { handleNotification(sbn) } catch (_: Exception) {
            MonitoringDiagnostics.record(this, sbn.packageName, "收到通知，但读取内容失败")
        }
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        val app = AppCatalog.apps.firstOrNull { it.packageName == sbn.packageName } ?: return
        if (!AppCatalog.enabled(this, app)) {
            MonitoringDiagnostics.record(this, app.packageName, "收到通知，但此来源的监控开关未开启")
            return
        }
        if (!app.notificationReady) {
            MonitoringDiagnostics.record(this, app.packageName, "收到通知，此来源使用账单导入")
            return
        }
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val bodies = listOfNotNull(
            sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            sbn.notification.tickerText?.toString(),
        ).filter(String::isNotBlank).distinct()
        val lines = sbn.notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map(CharSequence::toString)?.filter(String::isNotBlank).orEmpty()
        fun parseText(text: String) = PaymentTextParser.parse(app.name, text,
            "notification:${sbn.packageName}:${sbn.key}:${StatementParser.sha(text)}", sbn.postTime)
        val fromLines = lines.mapNotNull {
            parseText(if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0) "$title $it" else it)
        }.distinctBy { it.sourceKey }
        val records = if (fromLines.isNotEmpty()) fromLines else listOfNotNull(
            (bodies.ifEmpty { listOf("") }).firstNotNullOfOrNull { parseText("$title $it".trim()) }
        )
        if (records.isEmpty()) {
            val text = (lines + bodies.map { "$title $it" }).joinToString(" ").ifBlank { title }
            val reason = when {
                text.isBlank() -> "通知无可读文字"
                text.contains("有一条微信消息") -> "通知内容已隐藏，请导入账单"
                app.name == "招商银行" && !PaymentTextParser.hasAmount(text) &&
                    (text.contains("扣款") || text.contains("支出")) ->
                    "招行通知已送达，但正文没有金额；请导入招行流水，转账需人工核对"
                !PaymentTextParser.hasAmount(text) -> "通知未提供可识别的金额"
                else -> "通知未包含已适配的付款或退款格式"
            }
            MonitoringDiagnostics.record(this, app.packageName, reason)
            return
        }
        scope.launch {
            val outcome = try {
                val accepted = records.count { (application as SpendWatchApplication).repository.ingest(it) }
                if (accepted > 0) {
                    if (records.any { it.kind == RecordKind.REFUND }) "已识别 $accepted 条，退款请在待核对页关联"
                    else "已识别 $accepted 条交易，等待账单核对"
                } else "这些通知已记录过"
            } catch (_: Exception) { "识别成功，但保存交易失败" }
            MonitoringDiagnostics.record(this@PaymentNotificationService, app.packageName, outcome)
        }
    }

    override fun onDestroy() {
        if (active === this) active = null
        MonitoringDiagnostics.setConnected(this, false)
        scope.cancel()
        super.onDestroy()
    }
}
