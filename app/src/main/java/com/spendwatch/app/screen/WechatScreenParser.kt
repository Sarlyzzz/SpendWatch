package com.spendwatch.app.screen

import com.spendwatch.app.data.NormalizedRecord
import com.spendwatch.app.data.RecordKind
import com.spendwatch.app.importer.StatementParser
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Accepts only a complete, successful WeChat transfer detail page. */
internal object WechatScreenParser {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy年M月d日H:mm:ss")
    private val amountLine = Regex("^[\\-−－]?[¥￥]?\\s*(\\d{1,10}(?:\\.\\d{2}))$")
    private val orderPattern = Regex("转账单号\\s*([0-9\\s]{20,50})")
    private val timePattern = Regex("转账时间\\s*(\\d{4}年\\d{1,2}月\\d{1,2}日\\s*\\d{1,2}:\\d{2}:\\d{2})")
    private val fundingPattern = Regex("支付方式\\s*([^\\n]+)")

    fun parse(ocrLines: List<String>): NormalizedRecord? {
        val lines = ocrLines.map(String::trim).filter(String::isNotBlank)
        val text = lines.joinToString("\n")
        if (!text.contains("转账单号") || !text.contains("转账时间") ||
            !text.contains("对方已收钱") || !text.contains("转账")) return null
        val amountText = lines.firstNotNullOfOrNull { amountLine.matchEntire(it)?.groupValues?.getOrNull(1) } ?: return null
        val amount = runCatching { StatementParser.cents(amountText) }.getOrNull() ?: return null
        if (amount <= 0) return null
        val orderId = orderPattern.find(text)?.groupValues?.getOrNull(1)?.filter(Char::isDigit) ?: return null
        if (orderId.length !in 20..40) return null
        val timeText = timePattern.find(text)?.groupValues?.getOrNull(1)?.replace("\\s+".toRegex(), "") ?: return null
        val occurredAt = runCatching { LocalDateTime.parse(timeText, dateFormat).atZone(zone).toInstant().toEpochMilli() }
            .getOrNull() ?: return null
        val funding = fundingPattern.find(text)?.groupValues?.getOrNull(1).orEmpty().take(48)
            .replace(Regex("\\d{5,}"), "****").ifBlank { "未知" }
        return NormalizedRecord(
            source = "微信", sourceKey = "wechat:$orderId", occurredAt = occurredAt,
            amountMinor = amount, kind = RecordKind.TRANSFER, merchant = "微信转账",
            note = "微信账单详情屏幕识别，请核对是否属于消费", paymentChannel = "微信",
            fundingAccount = funding,
        )
    }
}
