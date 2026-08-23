package com.ripenai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ripenai.data.local.FarmerContainerEntity
import com.ripenai.data.local.FarmerSensorReadingEntity
import com.ripenai.domain.FarmerFeedbackLabel
import com.ripenai.ui.FarmerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private val FarmerGreen = Color(0xFF0F9D58)
private val FarmerNavy = Color(0xFF172235)
private val FarmerMuted = Color(0xFF64748B)
private val SafeGreen = Color(0xFF15803D)
private val AttentionAmber = Color(0xFFB45309)
private val UrgentRed = Color(0xFFB91C1C)

@Composable
fun FarmerModeScreen(viewModel: FarmerViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val selected by viewModel.selectedContainer.collectAsState()
    if (selected == null) FarmerDashboard(viewModel, onBack, modifier)
    else FarmerContainerDetail(viewModel, selected!!, modifier)
}

@Composable
private fun FarmerDashboard(viewModel: FarmerViewModel, onBack: () -> Unit, modifier: Modifier) {
    val containers by viewModel.containers.collectAsState()
    val syncing by viewModel.isSyncing.collectAsState()
    val message by viewModel.syncMessage.collectAsState()
    val error by viewModel.error.collectAsState()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFFF8FAFC)).navigationBarsPadding()) {
        Surface(modifier = Modifier.fillMaxWidth(), color = Color.White, shadowElevation = 2.dp) {
            FarmerHeader("Mode Petani", "Pantau kondisi setiap wadah", onBack, viewModel::syncAll, { showAddDialog = true }, syncing)
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item { LocalSyncCard() }
            message?.let { item { FeedbackCard(it, false) } }
            error?.let { item { FeedbackCard(it, true) } }
            if (containers.isEmpty()) {
                item { EmptyFarmerState({ showAddDialog = true }, viewModel::createDemoData) }
            } else {
                item { Text("Wadah terdaftar", color = FarmerNavy, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                items(containers, key = { it.id }) { container ->
                    ContainerSummaryCard(container) { viewModel.selectContainer(container.id) }
                }
                item {
                    OutlinedButton(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Tambah wadah lain")
                    }
                }
            }
        }
    }
    if (showAddDialog) AddContainerDialog(
        onDismiss = { showAddDialog = false },
        onSave = { name, fruit, ip, ssid -> viewModel.addContainer(name, fruit, ip, ssid); showAddDialog = false }
    )
}

@Composable
private fun FarmerHeader(title: String, subtitle: String, onBack: () -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit, syncing: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 48.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = FarmerNavy, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = FarmerMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        androidx.compose.material3.IconButton(onClick = onRefresh, enabled = !syncing) {
            if (syncing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = FarmerGreen)
            else Icon(Icons.Default.Refresh, contentDescription = "Sinkronkan semua", tint = FarmerGreen)
        }
        androidx.compose.material3.IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Tambah wadah", tint = FarmerGreen) }
    }
}

@Composable
private fun LocalSyncCard() {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF))) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Wifi, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Sinkronisasi lokal", color = FarmerNavy, fontWeight = FontWeight.Bold)
                Text("Ponsel membaca unit ESP32 melalui WiFi wadah. Internet tidak diperlukan.", color = FarmerMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun EmptyFarmerState(onAdd: () -> Unit, onDemo: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Sensors, contentDescription = null, tint = FarmerGreen, modifier = Modifier.size(48.dp))
            Text("Belum ada wadah", color = FarmerNavy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Tambahkan alamat unit ESP32. Untuk melihat alur dashboard tanpa unit, gunakan data contoh.", color = FarmerMuted, fontSize = 14.sp)
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Tambah wadah") }
            OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth()) { Text("Lihat data contoh (demo)") }
        }
    }
}

