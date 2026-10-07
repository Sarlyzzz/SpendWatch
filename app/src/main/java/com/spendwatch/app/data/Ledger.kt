package com.spendwatch.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.math.abs

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long,
    val amountMinor: Long,
    val refundedMinor: Long = 0,
    val merchant: String,
    val shoppingPlatform: String = "未知",
    val paymentChannel: String = "未知",
    val fundingAccount: String = "未知",
    val category: String = "未分类",
    val status: String = "REVIEW",
    val reviewReason: String = "",
    val userEdited: Boolean = false,
)

@Entity(tableName = "evidence", indices = [Index(value = ["sourceKey"], unique = true), Index("transactionId")])
data class EvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long?,
    val source: String,
    val sourceKey: String,
    val occurredAt: Long,
    val amountMinor: Long,
    val eventType: String,
    val merchant: String,
    val externalRef: String,
    val note: String,
    val shoppingPlatform: String = "未知",
    val paymentChannel: String = "未知",
    val fundingAccount: String = "未知",
    val category: String = "未分类",
    val directBankSpend: Boolean = false,
    val relationType: String = "PRIMARY",
    val importBatchId: Long? = null,
)

@Entity(tableName = "import_batches", indices = [Index(value = ["fileHash"], unique = true)])
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val fileHash: String,
    val source: String,
    val importedAt: Long,
    val parsedCount: Int,
    val errorCount: Int,
)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC, id DESC")
    fun observeTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC, id DESC")
    suspend fun allTransactions(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun transaction(id: Long): TransactionEntity?

    @Query("SELECT * FROM evidence WHERE transactionId = :id ORDER BY occurredAt")
    fun observeEvidence(id: Long): Flow<List<EvidenceEntity>>

    @Query("SELECT * FROM evidence WHERE sourceKey = :key LIMIT 1")
    suspend fun evidenceByKey(key: String): EvidenceEntity?

    @Query("SELECT * FROM evidence WHERE transactionId = :id")
    suspend fun evidenceFor(id: Long): List<EvidenceEntity>

    @Query("SELECT * FROM evidence WHERE id = :id LIMIT 1")
    suspend fun evidenceById(id: Long): EvidenceEntity?

    @Query("SELECT * FROM evidence WHERE importBatchId = :batchId")
    suspend fun evidenceByBatch(batchId: Long): List<EvidenceEntity>

    @Query("SELECT * FROM evidence WHERE source = :source")
    suspend fun evidenceBySource(source: String): List<EvidenceEntity>

    @Query("SELECT * FROM import_batches ORDER BY importedAt DESC")
    fun observeImports(): Flow<List<ImportBatchEntity>>

    @Query("SELECT * FROM import_batches WHERE fileHash = :hash LIMIT 1")
    suspend fun batchByHash(hash: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batches WHERE id = :id LIMIT 1")
    suspend fun batchById(id: Long): ImportBatchEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransaction(item: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidence(item: EvidenceEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBatch(item: ImportBatchEntity): Long

    @Update
    suspend fun updateTransaction(item: TransactionEntity)

    @Query("UPDATE evidence SET transactionId = :transactionId, relationType = :relationType WHERE id = :id")
    suspend fun moveOneEvidence(id: Long, transactionId: Long, relationType: String)

    @Query("UPDATE evidence SET transactionId = :targetId WHERE transactionId = :sourceId")
    suspend fun moveEvidence(sourceId: Long, targetId: Long)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransaction(id: Long)

    @Query("DELETE FROM evidence")
    suspend fun clearEvidence()

    @Query("DELETE FROM evidence WHERE importBatchId = :batchId")
    suspend fun deleteEvidenceByBatch(batchId: Long)

    @Query("DELETE FROM evidence WHERE source = :source")
    suspend fun deleteEvidenceBySource(source: String)

    @Query("DELETE FROM import_batches WHERE id = :id")
    suspend fun deleteBatch(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun clearTransactions()

    @Query("DELETE FROM import_batches")
    suspend fun clearImports()
}

@Database(entities = [TransactionEntity::class, EvidenceEntity::class, ImportBatchEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ledger(): LedgerDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "spendwatch.db").build()
    }
}

enum class RecordKind { PAYMENT, ORDER, BANK_DEBIT, BANK_CREDIT, REFUND, TRANSFER, IGNORE }

data class NormalizedRecord(
    val source: String,
    val sourceKey: String,
    val occurredAt: Long,
    val amountMinor: Long,
    val kind: RecordKind,
    val merchant: String,
    val externalRef: String = "",
    val note: String = "",
    val shoppingPlatform: String = "未知",
    val paymentChannel: String = "未知",
    val fundingAccount: String = "未知",
    val category: String = "未分类",
    val directBankSpend: Boolean = false,
)

class TransactionRepository(private val db: AppDatabase) {
    private val dao = db.ledger()
    val transactions = dao.observeTransactions()
    val imports = dao.observeImports()

    fun evidence(id: Long): Flow<List<EvidenceEntity>> = dao.observeEvidence(id)
    suspend fun transaction(id: Long): TransactionEntity? = dao.transaction(id)
    suspend fun allTransactions(): List<TransactionEntity> = dao.allTransactions()
    suspend fun existingBatch(hash: String): Boolean = dao.batchByHash(hash) != null

    suspend fun removeUnreliableChatOcr(): Int = db.withTransaction {
        val evidence = dao.evidenceBySource("微信支付会话")
        val transactionIds = evidence.mapNotNull { it.transactionId }.distinct()
        dao.deleteEvidenceBySource("微信支付会话")
        transactionIds.forEach { id -> if (dao.evidenceFor(id).isEmpty()) dao.deleteTransaction(id) }
        evidence.size
    }

    suspend fun importBatch(batch: ImportBatchEntity, records: List<NormalizedRecord>): Int = db.withTransaction {
        if (dao.batchByHash(batch.fileHash) != null) return@withTransaction 0
        val batchId = dao.insertBatch(batch)
        var accepted = 0
        for (record in records) if (ingestInternal(record, batchId)) accepted++
        accepted
    }

    suspend fun ingest(record: NormalizedRecord, batchId: Long? = null): Boolean = db.withTransaction {
        ingestInternal(record, batchId)
    }

    private suspend fun ingestInternal(record: NormalizedRecord, batchId: Long?): Boolean {
        if (record.kind == RecordKind.IGNORE || record.amountMinor < 0 || dao.evidenceByKey(record.sourceKey) != null) return false
        val existing = dao.allTransactions()
        val candidates = existing.filter { transaction ->
            record.kind !in setOf(RecordKind.BANK_CREDIT, RecordKind.REFUND) &&
            transaction.amountMinor == record.amountMinor &&
                abs(transaction.occurredAt - record.occurredAt) <= 10 * 60_000L &&
                transaction.status in setOf("PAID", "REVIEW") &&
                (record.externalRef.isNotBlank() && dao.evidenceFor(transaction.id).any {
                    it.externalRef.isNotBlank() && it.externalRef == record.externalRef
                } || compatible(transaction, record) ||
                    (record.source in setOf("微信", "微信支付会话") && dao.evidenceFor(transaction.id).any {
                        it.source in setOf("微信", "微信支付会话") && it.source != record.source &&
                            it.eventType == record.kind.name
                    }))
        }
        val match = candidates.singleOrNull()
        val weakMatches = if (match == null && record.kind in setOf(RecordKind.PAYMENT, RecordKind.BANK_DEBIT)) {
            existing.filter { it.status == "PAID" && it.amountMinor == record.amountMinor &&
                abs(it.occurredAt - record.occurredAt) <= 10 * 60_000L }
        } else emptyList()
        var linkedRefund = false
        val transactionId = when {
            record.kind == RecordKind.REFUND -> {
                // A generic notification such as "支付宝退款" lacks the original order ID.
                // Leave it for review instead of offsetting an unrelated purchase of the same amount.
                val original = if (record.sourceKey.startsWith("notification:")) null else
                    dao.allTransactions().filter {
                        it.occurredAt <= record.occurredAt && it.status == "PAID" &&
                            it.amountMinor - it.refundedMinor >= record.amountMinor &&
                            similar(it.merchant, record.merchant)
                    }.singleOrNull()
                if (original != null) {
                    dao.updateTransaction(original.copy(refundedMinor = original.refundedMinor + record.amountMinor))
                    linkedRefund = true
                    original.id
                } else create(record, "REFUND_REVIEW", "退款未找到原交易")
            }
            match != null -> {
                if (!match.userEdited) {
                    dao.updateTransaction(match.copy(
                        status = if (record.kind == RecordKind.PAYMENT || record.directBankSpend) "PAID" else match.status,
                        merchant = if (match.merchant.isBlank()) record.merchant else match.merchant,
                        shoppingPlatform = if (match.shoppingPlatform == "未知") record.shoppingPlatform else match.shoppingPlatform,
                        paymentChannel = if (record.paymentChannel != "未知") record.paymentChannel else match.paymentChannel,
                        fundingAccount = if (record.fundingAccount != "未知") record.fundingAccount else match.fundingAccount,
                        category = if (match.category == "未分类") record.category else match.category,
                    ))
                }
                match.id
            }
            else -> {
                val status = when (record.kind) {
                    RecordKind.PAYMENT -> if (weakMatches.isEmpty()) "PAID" else "REVIEW"
                    RecordKind.BANK_DEBIT -> if (record.directBankSpend && weakMatches.isEmpty()) "PAID" else "REVIEW"
                    RecordKind.BANK_CREDIT -> "CREDIT_REVIEW"
                    else -> "REVIEW"
                }
                val reason = when (record.kind) {
                    RecordKind.BANK_DEBIT -> if (weakMatches.isEmpty()) "银行扣款待与支付平台核对" else "疑似与已有付款重复"
                    RecordKind.PAYMENT -> if (weakMatches.isEmpty()) "" else "疑似与已有扣款重复"
                    RecordKind.BANK_CREDIT -> "入账待判断是否退款"
                    RecordKind.TRANSFER -> "转账是否为消费"
                    RecordKind.ORDER -> "订单尚无付款证据"
                    else -> ""
                }
                create(record, status, reason)
            }
        }
        return dao.insertEvidence(EvidenceEntity(
            transactionId = transactionId, source = record.source, sourceKey = record.sourceKey,
            occurredAt = record.occurredAt, amountMinor = record.amountMinor,
            eventType = record.kind.name, merchant = record.merchant, externalRef = record.externalRef,
            note = record.note, shoppingPlatform = record.shoppingPlatform,
            paymentChannel = record.paymentChannel, fundingAccount = record.fundingAccount,
            category = record.category, directBankSpend = record.directBankSpend,
            relationType = if (linkedRefund) "REFUND" else "PRIMARY", importBatchId = batchId,
        )) > 0
    }

    private suspend fun create(record: NormalizedRecord, status: String, reason: String): Long = dao.insertTransaction(
        TransactionEntity(
            occurredAt = record.occurredAt, amountMinor = record.amountMinor,
            merchant = record.merchant, shoppingPlatform = record.shoppingPlatform,
            paymentChannel = record.paymentChannel, fundingAccount = record.fundingAccount,
            category = record.category, status = status, reviewReason = reason,
        )
    )

    private fun compatible(transaction: TransactionEntity, record: NormalizedRecord): Boolean {
        if (!similar(transaction.merchant, record.merchant)) return false
        if (record.kind == RecordKind.BANK_DEBIT && transaction.paymentChannel == "银行卡直付") return false
        return transaction.shoppingPlatform != record.shoppingPlatform ||
            transaction.paymentChannel != record.paymentChannel || record.kind == RecordKind.ORDER
    }

    private fun similar(a: String, b: String): Boolean {
        val x = a.lowercase(Locale.ROOT).replace(Regex("[\\s·*（）()]"), "")
        val y = b.lowercase(Locale.ROOT).replace(Regex("[\\s·*（）()]"), "")
        return x.isNotBlank() && y.isNotBlank() && (x == y || (x.length >= 4 && y.length >= 4 && (x.contains(y) || y.contains(x))))
    }

    suspend fun setStatus(id: Long, status: String) = db.withTransaction {
        dao.transaction(id)?.let { dao.updateTransaction(it.copy(status = status, reviewReason = "", userEdited = true)) }
    }

    suspend fun restoreExcluded(id: Long) = db.withTransaction {
        val item = dao.transaction(id) ?: return@withTransaction
        if (item.status != "EXCLUDED") return@withTransaction
        dao.updateTransaction(item.copy(status = "REVIEW", reviewReason = ""))
        recalculate(id)
    }

    suspend fun updateDetails(id: Long, platform: String, channel: String, category: String) = db.withTransaction {
        dao.transaction(id)?.let { dao.updateTransaction(it.copy(shoppingPlatform = platform, paymentChannel = channel, category = category, userEdited = true)) }
    }

    suspend fun merge(targetId: Long, sourceId: Long): Boolean = db.withTransaction {
        val target = dao.transaction(targetId) ?: return@withTransaction false
        val source = dao.transaction(sourceId) ?: return@withTransaction false
        if (target.amountMinor != source.amountMinor || targetId == sourceId) return@withTransaction false
        dao.moveEvidence(sourceId, targetId)
        dao.updateTransaction(target.copy(
            status = if (target.status == "PAID" || source.status == "PAID") "PAID" else target.status,
            refundedMinor = (target.refundedMinor + source.refundedMinor).coerceAtMost(target.amountMinor),
            shoppingPlatform = if (target.shoppingPlatform == "未知") source.shoppingPlatform else target.shoppingPlatform,
            paymentChannel = if (target.paymentChannel == "未知") source.paymentChannel else target.paymentChannel,
            fundingAccount = if (target.fundingAccount == "未知") source.fundingAccount else target.fundingAccount,
            userEdited = true,
        ))
        dao.deleteTransaction(sourceId)
        true
    }

    suspend fun linkRefund(refundId: Long, targetId: Long): Boolean = db.withTransaction {
        val refund = dao.transaction(refundId) ?: return@withTransaction false
        val target = dao.transaction(targetId) ?: return@withTransaction false
        if (refund.status !in setOf("REFUND_REVIEW", "CREDIT_REVIEW") || target.status != "PAID" ||
            target.occurredAt > refund.occurredAt || target.amountMinor - target.refundedMinor < refund.amountMinor || refundId == targetId) return@withTransaction false
        for (evidence in dao.evidenceFor(refundId)) dao.moveOneEvidence(evidence.id, targetId, "REFUND")
        dao.updateTransaction(target.copy(refundedMinor = target.refundedMinor + refund.amountMinor, userEdited = true))
        dao.deleteTransaction(refundId)
        true
    }

    suspend fun splitEvidence(evidenceId: Long): Boolean = db.withTransaction {
        val evidence = dao.evidenceById(evidenceId) ?: return@withTransaction false
        val originalId = evidence.transactionId ?: return@withTransaction false
        if (dao.evidenceFor(originalId).size < 2) return@withTransaction false
        val status = when (evidence.eventType) {
            "PAYMENT" -> "PAID"
            "BANK_DEBIT" -> if (evidence.directBankSpend) "PAID" else "REVIEW"
            "REFUND" -> "REFUND_REVIEW"
            "BANK_CREDIT" -> "CREDIT_REVIEW"
            else -> "REVIEW"
        }
        val newId = dao.insertTransaction(TransactionEntity(
            occurredAt = evidence.occurredAt, amountMinor = evidence.amountMinor,
            merchant = evidence.merchant, shoppingPlatform = evidence.shoppingPlatform,
            paymentChannel = evidence.paymentChannel, fundingAccount = evidence.fundingAccount,
            category = evidence.category, status = status, reviewReason = "从合并交易拆出", userEdited = true,
        ))
        dao.moveOneEvidence(evidenceId, newId, "PRIMARY")
        recalculate(originalId)
        true
    }

    suspend fun removeImport(batchId: Long): Boolean = db.withTransaction {
        if (dao.batchById(batchId) == null) return@withTransaction false
        val affected = dao.evidenceByBatch(batchId).mapNotNull { it.transactionId }.distinct()
        dao.deleteEvidenceByBatch(batchId)
        dao.deleteBatch(batchId)
        affected.forEach { recalculate(it) }
        true
    }

    private suspend fun recalculate(id: Long) {
        val transaction = dao.transaction(id) ?: return
        val evidence = dao.evidenceFor(id)
        if (evidence.isEmpty()) { dao.deleteTransaction(id); return }
        val refund = evidence.filter { it.relationType == "REFUND" }.sumOf { it.amountMinor }
        val primary = evidence.filter { it.relationType == "PRIMARY" }
        if (primary.isEmpty()) {
            // Undoing the original payment import must not leave its surviving refunds orphaned.
            evidence.forEach { remaining ->
                val newId = dao.insertTransaction(TransactionEntity(
                    occurredAt = remaining.occurredAt, amountMinor = remaining.amountMinor,
                    merchant = remaining.merchant, paymentChannel = remaining.paymentChannel,
                    fundingAccount = remaining.fundingAccount,
                    status = if (remaining.eventType == "BANK_CREDIT") "CREDIT_REVIEW" else "REFUND_REVIEW",
                    reviewReason = "原消费证据已撤销，请重新关联退款",
                ))
                dao.moveOneEvidence(remaining.id, newId, "PRIMARY")
            }
            dao.deleteTransaction(id)
            return
        }
        val decisive = primary.firstOrNull { it.eventType == "PAYMENT" }
            ?: primary.firstOrNull { it.eventType == "BANK_DEBIT" && it.directBankSpend }
            ?: primary.first()
        val status = when {
            transaction.status == "EXCLUDED" -> "EXCLUDED"
            primary.any { it.eventType == "PAYMENT" || it.eventType == "BANK_DEBIT" && it.directBankSpend } -> "PAID"
            decisive.eventType == "BANK_CREDIT" -> "CREDIT_REVIEW"
            decisive.eventType == "REFUND" -> "REFUND_REVIEW"
            else -> "REVIEW"
        }
        dao.updateTransaction(transaction.copy(
            occurredAt = decisive.occurredAt, amountMinor = decisive.amountMinor,
            refundedMinor = refund.coerceAtMost(decisive.amountMinor), status = status,
            merchant = if (transaction.userEdited) transaction.merchant else decisive.merchant,
            shoppingPlatform = if (transaction.userEdited) transaction.shoppingPlatform else
                primary.firstOrNull { it.shoppingPlatform != "未知" }?.shoppingPlatform ?: "未知",
            paymentChannel = if (transaction.userEdited) transaction.paymentChannel else
                primary.firstOrNull { it.paymentChannel != "未知" }?.paymentChannel ?: "未知",
            fundingAccount = if (transaction.userEdited) transaction.fundingAccount else
                primary.firstOrNull { it.fundingAccount != "未知" }?.fundingAccount ?: "未知",
            category = if (transaction.userEdited) transaction.category else
                primary.firstOrNull { it.category != "未分类" }?.category ?: "未分类",
        ))
    }

    suspend fun clearAll() = db.withTransaction {
        dao.clearEvidence()
        dao.clearTransactions()
        dao.clearImports()
    }
}
