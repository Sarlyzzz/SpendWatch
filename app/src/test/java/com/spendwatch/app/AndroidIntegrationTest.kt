package com.spendwatch.app

import android.app.Notification
import android.app.NotificationManager
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import com.spendwatch.app.data.ImportBatchEntity
import com.spendwatch.app.data.NormalizedRecord
import com.spendwatch.app.data.RecordKind
import com.spendwatch.app.importer.StatementParser
import com.spendwatch.app.notifications.NotificationProbe
import com.spendwatch.app.notifications.PaymentNotificationService
import com.spendwatch.app.notifications.AppCatalog
import com.spendwatch.app.notifications.MonitoringDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = SpendWatchApplication::class)
class AndroidIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication() as SpendWatchApplication

    @After fun closeDatabase() { app.database.close() }

    @Test fun xmlWorksWithActualAndroidFactoryAndRejectsDtd() {
        val factory = Class.forName("org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl")
            .getDeclaredConstructor().newInstance() as DocumentBuilderFactory
        val doc = StatementParser.parseXml("<worksheet><row>测试商户</row></worksheet>".toByteArray(), factory)
        assertEquals("测试商户", doc.getElementsByTagName("row").item(0).textContent)
        assertThrows(IllegalArgumentException::class.java) {
            StatementParser.parseXml("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///private'>]><x>&e;</x>".toByteArray(), factory)
        }
    }

    @Test fun alipayAndroidExtrasReachLedgerAndRepeatedScanDoesNotDuplicate() = runBlocking {
        val controller = Robolectric.buildService(PaymentNotificationService::class.java).create()
        val service = controller.get()
        try {
            val payment = notice("com.eg.android.AlipayGphone", 1, "交易提醒", "你有一笔12.34元的支出，使用花呗支付")
            val refund = notice("com.eg.android.AlipayGphone", 2, "退款提醒", "你收到一笔12.34元退款，点击查看账单详情！")
            service.onNotificationPosted(payment)
            service.onNotificationPosted(refund)
            repeat(100) { if (app.repository.allTransactions().size < 2) delay(30) }
            assertEquals(2, app.repository.allTransactions().size)
            service.onNotificationPosted(payment)
            service.onNotificationPosted(refund)
            delay(200)
            val records = app.repository.allTransactions()
            assertEquals(2, records.size)
            assertEquals(1234L, records.single { it.status == "PAID" }.amountMinor)
            assertEquals("花呗", records.single { it.status == "PAID" }.fundingAccount)
            assertEquals(1, records.count { it.status == "REFUND_REVIEW" })
        } finally { controller.destroy() }
    }

    @Test fun probeCallbackIsRecognizedAndNeverEntersLedger() = runBlocking {
        val controller = Robolectric.buildService(PaymentNotificationService::class.java).create()
        try {
            assertTrue(NotificationProbe.send(app).startsWith("测试通知已发送"))
            val notification = app.getSystemService(NotificationManager::class.java).activeNotifications.single()
            controller.get().onNotificationPosted(notification)
            assertTrue(NotificationProbe.result(app).startsWith("测试通过"))
            assertTrue(app.repository.allTransactions().isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun truncatedCmbDebitExplainsMissingAmountWithoutInventingTransaction() = runBlocking {
        val cmb = AppCatalog.apps.single { it.name == "招商银行" }
        AppCatalog.setEnabled(app, cmb, true)
        val controller = Robolectric.buildService(PaymentNotificationService::class.java).create()
        try {
            controller.get().onNotificationPosted(notice(cmb.packageName, 3, "招商银行",
                "您账户尾号****在【财付通-微信支付-微信转账】发生快捷支付扣款，..."))
            assertTrue(app.repository.allTransactions().isEmpty())
            assertTrue(MonitoringDiagnostics.source(app, cmb.packageName)!!.outcome.contains("没有金额"))
        } finally { controller.destroy() }
    }

    @Test fun undoPaymentImportPreservesRefundForReview() = runBlocking {
        val repository = app.repository
        repository.importBatch(ImportBatchEntity(fileName = "synthetic.csv", fileHash = "synthetic", source = "支付宝",
            importedAt = 1, parsedCount = 1, errorCount = 0), listOf(NormalizedRecord(
            source = "支付宝", sourceKey = "payment", occurredAt = 1, amountMinor = 1234,
            kind = RecordKind.PAYMENT, merchant = "测试商户",
        )))
        repository.ingest(NormalizedRecord(source = "支付宝", sourceKey = "refund", occurredAt = 2,
            amountMinor = 1234, kind = RecordKind.REFUND, merchant = "测试商户"))
        assertEquals(1234L, repository.allTransactions().single().refundedMinor)
        val batch = app.database.ledger().batchByHash("synthetic")!!
        assertTrue(repository.removeImport(batch.id))
        val survivor = repository.allTransactions().single()
        assertEquals("REFUND_REVIEW", survivor.status)
        assertEquals(1, app.database.ledger().evidenceFor(survivor.id).size)
        repository.setStatus(survivor.id, "EXCLUDED")
        repository.restoreExcluded(survivor.id)
        assertEquals("REFUND_REVIEW", repository.transaction(survivor.id)!!.status)
    }

    private fun notice(pkg: String, id: Int, title: String, body: String): StatusBarNotification {
        val notification = Notification.Builder(app, "test").setContentTitle(title).setContentText(body).build()
        return StatusBarNotification(pkg, pkg, id, null, 0, 0, 0, notification, UserHandle.getUserHandleForUid(0), 1_000L + id)
    }
}
