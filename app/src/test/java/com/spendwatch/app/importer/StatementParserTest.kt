package com.spendwatch.app.importer

import com.spendwatch.app.data.RecordKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StatementParserTest {
    @Test fun alipayUsesDirectionAndStatusAndReadsGb18030() {
        val csv = """
            账单说明
            交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注,
            2026-10-01 12:00:00,餐饮美食,测试商户,,午餐,支出,12.30,银行卡,交易成功,A1,M1,,
            2026-10-01 12:01:00,餐饮美食,测试商户,,代付,不计收支,20.00,因公付,交易成功,A2,M2,,
            2026-10-01 12:02:00,退款,测试商户,,退款,不计收支,12.30,,退款成功,A3,M1,,
            2026-10-01 12:03:00,日用百货,测试商户,,取消,支出,30.00,,交易关闭,A4,M4,,
        """.trimIndent()
        val result = StatementParser.parseAlipay(csv.toByteArray(Charset.forName("GB18030")))
        assertEquals(4, result.records.size)
        assertEquals(listOf(RecordKind.PAYMENT, RecordKind.IGNORE, RecordKind.REFUND, RecordKind.IGNORE), result.records.map { it.kind })
        assertEquals(1230L, result.records.first().amountMinor)
        assertTrue(result.errors.isEmpty())
    }

    @Test fun wechatParsesExcelSerialAndKeepsTransfersForReview() {
        val sheet = """<?xml version="1.0" encoding="UTF-8"?>
            <worksheet><sheetData>
              <row r="18"><c r="A18" t="inlineStr"><is><t>交易时间</t></is></c><c r="B18" t="inlineStr"><is><t>交易类型</t></is></c><c r="C18" t="inlineStr"><is><t>交易对方</t></is></c><c r="E18" t="inlineStr"><is><t>收/支</t></is></c><c r="F18" t="inlineStr"><is><t>金额(元)</t></is></c><c r="H18" t="inlineStr"><is><t>当前状态</t></is></c><c r="I18" t="inlineStr"><is><t>交易单号</t></is></c></row>
              <row r="19"><c r="A19"><v>46296.5</v></c><c r="B19" t="inlineStr"><is><t>商户消费</t></is></c><c r="C19" t="inlineStr"><is><t>测试店</t></is></c><c r="E19" t="inlineStr"><is><t>支出</t></is></c><c r="F19"><v>9.8</v></c><c r="H19" t="inlineStr"><is><t>支付成功</t></is></c><c r="I19" t="inlineStr"><is><t>W1</t></is></c></row>
              <row r="20"><c r="A20"><v>46296.6</v></c><c r="B20" t="inlineStr"><is><t>转账</t></is></c><c r="C20" t="inlineStr"><is><t>测试人</t></is></c><c r="E20" t="inlineStr"><is><t>支出</t></is></c><c r="F20"><v>30</v></c><c r="H20" t="inlineStr"><is><t>对方已收钱</t></is></c><c r="I20" t="inlineStr"><is><t>W2</t></is></c></row>
            </sheetData></worksheet>""".trimIndent()
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zip.write(sheet.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }.toByteArray()
        val result = StatementParser.parseWechat(bytes)
        assertEquals(2, result.records.size)
        assertEquals(RecordKind.PAYMENT, result.records[0].kind)
        assertEquals(RecordKind.TRANSFER, result.records[1].kind)
        assertEquals(980L, result.records[0].amountMinor)
    }

    @Test fun bankNegativeAmountIsNotAutomaticallyExpenseWithoutPaymentClue() {
        val text = """
            招商银行 交易流水 2026-09-05 -- 2026-10-05
            记账日期 货币 交易金额 联机余额 交易摘要 对手信息
            2026-10-01 CNY -12.30 1,000.00 转账 测试
            2026-10-02 CNY -20.00 980.00 POS消费 测试店
        """.trimIndent()
        val result = BankTextParsers.parseCmb(text)
        assertEquals(2, result.records.size)
        assertEquals(false, result.records[0].directBankSpend)
        assertEquals(true, result.records[1].directBankSpend)
    }
}
