package com.ripenai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ripenai.ui.RipenViewModel
import com.ripenai.ui.theme.AgriPrimary
import com.ripenai.ui.theme.AgriPrimaryContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumerSettingsScreen(viewModel: RipenViewModel, modifier: Modifier = Modifier) {
    val showGrid by viewModel.showGrid.collectAsState()
    val saveImage by viewModel.saveImage.collectAsState()
    val soundNotification by viewModel.soundNotification.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Preferensi", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Buat pengalaman memilih buah terasa pas untukmu.", color = Color(0xFF475569), fontSize = 15.sp)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = AgriPrimaryContainer),
                border = BorderStroke(1.dp, Color(0xFFD1FAE5))
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Forum, contentDescription = null, tint = AgriPrimary)
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Pertanyaan konfirmasi selalu dipakai", fontWeight = FontWeight.Bold, color = Color(0xFF14532D))
                        Text("Mode ini memakai koneksi internet agar pertanyaan bisa menyesuaikan buah dan foto yang kamu kirim.", color = Color(0xFF166534), fontSize = 13.sp)
                    }
                }
            }
            SettingsSection(title = "Tampilan") {
                PreferenceSwitch(Icons.Default.GridOn, "Tampilkan garis bantu", "Membantu menempatkan satu buah di tengah bingkai.", showGrid) { viewModel.toggleShowGrid(it) }
            }
            SettingsSection(title = "Privasi & kenyamanan") {
                PreferenceSwitch(Icons.Default.Image, "Simpan foto hasil pindai", "Foto hanya disimpan di perangkat untuk riwayatmu.", saveImage) { viewModel.toggleSaveImage(it) }
                HorizontalDivider(color = Color(0xFFE2E8F0))
                PreferenceSwitch(Icons.AutoMirrored.Filled.VolumeUp, "Suara hasil", "Beri tanda suara saat hasil selesai.", soundNotification) { viewModel.toggleSoundNotification(it) }
                HorizontalDivider(color = Color(0xFFE2E8F0))
                Row(modifier = Modifier.fillMaxWidth().clickable { confirmClear = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color(0xFFB91C1C))
                    Column { Text("Hapus semua riwayat", fontWeight = FontWeight.SemiBold, color = Color(0xFF991B1B)); Text("Tidak bisa dibatalkan", color = Color(0xFF64748B), fontSize = 12.sp) }
                }
            }
            SettingsSection(title = "Tentang") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = AgriPrimary)
                    Text("RipenAI membantu kamu melihat tanda-tanda kematangan buah dengan foto dan beberapa pertanyaan sederhana.", color = Color(0xFF475569), fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Hapus semua riwayat?") },
            text = { Text("Semua hasil pindai yang tersimpan di perangkat akan dihapus.") },
            confirmButton = {
                Button(onClick = { viewModel.clearAllHistory(); confirmClear = false }) { Text("Hapus") }
            },
            dismissButton = {
                Button(onClick = { confirmClear = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFE2E8F0))) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.uppercase(), color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            content()
        }
    }
}

@Composable
private fun PreferenceSwitch(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = AgriPrimary)
        Column(modifier = Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E293B)); Text(subtitle, color = Color(0xFF64748B), fontSize = 12.sp, lineHeight = 17.sp) }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = AgriPrimary))
    }
}
