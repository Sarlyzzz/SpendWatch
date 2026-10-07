package com.spendwatch.app

import android.app.DatePickerDialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.ViewModelProvider
import com.spendwatch.app.data.TransactionEntity
import com.spendwatch.app.notifications.AppCatalog
import com.spendwatch.app.notifications.MonitoringDiagnostics
import com.spendwatch.app.notifications.PaymentNotificationService
import com.spendwatch.app.notifications.NotificationProbe
import com.spendwatch.app.screen.WechatScreenService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var model: MainViewModel
    private var monitorRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
        model = ViewModelProvider(this)[MainViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = appColorScheme) { SpendWatchScreen(model, monitorRevision) { monitorRevision++ } }
        }
    }

    override fun onResume() {
        super.onResume()
        monitorRevision++
        if (AppCatalog.notificationAccess(this) && !PaymentNotificationService.isConnected()) {
            PaymentNotificationService.scanActive(this)
        }
    }
}

private val zone = ZoneId.of("Asia/Shanghai")
private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone)
private val ink = Color(0xFF172B32)
private val muted = Color(0xFF63777E)
private val canvas = Color(0xFFF4F7F6)
private val accent = Color(0xFF137C76)
private val appColorScheme = lightColorScheme(
    primary = accent, onPrimary = Color.White, secondary = Color(0xFF467E91),
    background = canvas, onBackground = ink, surface = Color.White, onSurface = ink,
    surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFEAF1EF),
    outline = Color(0xFFCAD8D5), onSurfaceVariant = muted,
)
private fun money(minor: Long) = "¥" + BigDecimal.valueOf(minor, 2).toPlainString()
private fun statusName(status: String) = when (status) {
    "PAID" -> "已确认"; "EXCLUDED" -> "已排除"; "REFUND_REVIEW" -> "退款待关联"; "CREDIT_REVIEW" -> "入账待核对"; else -> "待核对"
}
private fun eventName(event: String) = when (event) {
    "PAYMENT" -> "付款"; "REFUND" -> "退款"; "BANK_DEBIT" -> "银行扣款"; "BANK_CREDIT" -> "银行入账"
    "ORDER" -> "订单线索"; "TRANSFER" -> "转账"; "IGNORE" -> "不计入消费"; else -> "待核对"
}

@Composable
private fun SpendWatchScreen(model: MainViewModel, monitorRevision: Int, refreshMonitoring: () -> Unit) {
    val context = LocalContext.current
    val transactions by model.transactions.collectAsState()
    val imports by model.imports.collectAsState()
    val importState by model.import.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var month by remember { mutableStateOf(YearMonth.now(zone)) }
    var selected by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshInfo by remember { mutableStateOf("下拉可重新检查通知栏中仍保留的通知") }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val message by model.message.collectAsState()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); model.message.value = null }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "账单"
            model.loadFile(name, uri)
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) model.exportCsv(uri)
    }
    val tabs = listOf("首页", "明细", "待核对", "导入", "监控", "区间", "设置")
    val navState = rememberLazyListState()
    LaunchedEffect(tab) { navState.animateScrollToItem(tab) }
    Scaffold(containerColor = canvas, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp)) {
                Text("月度花销", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = ink)
                Text("看清每一笔，安心过好每一天", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), state = navState,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tabs.indices.toList()) { index ->
                    val title = tabs[index]
                    val selectedTab = tab == index
                    Surface(onClick = { tab = index }, shape = RoundedCornerShape(100.dp),
                        color = if (selectedTab) ink else Color.White,
                        contentColor = if (selectedTab) Color.White else muted,
                        tonalElevation = if (selectedTab) 0.dp else 1.dp) {
                        Text(title, modifier = Modifier.padding(horizontal = 17.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.labelLarge, fontWeight = if (selectedTab) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            when (tab) {
                0 -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
                    refreshing = true
                    val result = PaymentNotificationService.scanActive(context)
                    refreshMonitoring()
                    refreshInfo = result.message
                    scope.launch { delay(700); refreshing = false }
                }, modifier = Modifier.fillMaxSize()) {
                    HomePage(transactions, month, refreshInfo,
                        onPrevious = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) },
                        onOpen = { selected = it }, onImport = { tab = 3 }, onRange = { tab = 5 })
                }
                1 -> TransactionList(transactions, onOpen = { selected = it })
                2 -> TransactionList(transactions.filter { it.status.contains("REVIEW") }, onOpen = { selected = it }, empty = "没有待核对记录")
                3 -> ImportPage(importState, imports, onChoose = {
                    model.cancelImport(); selectedUri = null
                    filePicker.launch(arrayOf("text/*", "application/pdf", "application/zip", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream"))
                }, onRetry = { password ->
                    selectedUri?.let { model.loadFile(importState.fileName, it, password.toCharArray()) }
                }, onCommit = model::commitImport, onCancel = model::cancelImport, onRemove = model::removeImport)
                4 -> MonitoringPage(monitorRevision, onSettings = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }, onChanged = refreshMonitoring)
                5 -> RangePage(transactions, onOpen = { selected = it }, onImport = { tab = 3 })
                6 -> SettingsPage(onExport = { exportPicker.launch("月度花销.csv") }, onClear = { clearConfirm = true })
            }
        }
    }
    selected?.let { item ->
        DetailDialog(transactions.firstOrNull { it.id == item.id } ?: item, transactions, model, onClose = { selected = null })
    }
    if (clearConfirm) AlertDialog(
        onDismissRequest = { clearConfirm = false }, title = { Text("清空本机数据？") },
        text = { Text("交易、原始证据和导入记录都会删除。") },
        confirmButton = { TextButton(onClick = { model.clearData(); clearConfirm = false }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } },
    )
}

