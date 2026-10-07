package com.spendwatch.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Local end-to-end listener check. It never creates a financial record. */
object NotificationProbe {
    const val ID = 90210
    private const val CHANNEL = "listener_check"
    private const val TOKEN = "spendwatch_probe"
    private fun prefs(context: Context) = context.getSharedPreferences("notification_probe", Context.MODE_PRIVATE)

    fun send(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return "本 App 的通知发送权限未开启，请先在系统应用设置中开启"
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "监听自检", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE)
            return "系统已关闭“监听自检”通知类别，请在应用通知设置中开启"
        val token = System.currentTimeMillis()
        prefs(context).edit().putLong("sent", token).putLong("received", 0).apply()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("月度花销监听自检")
            .setContentText("这是测试通知，不会计入消费。")
            .addExtras(android.os.Bundle().apply { putLong(TOKEN, token) }).build()
        MonitoringDiagnostics.record(context, "监听自检", "已发送测试通知，等待系统回调")
        manager.notify(ID, notification)
        return "测试通知已发送；约 5 秒后显示结果，不会计入消费"
    }

    fun receive(context: Context, notification: Notification) {
        val token = notification.extras.getLong(TOKEN, 0)
        if (token == 0L || token != prefs(context).getLong("sent", 0) || token == prefs(context).getLong("received", 0)) return
        prefs(context).edit().putLong("received", token).apply()
        MonitoringDiagnostics.record(context, "监听自检", "测试通过：系统通知已送达监听服务")
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun result(context: Context): String = when {
        prefs(context).getLong("sent", 0) == 0L -> "尚未发送测试通知"
        prefs(context).getLong("received", 0) == prefs(context).getLong("sent", 0) -> "测试通过：系统通知监听正常；若支付宝仍漏记，请查看支付宝的最近诊断"
        else -> "尚未收到测试回调：请检查通知访问授权及系统对本 App 的后台运行限制"
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
