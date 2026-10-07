package com.spendwatch.app.notifications

import com.spendwatch.app.data.RecordKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentTextParserTest {
    @Test fun alipaySpendingReminderIsPayment() {
        val record = PaymentTextParser.parse(
            "支付宝", "交易提醒 你有一笔12.34元的支出，使用花呗支付，确认收货的订单请查看详情",
            "notice-payment", 1L,
        )!!
        assertEquals(RecordKind.PAYMENT, record.kind)
        assertEquals(1234L, record.amountMinor)
        assertEquals("花呗", record.fundingAccount)
    }

    @Test fun alipayRefundReminderIsSeparateRefund() {
        val record = PaymentTextParser.parse(
            "支付宝", "退款提醒 你收到一笔12.34元退款，点击查看账单详情！",
            "notice-refund", 2L,
        )!!
        assertEquals(RecordKind.REFUND, record.kind)
        assertEquals(1234L, record.amountMinor)
    }

    @Test fun unrelatedOrRedactedNoticeDoesNotBecomeExpense() {
        assertNull(PaymentTextParser.parse("支付宝", "交易提醒 你有8个支付宝积分可领取", "points", 1L))
        assertNull(PaymentTextParser.parse("微信", "有一条微信消息", "hidden", 1L))
    }

    @Test fun thousandsAndMarketingAmountDoNotChangeTransactionAmount() {
        val record = PaymentTextParser.parse("支付宝", "交易提醒 领取2元奖励。你有一笔1,234.56元的支出", "large", 1)!!
        assertEquals(123456L, record.amountMinor)
        assertEquals(1234L, PaymentTextParser.parse("支付宝", "支付成功 ￥12.34", "currency", 1)!!.amountMinor)
    }

    @Test fun refundTitleOrFailureDoesNotProveMoneyWasReceived() {
        assertNull(PaymentTextParser.parse("支付宝", "退款提醒 12.34元退款申请已提交", "pending", 1))
        assertNull(PaymentTextParser.parse("支付宝", "退款提醒 退款失败12.34元", "failed", 1))
        assertNull(PaymentTextParser.parse("支付宝", "交易提醒 未支付成功12.34元", "unpaid", 1))
    }
}
