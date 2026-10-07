package com.spendwatch.app.importer

import com.spendwatch.app.data.NormalizedRecord
import com.spendwatch.app.data.RecordKind
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Text parser for the three verified bank PDF layouts. Unknown rows remain visible as errors. */
internal object BankTextParsers {
    private val date = Regex("(?m)^\\s*(20\\d{2}-\\d{2}-\\d{2})(?:[ \\t]+(\\d{2}:\\d{2}:\\d{2}))?")
    private val amount = Regex("(?<![\\d.])[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)")
    private val zone = ZoneId.of("Asia/Shanghai")

    fun parseCmb(text: String) = parse("招商银行", text, requireCurrencyAfterDate = true)
    fun parseBoc(text: String) = parse("中国银行", text)
    fun parsePsbc(text: String) = parse("邮储银行", text)

    private fun parse(bank: String, text: String, requireCurrencyAfterDate: Boolean = false): ParseResult {
        val records = mutableListOf<NormalizedRecord>()
        val errors = mutableListOf<String>()
        val matches = date.findAll(text).toList()
        for ((index, match) in matches.withIndex()) {
            val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val block = text.substring(match.range.first, end).trim()
            val firstLine = block.lineSequence().firstOrNull().orEmpty()
            if (requireCurrencyAfterDate && !firstLine.matches(Regex("^20\\d{2}-\\d{2}-\\d{2}\\s+[A-Z]{3}\\s+.*"))) continue
            val values = amount.findAll(block).toList()
            if (values.size < 2) continue // date range and document metadata
            try {
                val signed = StatementParser.signedCents(values[0].value)
                val balance = StatementParser.signedCents(values[1].value)
                val dateValue = match.groupValues[1]
                val timeValue = match.groupValues[2].ifBlank {
                    Regex("\\b\\d{2}:\\d{2}:\\d{2}\\b").find(firstLine)?.value ?: "00:00:00"
                }
                val at = LocalDate.parse(dateValue).atTime(java.time.LocalTime.parse(timeValue))
                    .atZone(zone).toInstant().toEpochMilli()
                val remaining = firstLine.substring((values[1].range.last + 1).coerceAtMost(firstLine.length))
                    .trim().ifBlank { "银行交易" }
                val merchant = maskAccounts(remaining).take(80)
                val isPaymentGateway = block.contains("支付宝") || block.contains("微信") || block.contains("财付通")
                val direct = signed < 0 && !isPaymentGateway &&
                    listOf("消费", "快捷支付", "POS", "购物", "支付交易").any { block.contains(it, ignoreCase = true) }
                val signature = "$bank|$dateValue|$timeValue|$signed|$balance|$merchant"
                records += NormalizedRecord(
                    source = bank, sourceKey = "bank:${StatementParser.sha(signature)}",
                    occurredAt = at, amountMinor = abs(signed),
                    kind = if (signed < 0) RecordKind.BANK_DEBIT else RecordKind.BANK_CREDIT,
                    merchant = merchant, note = maskAccounts(block.replace(Regex("\\s+"), " ")).take(160),
                    paymentChannel = if (direct) "银行卡直付" else "未知",
                    fundingAccount = bank, directBankSpend = direct,
                )
            } catch (error: Exception) {
                errors += "$bank 第 ${index + 1} 条候选记录：${error.message ?: "无法解析"}"
            }
        }
        require(records.isNotEmpty()) { "$bank PDF 未识别到交易" }
        return ParseResult(bank, records, errors)
    }

    private fun maskAccounts(value: String) = value.replace(Regex("(?<!\\d)\\d{5,}(?!\\d)"), "****")
}