@Composable
private fun FeedbackCard(message: String, isError: Boolean) {
    val tint = if (isError) AttentionAmber else SafeGreen
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f))) {
        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isError) Icons.Default.WarningAmber else Icons.Default.CheckCircle, contentDescription = null, tint = tint)
            Text(message, color = FarmerNavy, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ContainerSummaryCard(container: FarmerContainerEntity, onClick: () -> Unit) {
    val riskColor = riskColor(container.latestStatus)
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(container.name, color = FarmerNavy, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${container.fruitType} · ${container.ssid.ifBlank { container.ipAddress }}", color = FarmerMuted, fontSize = 13.sp)
                }
                StatusPill(container.latestStatus, riskColor)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Buka detail", tint = FarmerMuted)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(Icons.Default.DeviceThermostat, "Suhu", container.latestTemperature?.let { "%.1f °C".format(Locale.US, it) } ?: "--", Modifier.weight(1f))
                MetricTile(Icons.Default.WaterDrop, "Lembap", container.latestHumidity?.let { "%.0f%%".format(Locale.US, it) } ?: "--", Modifier.weight(1f))
                MetricTile(Icons.Default.Air, "Gas", container.latestGas?.let { "%.0f".format(Locale.US, it) } ?: "--", Modifier.weight(1f))
            }
            Text(lastSyncLabel(container), color = FarmerMuted, fontSize = 12.sp)
            if (container.lastError != null) Text(container.lastError, color = AttentionAmber, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FarmerContainerDetail(viewModel: FarmerViewModel, container: FarmerContainerEntity, modifier: Modifier) {
    val readings by viewModel.selectedReadings.collectAsState()
    val syncing by viewModel.isSyncing.collectAsState()
    val message by viewModel.syncMessage.collectAsState()
    val error by viewModel.error.collectAsState()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize().background(Color(0xFFF8FAFC)).navigationBarsPadding()) {
        Surface(modifier = Modifier.fillMaxWidth(), color = Color.White, shadowElevation = 2.dp) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 48.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = viewModel::backToDashboard) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") }
                Column(modifier = Modifier.weight(1f)) {
                    Text(container.name, color = FarmerNavy, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("${container.fruitType} · ${container.ipAddress}", color = FarmerMuted, fontSize = 13.sp)
                }
                androidx.compose.material3.IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Hapus wadah", tint = AttentionAmber) }
                androidx.compose.material3.IconButton(onClick = viewModel::syncSelected, enabled = !syncing) {
                    if (syncing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = FarmerGreen)
                    else Icon(Icons.Default.Refresh, contentDescription = "Sinkronkan wadah", tint = FarmerGreen)
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
                Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (container.lastError == null) Icon(Icons.Default.Wifi, contentDescription = null, tint = FarmerGreen) else Icon(Icons.Default.WifiOff, contentDescription = null, tint = AttentionAmber)
                    Column {
                        Text(if (container.lastError == null) "Status sinkronisasi" else "Data cache terakhir", color = FarmerNavy, fontWeight = FontWeight.Bold)
                        Text(lastSyncLabel(container), color = FarmerMuted, fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(Icons.Default.DeviceThermostat, "Suhu", container.latestTemperature?.let { "%.1f °C".format(Locale.US, it) } ?: "--", Modifier.weight(1f))
                MetricTile(Icons.Default.WaterDrop, "Kelembapan", container.latestHumidity?.let { "%.0f%%".format(Locale.US, it) } ?: "--", Modifier.weight(1f))
                MetricTile(Icons.Default.Air, "Gas", container.latestGas?.let { "%.0f".format(Locale.US, it) } ?: "--", Modifier.weight(1f))
            }
        }
        item { RiskCard(container) }
        item {
            FarmerFeedbackCard(container) { label ->
                viewModel.submitFeedback(container.id, label)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tren sensor", color = FarmerNavy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Data tersimpan di perangkat ini; garis dinormalisasi agar tiga sensor mudah dibandingkan.", color = FarmerMuted, fontSize = 12.sp)
            }
        }
        item { TrendChart(readings) }
        item { ReadingLegend() }
        if (readings.isEmpty()) item { Text("Belum ada histori. Tekan sinkronkan setelah ponsel terhubung ke WiFi unit.", color = FarmerMuted, fontSize = 13.sp) }
        message?.let { item { FeedbackCard(it, false) } }
        error?.let { item { FeedbackCard(it, true) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Info, contentDescription = null, tint = FarmerMuted, modifier = Modifier.size(18.dp))
                Text("Rule engine tetap menjadi pengaman utama. Farmer ML V1 membantu membaca pola sensor dan dapat dikalibrasi perlahan dari label pemeriksaan nyata di perangkat ini.", color = FarmerMuted, fontSize = 12.sp)
            }
        }
    }
    }
    if (showDeleteDialog) AlertDialog(
        onDismissRequest = { showDeleteDialog = false },
        title = { Text("Hapus wadah?") },
        text = { Text("Data histori ${container.name} akan dihapus dari perangkat ini.") },
        confirmButton = { TextButton(onClick = { showDeleteDialog = false; viewModel.deleteSelected() }) { Text("Hapus", color = UrgentRed) } },
        dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Batal") } }
    )
}

