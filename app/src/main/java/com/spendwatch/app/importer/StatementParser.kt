package com.spendwatch.app.importer

import android.content.Context
import com.spendwatch.app.data.NormalizedRecord
import com.spendwatch.app.data.RecordKind
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import net.lingala.zip4j.ZipFile
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import java.io.StringReader
import org.xml.sax.InputSource
import org.xml.sax.SAXException

data class ParseResult(
    val source: String,
    val records: List<NormalizedRecord>,
    val errors: List<String>,
)

class PasswordNeeded(val fileType: String) : Exception("此文件需要$fileType 密码")
class WrongPassword : Exception("密码不正确，或文件格式不受支持")

object StatementParser {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private const val MAX_FILE = 15 * 1024 * 1024

    fun parse(context: Context, fileName: String, bytes: ByteArray, password: CharArray? = null): ParseResult {
        require(bytes.size <= MAX_FILE) { "文件超过 15 MB" }
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".csv") -> parseAlipay(bytes)
            lower.endsWith(".xlsx") -> parseWechat(bytes)
            lower.endsWith(".zip") -> parseBankZip(context, bytes, password)
            lower.endsWith(".pdf") -> parseBankPdf(bytes, password)
            else -> throw IllegalArgumentException("仅支持 CSV、XLSX、PDF 和 ZIP")
        }
    }

    private fun decodeCsv(bytes: ByteArray): String {
        for (charset in listOf(Charsets.UTF_8, Charset.forName("GB18030"))) {
            try {
                return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            } catch (_: CharacterCodingException) { }
        }
        throw IllegalArgumentException("无法识别 CSV 编码")
    }

    private fun csvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            when (val ch = text[index]) {
                '"' -> if (quoted && index + 1 < text.length && text[index + 1] == '"') {
                    cell.append('"'); index++
                } else quoted = !quoted
                ',' -> if (quoted) cell.append(ch) else { row += cell.toString(); cell.clear() }
                '\n' -> if (quoted) cell.append(ch) else {
                    row += cell.toString().trimEnd('\r'); cell.clear()
                    rows += row.toList(); row.clear()
                }
                else -> cell.append(ch)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row.toList() }
        return rows
    }

    internal fun parseAlipay(bytes: ByteArray): ParseResult {
        val rows = csvRows(decodeCsv(bytes))
        val headerIndex = rows.indexOfFirst { it.containsAll(listOf("交易时间", "交易分类", "收/支", "金额", "交易状态", "交易订单号")) }
        require(headerIndex >= 0) { "未找到支付宝交易表头" }
        val header = rows[headerIndex].map(String::trim)
        val records = mutableListOf<NormalizedRecord>()
        val errors = mutableListOf<String>()
        for ((offset, row) in rows.drop(headerIndex + 1).withIndex()) {
            val line = offset + headerIndex + 2
            fun field(name: String) = row.getOrNull(header.indexOf(name).takeIf { it >= 0 } ?: -1)?.trim().orEmpty()
            val time = field("交易时间")
            if (!time.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"))) continue
            try {
                val amount = cents(field("金额"))
                val direction = field("收/支")
                val status = field("交易状态")
                val category = field("交易分类")
                val kind = when {
                    status == "退款成功" -> RecordKind.REFUND
                    status == "交易关闭" -> RecordKind.IGNORE
                    direction == "支出" && status in setOf("交易成功", "支付成功") -> RecordKind.PAYMENT
                    direction == "支出" -> RecordKind.ORDER
                    direction == "不计收支" && category in setOf("账户存取", "信用借还", "投资理财") -> RecordKind.IGNORE
                    else -> RecordKind.IGNORE
                }
                val id = field("交易订单号")
                val merchant = field("交易对方")
                val funding = field("收/付款方式").ifBlank { "未知" }
                val order = field("商家订单号")
                records += NormalizedRecord(
                    source = "支付宝", sourceKey = "alipay:${id.ifBlank { sha("$time|$amount|$merchant|$line") }}",
                    occurredAt = epoch(time), amountMinor = amount, kind = kind, merchant = merchant,
                    externalRef = order.takeUnless { it == "/" || it == "-" }.orEmpty(),
                    note = field("商品说明").take(160), paymentChannel = "支付宝", fundingAccount = funding,
                    category = if (category.isBlank()) "未分类" else category,
                )
            } catch (error: Exception) {
                errors += "第 $line 行：${error.message ?: "无法解析"}"
            }
        }
        return ParseResult("支付宝", records, errors)
    }

    internal fun parseWechat(bytes: ByteArray): ParseResult {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name in setOf("xl/sharedStrings.xml", "xl/worksheets/sheet1.xml")) {
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        require(out.size() <= MAX_FILE) { "表格内容过大" }
                    }
                    entries[entry.name] = out.toByteArray()
                }
            }
        }
        val sheet = entries["xl/worksheets/sheet1.xml"] ?: throw IllegalArgumentException("未找到微信账单工作表")
        val strings = entries["xl/sharedStrings.xml"]?.let { xml ->
            val doc = parseXml(xml)
            (0 until doc.getElementsByTagName("si").length).map { index ->
                doc.getElementsByTagName("si").item(index).textContent.orEmpty()
            }
        }.orEmpty()
        val document = parseXml(sheet)
        val rowNodes = document.getElementsByTagName("row")
        val rows = mutableListOf<Pair<Int, List<String>>>()
        for (i in 0 until rowNodes.length) {
            val node = rowNodes.item(i) as Element
            val cells = node.getElementsByTagName("c")
            val values = MutableList(16) { "" }
            for (j in 0 until cells.length) {
                val cell = cells.item(j) as Element
                val col = cell.getAttribute("r").takeWhile(Char::isLetter).fold(0) { acc, ch -> acc * 26 + (ch.uppercaseChar() - 'A' + 1) } - 1
                if (col !in values.indices) continue
                val raw = cell.getElementsByTagName("v").item(0)?.textContent
                    ?: cell.getElementsByTagName("is").item(0)?.textContent.orEmpty()
                values[col] = if (cell.getAttribute("t") == "s") strings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
            }
            rows += (node.getAttribute("r").toIntOrNull() ?: i + 1) to values
        }
        val headerAt = rows.indexOfFirst { (_, row) -> row.containsAll(listOf("交易时间", "交易类型", "收/支", "金额(元)", "交易单号")) }
        require(headerAt >= 0) { "未找到微信交易表头" }
        val header = rows[headerAt].second
        val records = mutableListOf<NormalizedRecord>()
        val errors = mutableListOf<String>()
        for ((line, row) in rows.drop(headerAt + 1)) {
            fun field(name: String) = row.getOrNull(header.indexOf(name).takeIf { it >= 0 } ?: -1)?.trim().orEmpty()
            if (field("交易时间").isBlank()) continue
            try {
                val time = field("交易时间")
                val occurredAt = if (time.contains('-')) epoch(time) else excelEpoch(time)
                val amount = cents(field("金额(元)"))
                val type = field("交易类型")
                val direction = field("收/支")
                val status = field("当前状态")
                val kind = when {
                    direction == "收入" -> RecordKind.IGNORE
                    type == "商户消费" && direction == "支出" && status == "支付成功" -> RecordKind.PAYMENT
                    direction == "支出" && type in setOf("转账", "扫二维码付款", "群收款") -> RecordKind.TRANSFER
                    else -> RecordKind.IGNORE
                }
                val id = field("交易单号")
                val merchant = field("交易对方")
                records += NormalizedRecord(
                    source = "微信", sourceKey = "wechat:${id.ifBlank { sha("$time|$amount|$merchant|$line") }}",
                    occurredAt = occurredAt, amountMinor = amount, kind = kind, merchant = merchant,
                    externalRef = field("商户单号").takeUnless { it == "/" || it == "-" }.orEmpty(),
                    note = field("商品").take(160), paymentChannel = "微信", fundingAccount = field("支付方式").ifBlank { "未知" },
                )
            } catch (error: Exception) {
                errors += "第 $line 行：${error.message ?: "无法解析"}"
            }
        }
        return ParseResult("微信", records, errors)
    }

    internal fun parseXml(bytes: ByteArray, factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()): org.w3c.dom.Document {
        // Android's XML factory does not implement the desktop Xerces feature flags.
        // Decode strictly before screening declarations; parse the same characters afterwards.
        val xml = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require(!xml.contains('\u0000')) { "表格 XML 编码不受支持，请使用原始微信 XLSX" }
        require(!Regex("<!\\s*(?:DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
            "账单 XML 包含不支持的文档或实体声明"
        }
        factory.isValidating = false
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("账单不得读取外部资源") }
        return builder.parse(InputSource(StringReader(xml)))
    }

    private fun parseBankZip(context: Context, bytes: ByteArray, password: CharArray?): ParseResult {
        val temporary = File.createTempFile("statement-", ".zip", context.cacheDir)
        try {
            temporary.writeBytes(bytes)
            val archive = ZipFile(temporary)
            if (archive.isEncrypted && password == null) throw PasswordNeeded("ZIP")
            if (archive.isEncrypted) archive.setPassword(password)
            val files = archive.fileHeaders.filter { !it.isDirectory }
            require(files.size == 1 && files.single().fileName.lowercase().endsWith(".pdf")) { "ZIP 必须只包含一份 PDF" }
            require(files.single().uncompressedSize in 1..MAX_FILE.toLong()) { "解压后的 PDF 大小不受支持" }
            return try {
                val pdfBytes = archive.getInputStream(files.single()).use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        require(output.size() <= MAX_FILE) { "PDF 过大" }
                    }
                    output.toByteArray()
                }
                parseBankPdf(pdfBytes, password)
            } catch (error: net.lingala.zip4j.exception.ZipException) {
                throw WrongPassword()
            }
        } finally {
            temporary.delete()
        }
    }

    private fun parseBankPdf(bytes: ByteArray, password: CharArray?): ParseResult {
        val document = try {
            PDDocument.load(bytes, String(password ?: charArrayOf()))
        } catch (error: InvalidPasswordException) {
            if (password == null) throw PasswordNeeded("PDF") else throw WrongPassword()
        }
        document.use { pdf ->
            val text = PDFTextStripper().apply { sortByPosition = true }.getText(pdf)
            if (text.isBlank()) throw IllegalArgumentException("扫描版 PDF 暂不支持")
            return when {
                text.contains("招商银行") && text.contains("联机余额") -> BankTextParsers.parseCmb(text)
                text.contains("中国银行") && text.contains("记账时间") -> BankTextParsers.parseBoc(text)
                text.contains("外部系统流水") && text.contains("交易余额") -> BankTextParsers.parsePsbc(text)
                else -> throw IllegalArgumentException("尚未支持此银行 PDF 的版式")
            }
        }
    }

    internal fun cents(value: String): Long = BigDecimal(value.replace(",", "").replace("¥", "").trim())
        .movePointRight(2).longValueExact().also { require(it >= 0) { "金额不能为负数" } }

    internal fun signedCents(value: String): Long = BigDecimal(value.replace(",", "").replace("¥", "").trim())
        .movePointRight(2).longValueExact()

    internal fun epoch(value: String): Long = LocalDateTime.parse(value, dateTime).atZone(zone).toInstant().toEpochMilli()

    private fun excelEpoch(value: String): Long {
        val serial = BigDecimal(value)
        val days = serial.toLong()
        val seconds = serial.subtract(BigDecimal(days)).multiply(BigDecimal(86_400)).toLong()
        return LocalDate.of(1899, 12, 30).plusDays(days).atStartOfDay(zone).plusSeconds(seconds).toInstant().toEpochMilli()
    }

    internal fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
