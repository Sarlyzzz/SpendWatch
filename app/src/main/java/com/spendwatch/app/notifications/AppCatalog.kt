package com.spendwatch.app.notifications

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

data class SupportedApp(val name: String, val packageName: String, val notificationReady: Boolean)

object AppCatalog {
    val apps = listOf(
        SupportedApp("支付宝", "com.eg.android.AlipayGphone", true),
        SupportedApp("微信", "com.tencent.mm", false),
        SupportedApp("淘宝", "com.taobao.taobao", true),
        SupportedApp("抖音", "com.ss.android.ugc.aweme", true),
        SupportedApp("招商银行", "cmb.pb", true),
        SupportedApp("中国银行", "com.chinamworld.bocmbci", true),
        SupportedApp("邮储银行", "com.yitong.mbank.psbc", true),
    )

    fun installed(context: Context, item: SupportedApp): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(
            item.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)
        ) else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(item.packageName, 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }

    fun notificationAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 27) {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.isNotificationListenerAccessGranted(ComponentName(context, PaymentNotificationService::class.java))
        }
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val target = ComponentName(context, PaymentNotificationService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    fun enabled(context: Context, item: SupportedApp): Boolean = context.getSharedPreferences("monitoring", Context.MODE_PRIVATE)
        .getBoolean(item.packageName, item.name == "支付宝")

    fun setEnabled(context: Context, item: SupportedApp, value: Boolean) {
        context.getSharedPreferences("monitoring", Context.MODE_PRIVATE).edit().putBoolean(item.packageName, value).apply()
    }
}
