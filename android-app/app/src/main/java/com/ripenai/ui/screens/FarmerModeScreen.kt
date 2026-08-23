package com.ripenai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ripenai.ui.RipenViewModel
import com.ripenai.ui.theme.AgriPrimary

@Composable
fun FarmerModeScreen(
    viewModel: RipenViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connected by viewModel.espConnected.collectAsState()
    val sensor by viewModel.sensorData.collectAsState()
    Column(
        modifier = modifier.fillMaxSize().background(Color.White).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onBack, shape = RoundedCornerShape(12.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") }
            Column {
                Text("Mode Petani", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                Text("Dashboard sensor offline-first", color = Color.Gray)
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = if (connected) Color(0xFFDCFCE7) else Color(0xFFFFF7ED))
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (connected) Icons.Default.Sync else Icons.Default.CloudOff, contentDescription = null, tint = if (connected) AgriPrimary else Color(0xFFB45309))
                Column {
                    Text(if (connected) "Gateway terhubung" else "Menampilkan data terakhir", fontWeight = FontWeight.Bold)
                    Text(if (connected) "Data sensor terbaru tersedia." else "Dekati gateway lokal untuk sinkronisasi.", fontSize = 13.sp, color = Color.Gray)
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Sensors, contentDescription = null, tint = AgriPrimary)
                    Text("Node terakhir", fontWeight = FontWeight.Bold)
                }
                Text("Suhu: ${sensor?.temperature ?: "--"} \u00B0C", color = Color(0xFF334155))
                Text("Kelembapan: ${sensor?.humidity ?: "--"}%", color = Color(0xFF334155))
                Text("Gas/etilen: ${sensor?.gas ?: "--"} ppm", color = Color(0xFF334155))
            }
        }
        Text(
            "Alur dashboard plot, tren, dan event buah jatuh akan mengikuti kontrak IoT saat spesifikasinya tersedia. Mode Konsumen sudah dapat dipakai penuh tanpa sensor.",
            color = Color.Gray,
            fontSize = 13.sp
        )
    }
}
