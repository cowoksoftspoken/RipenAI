package com.ripenai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ripenai.BuildConfig
import com.ripenai.ui.RipenViewModel
import com.ripenai.ui.theme.AgriPrimary
import com.ripenai.ui.theme.AgriPrimaryDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: RipenViewModel,
    modifier: Modifier = Modifier
) {
    val espIp by viewModel.espIp.collectAsState()
    val espConnected by viewModel.espConnected.collectAsState()
    val isTesting by viewModel.isTestingConnection.collectAsState()
    val testResult by viewModel.testConnectionResult.collectAsState()

    val showGrid by viewModel.showGrid.collectAsState()
    val saveImage by viewModel.saveImage.collectAsState()
    val soundNotification by viewModel.soundNotification.collectAsState()
    val showSimulation by viewModel.showSimulation.collectAsState()

    var ipInput by remember { mutableStateOf(espIp) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.White)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "Pengaturan Sistem",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.DarkGray
            )

            // Section: KONEKSI KAMERA & SENSOR ESP32
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("esp32_connection_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE5E7EB))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "KONEKSI KAMERA ESP32",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        letterSpacing = 1.sp
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Alamat IP Perangkat",
                            fontSize = 14.sp,
                            color = Color.DarkGray,
                            fontWeight = FontWeight.Medium
                        )
                        OutlinedTextField(
                            value = ipInput,
                            onValueChange = {
                                ipInput = it
                                viewModel.updateEspIp(it)
                            },
                            placeholder = { Text("Contoh: 192.168.4.1") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("esp32_ip_input"),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AgriPrimary,
                                cursorColor = AgriPrimary
                            )
                        )
                    }

                    // Status and Test Connection row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Connection Status Pill
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val dotColor = if (espConnected) AgriPrimary else Color.Red
                            val statusText = if (espConnected) "Terhubung" else "Terputus"
                            val statusTextColor = if (espConnected) AgriPrimaryDark else Color.Red

                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Text(
                                text = "Status: $statusText",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = statusTextColor
                            )
                        }

                        // Test connection Button
                        Button(
                            onClick = { viewModel.testConnection() },
                            enabled = !isTesting,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AgriPrimary),
                            modifier = Modifier.testTag("test_connection_btn")
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                            } else {
                                Text("Tes Koneksi", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }

                    // Test Results Indicator Popup
                    AnimatedVisibility(visible = testResult != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (testResult == "Terhubung") Color(0xFFDCFCE7) else Color(0xFFFEE2E2)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (testResult == "Terhubung") Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    contentDescription = null,
                                    tint = if (testResult == "Terhubung") AgriPrimaryDark else Color.Red
                                )
                                Text(
                                    text = if (testResult == "Terhubung") "Koneksi ke ESP32 Berhasil!" else "Gagal Menghubungi Sensor ESP32.",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (testResult == "Terhubung") AgriPrimaryDark else Color.Red
                                )
                            }
                        }
                    }
                }
            }

            // Section: PREFERENSI ANALISIS
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preferences_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE5E7EB))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "PREFERENSI ANALISIS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        letterSpacing = 1.sp
                    )

                    // 1. Tampilkan Grid Reticle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleShowGrid(!showGrid) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tampilkan Grid Reticle",
                            fontSize = 15.sp,
                            color = Color.DarkGray
                        )
                        Switch(
                            checked = showGrid,
                            onCheckedChange = { viewModel.toggleShowGrid(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = AgriPrimary),
                            modifier = Modifier.testTag("grid_switch")
                        )
                    }

                    HorizontalDivider(color = Color(0xFFE5E7EB))

                    // 2. Simpan Gambar Pindaian
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleSaveImage(!saveImage) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Simpan Gambar Pindaian",
                            fontSize = 15.sp,
                            color = Color.DarkGray
                        )
                        Switch(
                            checked = saveImage,
                            onCheckedChange = { viewModel.toggleSaveImage(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = AgriPrimary),
                            modifier = Modifier.testTag("save_image_switch")
                        )
                    }

                    HorizontalDivider(color = Color(0xFFE5E7EB))

                    // 3. Notifikasi Suara Deteksi
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleSoundNotification(!soundNotification) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Notifikasi Suara Deteksi",
                            fontSize = 15.sp,
                            color = Color.DarkGray
                        )
                        Switch(
                            checked = soundNotification,
                            onCheckedChange = { viewModel.toggleSoundNotification(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = AgriPrimary),
                            modifier = Modifier.testTag("sound_switch")
                        )
                    }

                    if (BuildConfig.DEBUG) {
                        HorizontalDivider(color = Color(0xFFE5E7EB))

                        // 4. Tampilkan Tombol Simulasi
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleShowSimulation(!showSimulation) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Tampilkan Tombol Simulasi",
                                fontSize = 15.sp,
                                color = Color.DarkGray
                            )
                            Switch(
                                checked = showSimulation,
                                onCheckedChange = { viewModel.toggleShowSimulation(it) },
                                colors = SwitchDefaults.colors(checkedTrackColor = AgriPrimary),
                                modifier = Modifier.testTag("simulation_switch")
                            )
                        }
                    }
                }
            }

            // Section: INFORMASI APLIKASI
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_info_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE5E7EB))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "INFORMASI APLIKASI",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        letterSpacing = 1.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Versi Aplikasi", fontSize = 14.sp, color = Color.Gray)
                        Text(
                            text = "v2.1.0-build.45",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.DarkGray
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Model AI", fontSize = 14.sp, color = Color.Gray)
                        Text(
                            text = "RipenVision-V3",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = AgriPrimary
                        )
                    }

                    HorizontalDivider(color = Color(0xFFE5E7EB))

                    // About section paragraph
                    Text(
                        text = "Tentang Aplikasi",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Text(
                        text = "RipenAI adalah sistem pertanian cerdas terintegrasi IoT on-device AI. Aplikasi ini mendeteksi kematangan buah secara offline dengan presisi tinggi, dan mengirimkan aksi digital ke papan sirkuit ESP32 untuk mengontrol LED panen secara nirkabel.",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
