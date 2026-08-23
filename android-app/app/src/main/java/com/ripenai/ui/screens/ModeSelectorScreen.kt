package com.ripenai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ripenai.ui.theme.AgriPrimary
import com.ripenai.ui.theme.AgriPrimaryContainer

enum class AppMode { CONSUMER, FARMER }

@Composable
fun ModeSelectorScreen(
    onModeSelected: (AppMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().background(Color.White).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Eco, contentDescription = null, tint = AgriPrimary, modifier = Modifier.height(64.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text("RipenAI", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = AgriPrimary)
        Text("Bantu mengambil keputusan tentang kematangan buah", color = Color.Gray, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(32.dp))

        ModeCard(
            title = "Mode Konsumen",
            subtitle = "Cek buah yang ingin kamu beli",
            icon = Icons.Default.Eco,
            primary = true,
            onClick = { onModeSelected(AppMode.CONSUMER) }
        )
        Spacer(modifier = Modifier.height(14.dp))
        ModeCard(
            title = "Mode Petani",
            subtitle = "Pantau kondisi wadah & sensor IoT",
            icon = Icons.Default.Agriculture,
            primary = false,
            onClick = { onModeSelected(AppMode.FARMER) }
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Info, contentDescription = null, tint = Color.Gray)
            Text(
                "Mode Konsumen memakai model TFLite di perangkat dan koneksi internet untuk pertanyaan konfirmasi sebelum hasil akhir ditampilkan.",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    onClick: () -> Unit
) {
    val container = if (primary) AgriPrimary else Color.White
    val content = if (primary) Color.White else Color(0xFF1E293B)
    if (primary) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(104.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = container),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)
        ) {
            ModeCardContent(title, subtitle, icon, content)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(104.dp),
            shape = RoundedCornerShape(20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)
        ) {
            ModeCardContent(title, subtitle, icon, content)
        }
    }
}

@Composable
private fun ModeCardContent(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: Color
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.height(36.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, color = content, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(subtitle, color = content.copy(alpha = 0.8f), fontSize = 13.sp)
        }
    }
}
