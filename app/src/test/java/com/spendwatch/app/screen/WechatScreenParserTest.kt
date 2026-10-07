package com.spendwatch.app.screen

import com.spendwatch.app.data.RecordKind
import org.junit.Assert.*
import org.junit.Test

class WechatScreenParserTest {
    private val page = listOf(
        "转账-转给朋友", "-1.00", "当前状态", "对方已收钱", "转账说明", "微信转账",
        "转账时间", "2026年10月7日 21:10:03", "支付方式", "招商银行储蓄卡(9861)",
        "转账单号", "100005000120261007072599990806", "9", "账单服务",
    )

    @Test fun completeTransferDetailCreatesReviewEvidence() {
        val record = WechatScreenParser.parse(page)!!
        assertEquals(RecordKind.TRANSFER, record.kind)
        assertEquals(100L, record.amountMinor)
        assertEquals("wechat:1000050001202610070725999908069", record.sourceKey)
        assertEquals("微信", record.paymentChannel)
        assertTrue(record.fundingAccount.contains("招商银行"))
    }

    @Test fun incompleteOrUnacceptedTransferIsIgnored() {
        assertNull(WechatScreenParser.parse(page - "对方已收钱"))
        assertNull(WechatScreenParser.parse(page - "转账单号"))
        assertNull(WechatScreenParser.parse(page - "-1.00"))
    }
}