@Composable
private fun HomePage(items: List<TransactionEntity>, month: YearMonth, refreshInfo: String, onPrevious: () -> Unit, onNext: () -> Unit,
                     onOpen: (TransactionEntity) -> Unit, onImport: () -> Unit, onRange: () -> Unit) {
    val inMonth = items.filter { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) == month }
    val paid = inMonth.filter { it.status == "PAID" }
    val total = paid.sumOf { it.amountMinor - it.refundedMinor }
    val reviews = inMonth.count { it.status.contains("REVIEW") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = ink)) {
            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(ink, Color(0xFF176D70))))
                .padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onPrevious) { Text("‹ 上月", color = Color.White) }
                    Text("${month.year} 年 ${month.monthValue} 月", color = Color.White,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                    TextButton(onClick = onNext) { Text("下月 ›", color = Color.White) }
                }
                Text("已确认消费净额", color = Color.White.copy(alpha = .82f), style = MaterialTheme.typography.bodyMedium)
                Text(money(total), color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text("${paid.size} 笔已确认  ·  $reviews 笔待核对", color = Color.White.copy(alpha = .88f),
                    style = MaterialTheme.typography.bodySmall)
            }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onImport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("导入账单") }
            OutlinedButton(onClick = onRange, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("区间统计") }
        } }
        item { Text(refreshInfo, style = MaterialTheme.typography.bodySmall, color = muted) }
        item { SectionTitle("付款渠道", "已确认交易") }
        val byChannel = paid.groupBy { it.paymentChannel }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor - it.refundedMinor } }
        items(byChannel.toList().sortedByDescending { it.second }) { (name, amount) ->
            BreakdownRow(name, amount, total)
        }
        item { SectionTitle("消费来源", "购物平台与商户来源") }
        val byPlatform = paid.groupBy { it.shoppingPlatform }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor - it.refundedMinor } }
        items(byPlatform.toList().sortedByDescending { it.second }) { (name, amount) ->
            BreakdownRow(name, amount, total)
        }
        item { SectionTitle("最近交易", "查看明细与证据") }
        items(inMonth.take(10), key = { it.id }) { item -> TransactionCard(item, onOpen) }
        if (inMonth.isEmpty()) item { EmptyCard("本月还没有交易", "导入账单或等待付款通知后，这里会显示记录。") }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ink)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

@Composable
private fun BreakdownRow(name: String, amount: Long, total: Long) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, fontWeight = FontWeight.Medium, color = ink)
                Text(money(amount), fontWeight = FontWeight.SemiBold, color = ink)
            }
            LinearProgressIndicator(progress = { if (total > 0) (amount.toFloat() / total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(5.dp), color = accent, trackColor = Color(0xFFE7EFED))
        }
    }
}

