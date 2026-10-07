package com.spendwatch.app.screen

import com.spendwatch.app.data.RecordKind
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WechatChatParserTest {
    private val page = listOf(
        "微信支付", "周一18:59", "小德共享", "使用零钱通支付", "¥2.50", "账单详情",
        "19:51", "微信支付凭证", "使用零钱通支付", "¥1.00", "收款方", "爸",
        "交易状态", "支付成功，对方已收款", "查看账单详情",
    )

    @Test fun visibleMerchantAndTransferCardsHaveDistinctKindsAndStableKeys() {
        val records = WechatChatParser.parse(page, LocalDate.of(2026, 10, 7))
        assertEquals(2, records.size)
        assertEquals(RecordKind.PAYMENT, records[0].kind)
        assertEquals(250L, records[0].amountMinor)
        assertEquals("小德共享", records[0].merchant)
        assertEquals(RecordKind.TRANSFER, records[1].kind)
        assertEquals(100L, records[1].amountMinor)
        assertEquals("零钱通", records[1].fundingAccount)
        assertEquals(records.map { it.sourceKey }, WechatChatParser.parse(page, LocalDate.of(2026, 10, 7)).map { it.sourceKey })
    }

    @Test fun otherConversationsAndIncompleteCardsAreIgnored() {
        assertTrue(WechatChatParser.parse(page.drop(1), LocalDate.of(2026, 10, 7)).isEmpty())
        assertTrue(WechatChatParser.parse(page - "周一18:59", LocalDate.of(2026, 10, 7)).isEmpty())
        assertEquals(1, WechatChatParser.parse(page - "支付成功，对方已收款", LocalDate.of(2026, 10, 7)).size)
        assertEquals(2, WechatChatParser.parse(page - "账单详情", LocalDate.of(2026, 10, 7)).size)
    }

    @Test fun largeBareDecimalAfterConversationTimestampIsAccepted() {
        val lines = listOf("22:16", "0.10", "微信支付", "周一18:59", "小德共享",
            "使用零钱通支付", "2.50", "账单详情")
        val records = WechatChatParser.parse(lines, LocalDate.of(2026, 10, 7))
        assertEquals(1, records.size)
        assertEquals(250L, records.single().amountMinor)
    }

    @Test fun ambiguousSameMinuteSameAmountCardsAreSkipped() {
        val lines = listOf("微信支付", "今天12:00", "商户甲", "使用零钱支付", "2.50",
            "12:00", "商户乙", "使用零钱支付", "2.50")
        assertTrue(WechatChatParser.parse(lines, LocalDate.of(2026, 10, 7)).isEmpty())
    }
}