@Composable
private fun MetricTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, modifier: Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFE2E8F0))) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = null, tint = FarmerGreen, modifier = Modifier.size(19.dp))
            Text(label, color = FarmerMuted, fontSize = 11.sp)
            Text(value, color = FarmerNavy, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun StatusPill(status: String, color: Color) {
    Box(modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 5.dp)) { Text(status, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun RiskCard(container: FarmerContainerEntity) {
    val color = riskColor(container.latestStatus)
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f))) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (container.latestStatus == "Aman") Icons.Default.CheckCircle else Icons.Default.WarningAmber, contentDescription = null, tint = color, modifier = Modifier.size(25.dp))
                Spacer(Modifier.width(9.dp))
                Text("${container.latestStatus}", color = color, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Text(container.latestRiskScore?.let { "Skor risiko: ${(it * 100).toInt()}%" } ?: "Skor risiko belum tersedia", color = FarmerNavy, fontSize = 13.sp)
            container.latestModelScore?.let { score ->
                val confidence = ((container.latestModelConfidence ?: 0f) * 100).toInt()
                Text("Farmer ML V1: ${(score * 100).toInt()}% · keyakinan $confidence%", color = FarmerMuted, fontSize = 12.sp)
            }
            container.latestHoursToAction?.let { hours ->
                Text("Perkiraan tindakan AI: dalam ±${hours.toInt().coerceAtLeast(0)} jam", color = FarmerMuted, fontSize = 12.sp)
            }
            if (container.latestCalibrationSamples > 0) {
                Text("Kalibrasi lokal: ${container.latestCalibrationSamples} label ${container.fruitType}", color = FarmerMuted, fontSize = 12.sp)
            }
            Text(container.latestRecommendation, color = FarmerNavy, fontSize = 14.sp)
        }
    }
}

