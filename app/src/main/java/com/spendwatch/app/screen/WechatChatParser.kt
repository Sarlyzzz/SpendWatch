package com.spendwatch.app.screen

import com.spendwatch.app.data.NormalizedRecord
import com.spendwatch.app.data.RecordKind
import com.spendwatch.app.importer.StatementParser
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Reads only complete visible cards in the WeChat Pay conversation. */
internal object WechatChatParser {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val stamp = Regex("^(?:(今天|昨天|周[一二三四五六日天]|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}日)\\s*)?(\\d{1,2}):(\\d{2})$")
    private val amount = Regex("[¥￥Yy]?(\\d{1,10}\\.\\d{2})")
    private val funding = Regex("使用([^\\n]{1,24})支付")

    fun parse(ocrLines: List<String>, today: LocalDate = LocalDate.now(zone)): List<NormalizedRecord> {
        val lines = ocrLines.map { it.trim().replace('：', ':').replace(Regex("\\s+"), "")
            .replace('。', '.').replace('·', '.') }
            .filter(String::isNotBlank)
        val header = lines.take(10)
        if (header.none { it == "微信支付" } && header.windowed(2).none { it[0] == "微信" && it[1] == "支付" })
            return emptyList()
        val records = mutableListOf<NormalizedRecord>()
        var date: LocalDate? = null
        var time: LocalTime? = null
        var card = mutableListOf<String>()
        fun flush() {
            val day = date ?: return
            val at = time ?: return
            val text = card.joinToString("\n")
            if (text.contains("退款") || text.contains("收款到账")) return
            val amountText = card.firstNotNullOfOrNull { amount.matchEntire(it)?.groupValues?.getOrNull(1) } ?: return
            val cents = runCatching { StatementParser.cents(amountText) }.getOrNull() ?: return
            if (cents <= 0) return
            val isTransfer = text.contains("凭证") &&
                (text.contains("支付成功") || text.contains("对方已收款"))
            val isPayment = !text.contains("凭证") &&
                (funding.containsMatchIn(text) || text.contains("付款成功"))
            if (!isTransfer && !isPayment) return
            val merchant = if (isTransfer) "微信转账" else card.takeWhile { !it.contains("支付") }
                .firstOrNull { it.length in 2..48 && it != "微信支付" } ?: "微信支付商户"
            val occurredAt = LocalDateTime.of(day, at).atZone(zone).toInstant().toEpochMilli()
            val kind = if (isTransfer) RecordKind.TRANSFER else RecordKind.PAYMENT
            val key = "wechat-chat:${StatementParser.sha("$occurredAt|$cents|$kind")}"
            records += NormalizedRecord(
                source = "微信支付会话", sourceKey = key, occurredAt = occurredAt,
                amountMinor = cents, kind = kind, merchant = merchant,
                note = "微信支付会话屏幕识别；聊天卡片可能缺少交易单号，请核对同额交易",
                paymentChannel = "微信", fundingAccount = funding.find(text)?.groupValues?.getOrNull(1) ?: "未知",
            )
        }
        for (line in lines.drop(1)) {
            val matched = stamp.find(line)
            if (matched != null) {
                flush()
                card = mutableListOf()
                val label = matched.groupValues[1]
                if (label.isNotEmpty()) date = parseDay(label, today)
                time = runCatching { LocalTime.of(matched.groupValues[2].toInt(), matched.groupValues[3].toInt()) }.getOrNull()
            } else if (time != null) {
                card += line
            }
        }
        flush()
        return records.groupBy { it.sourceKey }.values.filter { it.size == 1 }.map { it.single() }
    }

    private fun parseDay(label: String, today: LocalDate): LocalDate? = when (label) {
        "今天" -> today
        "昨天" -> today.minusDays(1)
        else -> when {
            label.startsWith("周") -> {
                val weekday = when (label.last()) {
                    '一' -> DayOfWeek.MONDAY; '二' -> DayOfWeek.TUESDAY; '三' -> DayOfWeek.WEDNESDAY
                    '四' -> DayOfWeek.THURSDAY; '五' -> DayOfWeek.FRIDAY; '六' -> DayOfWeek.SATURDAY
                    else -> DayOfWeek.SUNDAY
                }
                today.minusDays((today.dayOfWeek.value - weekday.value + 7L) % 7)
            }
            else -> runCatching {
                val match = Regex("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日").matchEntire(label) ?: return null
                var day = LocalDate.of(match.groupValues[1].toIntOrNull() ?: today.year,
                    match.groupValues[2].toInt(), match.groupValues[3].toInt())
                if (match.groupValues[1].isEmpty() && day.isAfter(today)) day = day.minusYears(1)
                day
            }.getOrNull()
        }
    }
}