@Composable
private fun EmptyCard(title: String, hint: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = ink)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun RangePage(items: List<TransactionEntity>, onOpen: (TransactionEntity) -> Unit, onImport: () -> Unit) {
    val context = LocalContext.current
    var startText by rememberSaveable { mutableStateOf(LocalDate.now(zone).withDayOfMonth(1).toString()) }
    var endText by rememberSaveable { mutableStateOf(LocalDate.now(zone).toString()) }
    val start = LocalDate.parse(startText)
    val end = LocalDate.parse(endText)
    fun pickDate(current: LocalDate, update: (LocalDate) -> Unit) {
        DatePickerDialog(context, { _, year, month, day -> update(LocalDate.of(year, month + 1, day)) },
            current.year, current.monthValue - 1, current.dayOfMonth).show()
    }
    val valid = !start.isAfter(end)
    val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
    val endExclusive = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val inRange = if (valid) items.filter { it.occurredAt >= startMillis && it.occurredAt < endExclusive } else emptyList()
    val paid = inRange.filter { it.status == "PAID" }
    val pending = inRange.count { it.status.contains("REVIEW") }
    val excluded = inRange.count { it.status == "EXCLUDED" }
    val total = paid.sumOf { it.amountMinor - it.refundedMinor }
    val byChannel = paid.groupBy { it.paymentChannel }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor - it.refundedMinor } }
    val byPlatform = paid.groupBy { it.shoppingPlatform }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor - it.refundedMinor } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("区间统计", "日期包含首尾两天") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { pickDate(start) { startText = it.toString() } }, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("开始日期", style = MaterialTheme.typography.labelSmall)
                Text(startText, style = MaterialTheme.typography.bodyMedium)
            } }
            OutlinedButton(onClick = { pickDate(end) { endText = it.toString() } }, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("结束日期", style = MaterialTheme.typography.labelSmall)
                Text(endText, style = MaterialTheme.typography.bodyMedium)
            } }
        } }
        if (!valid) item { EmptyCard("日期顺序有误", "开始日期不能晚于结束日期。") }
        if (valid) {
            item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ink)) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已确认消费净额", color = Color.White.copy(alpha = .8f))
                Text(money(total), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${inRange.size} 笔记录 · 已确认 ${paid.size} · 待核对 $pending · 已排除 $excluded",
                    color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall)
            } } }
            item { Text("仅统计本机已导入或通知已识别的交易；其他 App 中未导出的账单不会自动读取。",
                style = MaterialTheme.typography.bodySmall, color = muted) }
            item { Button(onClick = onImport, shape = RoundedCornerShape(16.dp)) { Text("导入更多账单") } }
            item { SectionTitle("付款渠道", "已确认交易") }
            items(byChannel.toList().sortedByDescending { it.second }) { (name, amount) ->
                BreakdownRow(name, amount, total)
            }
            item { SectionTitle("消费来源", "购物平台与商户来源") }
            items(byPlatform.toList().sortedByDescending { it.second }) { (name, amount) ->
                BreakdownRow(name, amount, total)
            }
            item { SectionTitle("区间内全部记录", "含待核对与已排除") }
            items(inRange, key = { it.id }) { item -> TransactionCard(item, onOpen) }
            if (inRange.isEmpty()) item { EmptyCard("这段时间没有记录", "可以调整日期或导入对应时间段的账单。") }
        }
    }
}

