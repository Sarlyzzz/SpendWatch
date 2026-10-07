package com.spendwatch.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendwatch.app.data.ImportBatchEntity
import com.spendwatch.app.data.EvidenceEntity
import com.spendwatch.app.importer.ParseResult
import com.spendwatch.app.importer.PasswordNeeded
import com.spendwatch.app.importer.StatementParser
import com.spendwatch.app.importer.WrongPassword
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ImportUiState(
    val fileName: String = "",
    val parsed: ParseResult? = null,
    val message: String = "",
    val requiresPassword: Boolean = false,
    val busy: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as SpendWatchApplication).repository
    val transactions = repository.transactions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val imports = repository.imports.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _import = MutableStateFlow(ImportUiState())
    val import: StateFlow<ImportUiState> = _import
    private var pendingBytes: ByteArray? = null
    private var pendingHash: String = ""
    private var parsingJob: Job? = null
    val message = MutableStateFlow<String?>(null)

    fun evidence(id: Long): Flow<List<EvidenceEntity>> = repository.evidence(id)

    fun loadFile(fileName: String, uri: Uri, password: CharArray? = null) {
        if (_import.value.busy) { password?.fill('\u0000'); return }
        val app = getApplication<SpendWatchApplication>()
        _import.value = ImportUiState(fileName = fileName, busy = true)
        parsingJob = viewModelScope.launch {
            try {
                val bytes = pendingBytes ?: withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            require(output.size() <= 15 * 1024 * 1024) { "文件超过 15 MB" }
                        }
                        output.toByteArray()
                    } ?: error("无法读取文件")
                }
                pendingBytes = bytes
                pendingHash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                if (repository.existingBatch(pendingHash)) {
                    _import.value = ImportUiState(fileName, message = "这份文件已经导入过")
                    return@launch
                }
                val result = withContext(Dispatchers.IO) { StatementParser.parse(app, fileName, bytes, password) }
                _import.value = ImportUiState(fileName = fileName, parsed = result,
                    message = "识别 ${result.records.size} 条，需检查 ${result.errors.size} 条")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (need: PasswordNeeded) {
                _import.value = ImportUiState(fileName, message = need.message.orEmpty(), requiresPassword = true)
            } catch (wrong: WrongPassword) {
                _import.value = ImportUiState(fileName, message = wrong.message.orEmpty(), requiresPassword = true)
            } catch (error: Exception) {
                _import.value = ImportUiState(fileName, message = error.message ?: "导入失败")
            } finally {
                password?.fill('\u0000')
            }
        }
    }

    fun commitImport() {
        val state = _import.value
        if (state.busy) return
        val result = state.parsed ?: return
        val hash = pendingHash
        _import.value = state.copy(busy = true)
        viewModelScope.launch {
            try {
                val accepted = repository.importBatch(
                    ImportBatchEntity(fileName = state.fileName, fileHash = hash, source = result.source,
                        importedAt = System.currentTimeMillis(), parsedCount = result.records.size,
                        errorCount = result.errors.size), result.records
                )
                _import.value = ImportUiState(message = "导入完成：新增 $accepted 条记录；${result.errors.size} 条需检查")
                pendingBytes = null
                pendingHash = ""
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _import.value = state.copy(busy = false, message = error.message ?: "导入失败")
            }
        }
    }

    fun cancelImport() {
        parsingJob?.cancel()
        pendingBytes = null
        pendingHash = ""
        _import.value = ImportUiState()
    }

    private fun action(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        try { block(); message.value = success }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { message.value = error.message ?: "操作失败，请重试" }
    }

    fun setStatus(id: Long, status: String) = action("交易状态已更新") { repository.setStatus(id, status) }
    fun restoreExcluded(id: Long) = action("交易已恢复") { repository.restoreExcluded(id) }
    fun updateDetails(id: Long, platform: String, channel: String, category: String) = action("交易信息已保存") {
        repository.updateDetails(id, platform, channel, category)
    }
    fun merge(targetId: Long, sourceId: Long) = action("交易已合并") { check(repository.merge(targetId, sourceId)) { "这两笔交易无法合并" } }
    fun linkRefund(refundId: Long, targetId: Long) = action("退款已关联") { check(repository.linkRefund(refundId, targetId)) { "退款金额或交易时间不符合关联条件" } }
    fun splitEvidence(evidenceId: Long) = action("证据已拆出为独立交易") { check(repository.splitEvidence(evidenceId)) { "证据无法拆出" } }
    fun removeImport(batchId: Long) = action("导入已撤销，金额已重算") { check(repository.removeImport(batchId)) { "该导入记录已不存在" } }
    fun clearData() {
        if (_import.value.busy) { message.value = "请等待当前账单处理完成后再清空数据"; return }
        cancelImport()
        action("本机交易和诊断记录已清空") {
            repository.clearAll()
            com.spendwatch.app.notifications.NotificationProbe.clear(getApplication())
            com.spendwatch.app.notifications.MonitoringDiagnostics.clear(getApplication())
        }
    }

    fun exportCsv(uri: Uri) {
        val app = getApplication<SpendWatchApplication>()
        action("CSV 已导出到所选位置") { withContext(Dispatchers.IO) {
            val data = repository.allTransactions()
            val header = "时间,金额(元),退款(元),状态,商户,消费来源,付款渠道,扣款账户,分类\n"
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"))
            fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
            val content = buildString {
                append('\uFEFF'); append(header)
                for (item in data) {
                    append(listOf(
                        formatter.format(Instant.ofEpochMilli(item.occurredAt)),
                        java.math.BigDecimal.valueOf(item.amountMinor, 2).toPlainString(),
                        java.math.BigDecimal.valueOf(item.refundedMinor, 2).toPlainString(),
                        item.status, item.merchant, item.shoppingPlatform, item.paymentChannel,
                        item.fundingAccount, item.category,
                    ).joinToString(",") { quote(it) })
                    append('\n')
                }
            }
            val output = app.contentResolver.openOutputStream(uri) ?: error("无法写入所选文件")
            output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        } }
    }
}
