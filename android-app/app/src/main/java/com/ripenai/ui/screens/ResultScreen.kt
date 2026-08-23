package com.ripenai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ripenai.ui.RipenViewModel
import com.ripenai.ui.theme.AgriPrimary
import com.ripenai.ui.theme.AgriPrimaryContainer
import com.ripenai.ui.theme.StatusHampirMatang
import com.ripenai.ui.theme.StatusHampirMatangBg
import com.ripenai.ui.theme.StatusMentah
import com.ripenai.ui.theme.StatusMentahBg
import com.ripenai.ui.theme.StatusMatang
import com.ripenai.ui.theme.StatusMatangBg
import com.ripenai.ui.theme.StatusOverripe
import com.ripenai.ui.theme.StatusOverripeBg
import com.ripenai.ui.theme.StatusRotten
import com.ripenai.ui.theme.StatusRottenBg

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    viewModel: RipenViewModel,
    onBackToScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val result by viewModel.scanResult.collectAsState()
    val bitmap by viewModel.capturedBitmap.collectAsState()
    val scan = result ?: return
    val palette = when (scan.ripeness) {
        "Mentah" -> Triple(StatusMentah, StatusMentahBg, "Perlu waktu lagi")
        "Hampir Matang" -> Triple(StatusHampirMatang, StatusHampirMatangBg, "Hampir siap")
        "Terlalu Matang" -> Triple(StatusOverripe, StatusOverripeBg, "Segera digunakan")
        "Busuk" -> Triple(StatusRotten, StatusRottenBg, "Jangan dikonsumsi")
        else -> Triple(StatusMatang, StatusMatangBg, "Siap dikonsumsi")
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Hasil analisis", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackToScan, modifier = Modifier.testTag("result_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(scan.commodity, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1E293B))
                    Text("Keputusan berbasis foto buah yang kamu pilih", fontSize = 12.sp, color = Color.Gray)
                }
                Box(modifier = Modifier.clip(RoundedCornerShape(100)).background(AgriPrimaryContainer).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("SELESAI", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AgriPrimary)
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFF1F5F9)).border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "Foto buah", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Default.Eco, contentDescription = null, tint = AgriPrimary.copy(alpha = 0.35f), modifier = Modifier.size(72.dp))
            }

            Card(
                modifier = Modifier.fillMaxWidth().testTag("ripeness_result_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("STATUS KEMATANGAN", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text("${scan.confidence}% yakin", fontWeight = FontWeight.Bold, color = palette.first)
                    }
                    LinearProgressIndicator(
                        progress = { scan.confidence / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        color = palette.first,
                        trackColor = Color(0xFFE2E8F0)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(palette.second), contentAlignment = Alignment.Center) {
                            Icon(if (scan.ripeness == "Terlalu Matang" || scan.ripeness == "Busuk") Icons.Default.Warning else Icons.Default.CheckCircle, contentDescription = null, tint = palette.first, modifier = Modifier.size(28.dp))
                        }
                        Column {
                            Text(scan.ripeness, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = palette.first)
                            Text(palette.third, color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                    Text(scan.daysEstimate, color = Color(0xFF475569), fontWeight = FontWeight.SemiBold)
                }
            }

            if (scan.disclaimer != null || scan.isCvOnly || scan.isAmbiguous) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)), border = BorderStroke(1.dp, Color(0xFFFED7AA))) {
                    Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFB45309))
                        Text(scan.disclaimer ?: "Gunakan hasil ini sebagai panduan dan pertimbangkan ciri buah lain.", color = Color(0xFF7C2D12), fontSize = 13.sp)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("REKOMENDASI", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Text(scan.recommendation, color = Color(0xFF334155), fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("Sumber: ${scan.analysisSource}", color = Color.Gray, fontSize = 12.sp)
                }
            }

            Button(
                onClick = onBackToScan,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("scan_again_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AgriPrimary)
            ) {
                Text("Pindai buah lain", fontWeight = FontWeight.Bold)
            }
        }
    }
}