@Composable
private fun TransactionList(items: List<TransactionEntity>, onOpen: (TransactionEntity) -> Unit, empty: String = "暂无交易") {
    var query by rememberSaveable(empty) { mutableStateOf("") }
    val filtered = items.filter { query.isBlank() ||
        "${it.merchant} ${it.shoppingPlatform} ${it.paymentChannel} ${it.fundingAccount} ${it.category} ${money(it.amountMinor)}".contains(query.trim(), true) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { OutlinedTextField(query, { query = it }, label = { Text("搜索商户、金额、渠道或分类") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Text(if (filtered.isEmpty()) empty else "共 ${filtered.size} 笔", style = MaterialTheme.typography.bodySmall, color = muted) }
        items(filtered, key = { it.id }) { item -> TransactionCard(item, onOpen) }
        if (filtered.isEmpty()) item { EmptyCard(empty, "试试调整搜索词，或导入更多账单。") }
    }
}

@Composable
private fun TransactionCard(item: TransactionEntity, onOpen: (TransactionEntity) -> Unit) {
    val badgeColor = when (item.status) {
        "PAID" -> Color(0xFFE0F3EE)
        "EXCLUDED" -> Color(0xFFE9ECEB)
        else -> Color(0xFFFFF0D9)
    }
    val statusColor = when (item.status) {
        "PAID" -> Color(0xFF0B7668)
        "EXCLUDED" -> muted
        else -> Color(0xFF99610C)
    }
    Card(Modifier.fillMaxWidth().clickable { onOpen(item) }, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(Color(0xFFE9F3F1), CircleShape), contentAlignment = Alignment.Center) {
                Text(item.merchant.ifBlank { "?" }.take(1), color = accent, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.merchant.ifBlank { "未知商户" }, fontWeight = FontWeight.SemiBold, maxLines = 1, color = ink)
                Text("${timeFormat.format(Instant.ofEpochMilli(item.occurredAt))}  ·  ${item.paymentChannel}",
                    style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(money(item.amountMinor - item.refundedMinor), fontWeight = FontWeight.Bold, color = ink)
                Surface(shape = RoundedCornerShape(100.dp), color = badgeColor) {
                    Text(statusName(item.status), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall, color = statusColor)
                }
            }
        }
    }
}

@Composable
private fun DetailDialog(item: TransactionEntity, transactions: List<TransactionEntity>, model: MainViewModel, onClose: () -> Unit) {
    val evidence by remember(item.id) { model.evidence(item.id) }.collectAsState(initial = emptyList())
    var platform by remember(item.id) { mutableStateOf(item.shoppingPlatform) }
    var channel by remember(item.id) { mutableStateOf(item.paymentChannel) }
    var category by remember(item.id) { mutableStateOf(item.category) }
    var candidateQuery by remember(item.id) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("交易详情") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${item.merchant} · ${money(item.amountMinor)}")
            Text("扣款账户：${item.fundingAccount}")
            Text("状态：${statusName(item.status)}${if (item.reviewReason.isNotBlank()) " · ${item.reviewReason}" else ""}")
            OutlinedTextField(platform, { platform = it }, label = { Text("消费来源") }, singleLine = true)
            OutlinedTextField(channel, { channel = it }, label = { Text("付款渠道") }, singleLine = true)
            OutlinedTextField(category, { category = it }, label = { Text("分类") }, singleLine = true)
            Text("证据：", style = MaterialTheme.typography.bodySmall)
            evidence.forEach { source ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${source.source} · ${eventName(source.eventType)}", style = MaterialTheme.typography.bodySmall)
                    if (evidence.size > 1) TextButton(onClick = { model.splitEvidence(source.id); onClose() }) { Text("拆出") }
                }
            }
            if (item.status in setOf("REVIEW", "PAID")) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.status == "REVIEW") Button(onClick = { model.setStatus(item.id, "PAID"); onClose() }) { Text("计入消费") }
                OutlinedButton(onClick = { model.setStatus(item.id, "EXCLUDED"); onClose() }) { Text("排除") }
            }
            if (item.status == "EXCLUDED") OutlinedButton(onClick = { model.restoreExcluded(item.id); onClose() }) { Text("恢复交易") }
            if (item.status.contains("REVIEW")) OutlinedTextField(candidateQuery, { candidateQuery = it }, label = { Text("查找要合并或关联的原交易") }, singleLine = true)
            if (item.status in setOf("REFUND_REVIEW", "CREDIT_REVIEW")) {
                OutlinedButton(onClick = { model.setStatus(item.id, "EXCLUDED"); onClose() }) { Text("不是退款，排除") }
                Text("关联退款到消费：", style = MaterialTheme.typography.bodySmall)
                transactions.filter { it.status == "PAID" && it.occurredAt <= item.occurredAt &&
                    it.amountMinor - it.refundedMinor >= item.amountMinor &&
                    (candidateQuery.isBlank() || "${it.merchant} ${money(it.amountMinor)}".contains(candidateQuery, true)) }.forEach { target ->
                    TextButton(onClick = { model.linkRefund(item.id, target.id); onClose() }) {
                        Text("${target.merchant.take(15)} · ${money(target.amountMinor)} · ${timeFormat.format(Instant.ofEpochMilli(target.occurredAt))}")
                    }
                }
            }
            val candidates = transactions.filter { it.id != item.id && it.amountMinor == item.amountMinor &&
                it.status in setOf("PAID", "REVIEW") && (candidateQuery.isBlank() || it.merchant.contains(candidateQuery, true)) }
            if (item.status == "REVIEW" && candidates.isNotEmpty()) {
                Text("手动合并到：", style = MaterialTheme.typography.bodySmall)
                candidates.forEach { target -> TextButton(onClick = { model.merge(target.id, item.id); onClose() }) {
                    Text("${target.merchant.take(15)} · ${timeFormat.format(Instant.ofEpochMilli(target.occurredAt))}")
                } }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        model.updateDetails(item.id, platform, channel, category); onClose()
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onClose) { Text("关闭") } })
}

