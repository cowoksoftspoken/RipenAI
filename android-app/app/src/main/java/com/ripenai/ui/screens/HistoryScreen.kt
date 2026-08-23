package com.ripenai.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ripenai.data.local.ScanHistory
import com.ripenai.domain.ClassificationResult
import com.ripenai.ui.RipenViewModel
import com.ripenai.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: RipenViewModel,
    onNavigateToResult: () -> Unit,
    modifier: Modifier = Modifier
) {
    val historyList by viewModel.historyList.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Riwayat Pemindaian", fontWeight = FontWeight.Bold) },
                actions = {
                    if (historyList.isNotEmpty()) {
                        TextButton(
                            onClick = { viewModel.clearAllHistory() },
                            modifier = Modifier.testTag("clear_history_btn")
                        ) {
                            Text("Hapus Semua", color = Color.Red, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.White)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Screen Subtitle
            Text(
                text = "Data historis estimasi kematangan buah.",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )

            if (historyList.isEmpty()) {
                // Beautiful Offline Empty State vector
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF3F4F6)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = Color.LightGray,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Belum Ada Scan",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Mulai pindai buah Anda untuk melihat riwayat analisis kematangan di sini.",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            } else {
                // List of Scan Histories
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .testTag("history_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(historyList, key = { it.id }) { item ->
                        HistoryRowItem(
                            item = item,
                            onClick = {
                                val res = ClassificationResult(
                                    commodity = item.commodity,
                                    ripeness = item.ripeness,
                                    confidence = item.confidence,
                                    daysEstimate = item.daysEstimate,
                                    recommendation = item.recommendation,
                                    temperature = item.temperature,
                                    humidity = item.humidity,
                                    gas = item.gas,
                                    gasLevel = item.gasLevel
                                )
                                viewModel.setHistoricalResult(res, item.imagePath)
                                onNavigateToResult()
                            },
                            onDelete = { viewModel.deleteHistoryItem(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryRowItem(
    item: ScanHistory,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (item.ripeness) {
        "Mentah" -> StatusMentah
        "Hampir Matang" -> StatusHampirMatang
        "Terlalu Matang" -> StatusOverripe
        "Busuk" -> StatusRotten
        else -> StatusMatang
    }

    val statusBgColor = when (item.ripeness) {
        "Mentah" -> StatusMentahBg
        "Hampir Matang" -> StatusHampirMatangBg
        "Terlalu Matang" -> StatusOverripeBg
        "Busuk" -> StatusRottenBg
        else -> StatusMatangBg
    }

    val formattedDate = remember(item.dateMillis) {
        val sdf = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault())
        sdf.format(Date(item.dateMillis))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("history_item_card_${item.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FAFB)),
        border = BorderStroke(1.dp, Color(0xFFE5E7EB))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Thumbnail: 60dp circular crop
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE5E7EB))
                    .border(1.dp, Color(0xFFD1D5DB), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (!item.imagePath.isNullOrBlank() && File(item.imagePath).exists()) {
                    AsyncImage(
                        model = File(item.imagePath),
                        contentDescription = "Thumbnail Buah",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Eco,
                        contentDescription = null,
                        tint = AgriPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.commodity,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.DarkGray
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formattedDate,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(6.dp))
                
                // Status Badge Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Left color dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = "${item.ripeness} (${item.confidence}%)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
            }

            // Delete action
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_history_item_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Hapus riwayat",
                    tint = Color.LightGray,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
