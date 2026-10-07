package com.spendwatch.app.screen

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.spendwatch.app.SpendWatchApplication
import com.spendwatch.app.notifications.MonitoringDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Opt-in, on-device OCR for the visible WeChat transfer detail page. No image or raw OCR text is stored. */
class WechatScreenService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var lastAttemptAt = 0L
    private var polling = false
    private val candidateSeen = mutableMapOf<String, Int>()
    private val processedKeys = mutableSetOf<String>()
    private val poll = object : Runnable {
        override fun run() {
            if (!enabled(this@WechatScreenService) || rootInActiveWindow?.packageName?.toString() != "com.tencent.mm") {
                polling = false
                candidateSeen.clear()
                return
            }
            capture()
            handler.postDelayed(this, 4_000L)
        }
    }

    companion object {
        private const val PREFS = "wechat_screen_monitor"
        private const val SOURCE = "微信屏幕"

        fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("enabled", false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        }

        fun systemAccess(context: Context): Boolean {
            val target = ComponentName(context, WechatScreenService::class.java)
            val services = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return services.split(':').any { ComponentName.unflattenFromString(it) == target }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        MonitoringDiagnostics.record(this, SOURCE, "系统已连接微信屏幕识别服务")
        scope.launch {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("chat_ocr_cleanup_041_done", false)) {
                val removed = runCatching { (application as SpendWatchApplication).repository.removeUnreliableChatOcr() }.getOrNull()
                if (removed != null) {
                    prefs.edit().putBoolean("chat_ocr_cleanup_041_done", true).apply()
                    MonitoringDiagnostics.record(this@WechatScreenService, SOURCE, "已清理旧版会话识别记录 $removed 笔")
                }
            }
        }
        if (enabled(this) && !polling) {
            polling = true
            handler.postDelayed(poll, 1_500L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!enabled(this) || event?.packageName?.toString() != "com.tencent.mm") return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (!polling) {
            polling = true
            handler.postDelayed(poll, 1_500L)
        }
        val windowClass = event.className?.toString().orEmpty()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            MonitoringDiagnostics.record(this, SOURCE, "收到微信页面事件：${windowClass.takeLast(48)}")
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.postDelayed({ if (enabled(this) && rootInActiveWindow?.packageName?.toString() == "com.tencent.mm") capture() }, 700)
        } else {
            capture()
        }
    }

    private fun capture() {
        if (Build.VERSION.SDK_INT < 30 || busy || SystemClock.elapsedRealtime() - lastAttemptAt < 3_000L) return
        busy = true
        lastAttemptAt = SystemClock.elapsedRealtime()
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onFailure(errorCode: Int) {
                    busy = false
                    MonitoringDiagnostics.record(this@WechatScreenService, SOURCE, "屏幕读取失败（系统代码 $errorCode）")
                }

                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally { buffer.close() }
                    if (bitmap == null) { busy = false; return }
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { recognized ->
                            val lines = recognized.textBlocks.flatMap { it.lines }
                                .sortedWith(compareBy({ it.boundingBox?.top ?: Int.MAX_VALUE }, { it.boundingBox?.left ?: 0 }))
                                .map { it.text }
                            val chatRecords = WechatChatParser.parse(lines)
                            if (chatRecords.isNotEmpty()) {
                                val keys = chatRecords.map { it.sourceKey }.toSet()
                                candidateSeen.keys.retainAll(keys)
                                chatRecords.forEach { candidateSeen[it.sourceKey] = (candidateSeen[it.sourceKey] ?: 0) + 1 }
                                val stable = chatRecords.filter {
                                    (candidateSeen[it.sourceKey] ?: 0) >= 2 && processedKeys.add(it.sourceKey)
                                }
                                if (stable.isNotEmpty()) scope.launch {
                                    var saved = 0
                                    for (item in stable) {
                                        if (runCatching { (application as SpendWatchApplication).repository.ingest(item) }
                                                .getOrDefault(false)) saved++
                                    }
                                    MonitoringDiagnostics.record(this@WechatScreenService, SOURCE,
                                        "微信支付会话识别 ${stable.size} 张稳定卡片，新增 $saved 笔")
                                }
                            } else {
                                candidateSeen.clear()
                                if (recognized.text.contains("微信支付") && recognized.text.contains("账单详情")) {
                                    MonitoringDiagnostics.record(this@WechatScreenService, SOURCE,
                                        "已读取微信支付会话，卡片时间或金额未完整识别")
                                }
                            }
                            val record = WechatScreenParser.parse(lines)
                            if (record != null) {
                                scope.launch {
                                    val outcome = try {
                                        if ((application as SpendWatchApplication).repository.ingest(record))
                                            "识别到微信转账，已放入待核对"
                                        else "这笔微信转账已记录过"
                                    } catch (_: Exception) { "识别到微信转账，但保存失败" }
                                    MonitoringDiagnostics.record(this@WechatScreenService, SOURCE, outcome)
                                }
                            } else if (recognized.text.contains("转账单号")) {
                                MonitoringDiagnostics.record(this@WechatScreenService, SOURCE, "看到转账详情，但金额、状态、时间或单号未完整识别")
                            } else if (chatRecords.isEmpty()) {
                                val compact = lines.map { it.trim().replace('：', ':').replace(Regex("\\s+"), "") }
                                MonitoringDiagnostics.record(this@WechatScreenService, SOURCE,
                                    "OCR ${recognized.text.length} 字；标题=${compact.take(10).contains("微信支付")}，时间=${compact.count { Regex("\\d{1,2}:\\d{2}").containsMatchIn(it) }}，小数=${compact.count { Regex("\\d+[.。·]\\d{2}").containsMatchIn(it) }}，付款=${recognized.text.contains("支付") || recognized.text.contains("付款")}，凭证=${recognized.text.contains("凭证")}")
                            }
                        }
                        .addOnFailureListener { MonitoringDiagnostics.record(this@WechatScreenService, SOURCE, "微信页面文字识别失败") }
                        .addOnCompleteListener { bitmap.recycle(); busy = false }
                }
            })
        } catch (_: Exception) {
            busy = false
            MonitoringDiagnostics.record(this, SOURCE, "系统未允许读取微信屏幕")
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        scope.cancel()
        recognizer.close()
        super.onDestroy()
    }
}