@Composable
private fun ImportPage(state: ImportUiState, imports: List<com.spendwatch.app.data.ImportBatchEntity>,
                       onChoose: () -> Unit, onRetry: (String) -> Unit, onCommit: () -> Unit,
                       onCancel: () -> Unit, onRemove: (Long) -> Unit) {
    var password by remember(state.fileName) { mutableStateOf("") }
    var pendingRemove by remember { mutableStateOf<Long?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE5F3EF))) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("导入账单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ink)
                Text("支持支付宝、微信和已适配银行的流水。文件仅在本机解析，导入前可以预览。",
                    style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        } }
        item { Button(onClick = onChoose, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)) { Text("选择账单文件") } }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在处理账单…") }
        if (state.fileName.isNotBlank()) item { Text("文件：${state.fileName}") }
        if (state.message.isNotBlank()) item { Text(state.message) }
        if (state.requiresPassword) {
            item { OutlinedTextField(password, { password = it }, label = { Text("文件密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation()) }
            item { Button(onClick = { onRetry(password); password = "" }, enabled = password.isNotBlank() && !state.busy) { Text("解密并预览") } }
        }
        state.parsed?.let { result ->
            item { Text("来源：${result.source}，有效记录 ${result.records.size} 条，解析问题 ${result.errors.size} 条") }
            if (result.errors.isNotEmpty()) item { Text(result.errors.take(5).joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
            item { SectionTitle("记录预览", "前 8 条") }
            items(result.records.take(8)) { record ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Text("${timeFormat.format(Instant.ofEpochMilli(record.occurredAt))} · ${record.merchant.take(24)} · ${money(record.amountMinor)} · ${eventName(record.kind.name)}",
                        modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCommit, enabled = !state.busy) { Text("确认导入") }
                OutlinedButton(onClick = onCancel, enabled = !state.busy) { Text("取消") }
            } }
        }
        item { SectionTitle("导入历史", "可撤销单次导入") }
        items(imports, key = { it.id }) { batch ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(batch.source, fontWeight = FontWeight.SemiBold)
                        Text("${batch.fileName.take(24)} · ${batch.parsedCount} 条", style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    TextButton(onClick = { pendingRemove = batch.id }) { Text("撤销") }
                }
            }
        }
        if (imports.isEmpty()) item { EmptyCard("还没有导入记录", "选择账单文件后，导入历史会显示在这里。") }
    }
    pendingRemove?.let { id -> AlertDialog(
        onDismissRequest = { pendingRemove = null }, title = { Text("撤销这次导入？") },
        text = { Text("该文件带来的记录会移除，其余交易将重新计算。") },
        confirmButton = { TextButton(onClick = { onRemove(id); pendingRemove = null }) { Text("撤销导入") } },
        dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("取消") } },
    ) }
}