@Composable
private fun FarmerFeedbackCard(container: FarmerContainerEntity, onFeedback: (FarmerFeedbackLabel) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Bantu model belajar dari pemeriksaanmu", color = FarmerNavy, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Setelah melihat kondisi nyata buah, pilih label yang paling sesuai. Label ini hanya mengubah kalibrasi lokal di ponsel.", color = FarmerMuted, fontSize = 12.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { onFeedback(FarmerFeedbackLabel.SAFE) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    border = BorderStroke(1.dp, SafeGreen)
                ) { Text("Aman", color = SafeGreen, fontSize = 11.sp) }
                OutlinedButton(
                    onClick = { onFeedback(FarmerFeedbackLabel.ATTENTION) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    border = BorderStroke(1.dp, AttentionAmber)
                ) { Text("Perhatian", color = AttentionAmber, fontSize = 11.sp) }
                OutlinedButton(
                    onClick = { onFeedback(FarmerFeedbackLabel.URGENT) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    border = BorderStroke(1.dp, UrgentRed)
                ) { Text("Urgent", color = UrgentRed, fontSize = 11.sp) }
            }
            if (container.latestCalibrationSamples > 0) {
                Text("Pembelajaran lokal aktif untuk ${container.fruitType}: ${container.latestCalibrationSamples} label", color = SafeGreen, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun TrendChart(readings: List<FarmerSensorReadingEntity>) {
    val sorted = readings.sortedBy { it.timestamp }.takeLast(48)
    if (sorted.size < 2) {
        Card(modifier = Modifier.fillMaxWidth().height(190.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Belum cukup data untuk menampilkan tren", color = FarmerMuted, fontSize = 13.sp) }
        }
        return
    }
    Card(modifier = Modifier.fillMaxWidth().height(210.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val width = size.width
            val height = size.height
            val maxTemp = max(1f, sorted.maxOf { it.temperature })
            val maxHumidity = max(1f, sorted.maxOf { it.humidity })
            val maxGas = max(1f, sorted.maxOf { it.gas })
            fun x(index: Int) = if (sorted.lastIndex == 0) 0f else width * index / sorted.lastIndex
            fun y(value: Float, maxValue: Float) = height - (value / maxValue).coerceIn(0f, 1f) * height
            fun path(values: (FarmerSensorReadingEntity) -> Float, maxValue: Float): Path = Path().apply {
                sorted.forEachIndexed { index, reading ->
                    val point = Offset(x(index), y(values(reading), maxValue))
                    if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
            }
            drawLine(Color(0xFFD6DEE8), Offset(0f, height), Offset(width, height), strokeWidth = 1.dp.toPx())
            drawPath(path({ it.temperature }, maxTemp), Color(0xFF2563EB), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path({ it.humidity }, maxHumidity), Color(0xFF0F9D58), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path({ it.gas }, maxGas), Color(0xFFF59E0B), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun ReadingLegend() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendDot(Color(0xFF2563EB), "Suhu")
        LegendDot(Color(0xFF0F9D58), "Kelembapan")
        LegendDot(Color(0xFFF59E0B), "Gas")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).background(color, RoundedCornerShape(50)))
        Text(label, color = FarmerMuted, fontSize = 12.sp)
    }
}

@Composable
private fun AddContainerDialog(onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var fruit by rememberSaveable { mutableStateOf("Pisang") }
    var ip by rememberSaveable { mutableStateOf("192.168.4.1") }
    var ssid by rememberSaveable { mutableStateOf("") }
    val isLocalDemo = ip.trim().let { it == "127.0.0.1" || it.startsWith("127.0.0.1:") || it == "localhost" || it.startsWith("localhost:") }
    val fruits = listOf("Pisang", "Mangga", "Apel", "Pepaya", "Jeruk", "Alpukat", "Durian", "Tomat")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah wadah") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nama wadah") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Jenis buah", color = FarmerMuted, fontSize = 12.sp)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fruits.forEach { option ->
                        if (fruit == option) Button(onClick = { fruit = option }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) { Text(option, fontSize = 12.sp) }
                        else OutlinedButton(onClick = { fruit = option }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) { Text(option, fontSize = 12.sp) }
                    }
                }
                OutlinedTextField(ip, { ip = it }, label = { Text("Alamat IP unit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID WiFi unit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    if (isLocalDemo) "Demo USB: SSID boleh kosong jika port sudah diteruskan dengan adb reverse."
                    else "SSID opsional. Jika diisi, aplikasi mencoba auto-connect ke unit; IP default ESP32 biasanya 192.168.4.1.",
                    color = FarmerMuted,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, fruit, ip, ssid) }, enabled = name.isNotBlank() && ip.isNotBlank()) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

private fun riskColor(status: String): Color = when (status) {
    "Aman" -> SafeGreen
    "Perhatian" -> AttentionAmber
    "Segera ditangani" -> UrgentRed
    else -> FarmerMuted
}

private fun lastSyncLabel(container: FarmerContainerEntity): String {
    val timestamp = container.lastSyncMillis ?: return "Belum pernah sinkron"
    val formatted = SimpleDateFormat("dd MMM, HH:mm", Locale.forLanguageTag("id-ID")).format(Date(timestamp))
    return if (container.lastError == null) "Terakhir diperiksa $formatted" else "Terakhir tersimpan $formatted · unit belum terjangkau"
}
