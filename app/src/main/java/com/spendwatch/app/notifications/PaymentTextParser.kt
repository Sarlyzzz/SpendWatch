package com.spendwatch.app.notifications

import com.spendwatch.app.data.NormalizedRecord
import com.spendwatch.app.data.RecordKind
import com.spendwatch.app.importer.StatementParser

/** Parses only explicit transaction notices from a known source. */
internal object PaymentTextParser {
    private const val NUMBER = "((?:\\d{1,3}(?:,\\d{3})+|\\d{1,10})(?:\\.\\d{1,2})?)"
    private val amountPattern = Regex("(?<![\\d.,])(?:[￥¥]\\s*)?$NUMBER\\s*元")
    private val currencyPattern = Regex("[￥¥]\\s*$NUMBER(?![\\d.,])")
    private val spending = Regex("[你您]有一笔\\s*$NUMBER\\s*元的?支出")
    private val receivedRefund = Regex("收到一笔\\s*$NUMBER\\s*元退款")

    fun hasAmount(text: String): Boolean = amountPattern.containsMatchIn(text) || currencyPattern.containsMatchIn(text)

    fun parse(source: String, text: String, key: String, at: Long): NormalizedRecord? {
        if (text.isBlank() || listOf("有一条微信消息", "待付款", "支付失败", "付款失败", "退款失败", "交易失败", "退款处理中", "退款申请", "退款未到账").any(text::contains)) return null
        if (Regex("未(?:支付|付款|退款|交易)成功").containsMatchIn(text)) return null
        val expenseNotice = if (source == "支付宝") spending.find(text) else null
        val refundNotice = if (source == "支付宝") receivedRefund.find(text) else null
        val match = refundNotice ?: expenseNotice ?: amountPattern.find(text) ?: currencyPattern.find(text) ?: return null
        val amount = runCatching { StatementParser.cents(match.groupValues[1]) }.getOrNull() ?: return null
        if (amount <= 0) return null

        val refund = listOf("退款成功", "退款已到账").any(text::contains) || refundNotice != null
        val paid = listOf("支付成功", "付款成功", "成功付款", "交易成功", "扣款成功").any(text::contains) ||
            expenseNotice != null
        val bank = source in setOf("招商银行", "中国银行", "邮储银行")
        val bankDebit = bank && listOf("支出", "扣款", "消费").any(text::contains)
        val order = listOf("订单", "下单成功").any(text::contains)
        val kind = when {
            refund -> RecordKind.REFUND
            source in setOf("淘宝", "抖音") && order -> RecordKind.ORDER
            source == "支付宝" && paid -> RecordKind.PAYMENT
            bank && (paid || bankDebit) -> RecordKind.BANK_DEBIT
            else -> return null
        }
        val merchant = Regex("(?:向|商户[：:])\\s*([^，,。；;]{2,30})").find(text)?.groupValues?.getOrNull(1)
            ?.replace(Regex("\\d{4,}"), "****") ?: source
        val funding = when {
            source == "支付宝" && text.contains("花呗") -> "花呗"
            source == "支付宝" && text.contains("余额宝") -> "余额宝"
            source == "支付宝" && text.contains("余额支付") -> "支付宝余额"
            bank -> source
            else -> "未知"
        }
        return NormalizedRecord(
            source = source, sourceKey = key, occurredAt = at, amountMinor = amount,
            kind = kind, merchant = merchant, note = "通知识别：${kind.name}",
            shoppingPlatform = if (source in setOf("淘宝", "抖音")) source else "未知",
            paymentChannel = if (source == "支付宝") "支付宝" else "未知",
            fundingAccount = funding,
        )
    }
}