@Composable
private fun MonitoringPage(revision: Int, onSettings: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val diagnosticsRevision = MonitoringDiagnostics.revision.collectAsState().value
    val diagnosticEvents = remember(diagnosticsRevision, revision) { MonitoringDiagnostics.history(context) }
    var scanMessage by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun sendProbe() {
        scope.launch {
            probing = true
            try {
                scanMessage = NotificationProbe.send(context)
                if (scanMessage.startsWith("测试通知已发送")) {
                    delay(5_000)
                    scanMessage = NotificationProbe.result(context)
                }
            } catch (_: Exception) { scanMessage = "测试通知发送失败，请检查本 App 的通知发送权限" }
            finally { probing = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) sendProbe() else scanMessage = "未允许发送测试通知；这与“通知访问”是两项独立权限"
    }
    val access = AppCatalog.notificationAccess(context)
    val screenEnabled = WechatScreenService.enabled(context)
    val screenAccess = WechatScreenService.systemAccess(context)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = if (access && PaymentNotificationService.isConnected()) Color(0xFFE5F3EF) else Color(0xFFFFF0D9))) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("通知监听", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (access) "系统通知访问已授权" else "系统通知访问未授权", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onSettings) { Text(if (access) "打开通知访问设置" else "前往系统设置授权") }
            if (access) Text(if (PaymentNotificationService.isConnected()) "通知监听：已连接" else "通知监听：未连接，请检查系统通知访问设置",
                style = MaterialTheme.typography.bodySmall)
            Text("只解析系统实际发出的交易通知。微信转账若没有系统通知，关闭消息去敏也无法自动记录；请导入微信账单。",
                style = MaterialTheme.typography.bodySmall, color = muted)
            OutlinedButton(onClick = {
                scanMessage = PaymentNotificationService.scanActive(context).message
                onChanged()
            }) { Text("重新检查通知栏") }
            if (scanMessage.isNotBlank()) Text(scanMessage, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !probing, onClick = {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else sendProbe()
            }) { Text(if (probing) "正在等待系统回调…" else "发送测试通知，检查监听") }
            Text("无需付款，测试不会写入账本。测试通过仅说明系统能送达本 App 的通知。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                    .onFailure { scanMessage = "无法直接打开，请到系统设置查找月度花销" }
            }) { Text("打开本 App 系统设置") }
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("月度花销诊断", MonitoringDiagnostics.report(context)))
                scanMessage = "诊断报告已复制；包含设备、权限和处理结果，不含通知正文、金额或账号"
            }) { Text("复制诊断报告") }
            }
        } }
        item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("微信支付会话与账单识别", fontWeight = FontWeight.Bold)
                        Text(if (screenAccess) "系统无障碍权限已开启" else "需要开启系统无障碍权限",
                            style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    Switch(checked = screenEnabled, onCheckedChange = {
                        WechatScreenService.setEnabled(context, it)
                        onChanged()
                    })
                }
                Text("打开微信支付会话时识别可见的支付卡片；明确的商户支付计入消费，转账凭证进入待核对。打开转账账单详情也可识别单号。微信在后台或未打开的历史消息无法读取；截图和原始文字不保存。",
                    style = MaterialTheme.typography.bodySmall, color = muted)
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                    Text("打开无障碍设置")
                }
                MonitoringDiagnostics.source(context, "微信屏幕")?.let { diagnostic ->
                    Text("最近结果：${diagnostic.outcome}", style = MaterialTheme.typography.bodySmall)
                }
            }
        } }
        items(AppCatalog.apps) { app ->
            val installed = AppCatalog.installed(context, app)
            val enabled = AppCatalog.enabled(context, app)
            val diagnostic = MonitoringDiagnostics.source(context, app.packageName)
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)) { Row(Modifier.padding(14.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.name, fontWeight = FontWeight.SemiBold)
                    Text(when {
                        !installed -> "未安装"
                        !app.notificationReady -> "已安装 · 当前转账无可用通知，使用账单导入"
                        !access -> "已安装 · 等待通知授权"
                        !enabled -> "已安装 · 已关闭"
                        app.name in setOf("淘宝", "抖音") -> "已安装 · 仅收集订单线索，不直接计消费"
                        app.name in setOf("招商银行", "中国银行", "邮储银行") -> "已安装 · 仅识别含金额通知，流水可补齐"
                        else -> "已安装 · ${if (enabled) "识别规则待真机验证" else "已关闭"}"
                    }, style = MaterialTheme.typography.bodySmall)
                    if (diagnostic != null) Text("最近通知 ${timeFormat.format(Instant.ofEpochMilli(diagnostic.at))}：${diagnostic.outcome}",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, onCheckedChange = {
                    AppCatalog.setEnabled(context, app, it)
                    if (it) scanMessage = PaymentNotificationService.scanActive(context).message
                    onChanged()
                }, enabled = installed && app.notificationReady)
            } }
        }
        item { SectionTitle("最近诊断", "仅保留处理结果") }
        items(diagnosticEvents.take(10)) { event ->
            val name = AppCatalog.apps.firstOrNull { it.packageName == event.source }?.name ?: event.source
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Text("${timeFormat.format(Instant.ofEpochMilli(event.at))} · $name\n${event.outcome}",
                    modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsPage(onExport: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("本机数据", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("账单、交易与诊断记录默认只保存在这台手机上。", style = MaterialTheme.typography.bodyMedium, color = muted)
                Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("导出交易 CSV") }
        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("清空本机数据") }
        Text("月份按北京时间计算。支付宝与微信账单可补齐通知遗漏；无法确定消费性质的银行扣款会进入待核对。",
            style = MaterialTheme.typography.bodySmall, color = muted)
    }
}
