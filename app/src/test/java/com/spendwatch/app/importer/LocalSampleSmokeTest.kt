package com.spendwatch.app.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Optional local check: set SPENDWATCH_SAMPLE_DIR to a folder with private exports. */
class LocalSampleSmokeTest {
    @Test fun verifiedWechatAndAlipayExports() {
        val directory = System.getenv("SPENDWATCH_SAMPLE_DIR")?.let(Path::of)
        assumeTrue(directory != null && Files.isDirectory(directory))
        val files = Files.list(directory!!).use { it.toList() }
        val wechat = files.singleOrNull { it.fileName.toString().startsWith("微信支付账单流水文件") && it.fileName.toString().endsWith(".xlsx") }
        val alipay = files.singleOrNull { it.fileName.toString().startsWith("支付宝交易明细") && it.fileName.toString().endsWith(".csv") }
        assumeTrue(wechat != null && alipay != null)
        val wechatResult = StatementParser.parseWechat(Files.readAllBytes(wechat!!))
        val alipayResult = StatementParser.parseAlipay(Files.readAllBytes(alipay!!))
        assertEquals(39, wechatResult.records.size)
        assertEquals(101, alipayResult.records.size)
        assertTrue(wechatResult.errors.isEmpty())
        assertTrue(alipayResult.errors.isEmpty())
    }
}
