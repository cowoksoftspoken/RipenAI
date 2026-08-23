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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.ripenai.ui.theme.AgriPrimaryContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionsScreen(
    viewModel: RipenViewModel,
    onBackToScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val response by viewModel.questionResponse.collectAsState()
    val answers by viewModel.questionAnswers.collectAsState()
    val isLoading by viewModel.isLoadingQuestions.collectAsState()
    val preliminary by viewModel.scanResult.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        topBar = {
            TopAppBar(
                title = { Text("Bantu RipenAI memastikan", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBackToScan) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).background(Color.White).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AgriPrimaryContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = AgriPrimary)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Kita pastikan bersama", fontWeight = FontWeight.Bold, color = Color(0xFF14532D))
                        Text(
                            "Jawab pilihan singkat ini berdasarkan apa yang kamu lihat atau rasakan. Hasil akhir baru muncul setelah semua pertanyaan terjawab.",
                            color = Color(0xFF166534), fontSize = 13.sp
                        )
                        if (preliminary != null) {
                            Text(
                                "Kandidat: ${preliminary!!.ripeness} ${preliminary!!.confidence}%" +
                                    (preliminary!!.top2Stage?.let { " atau ${it.replace('_', ' ')} ${preliminary!!.top2Confidence}%" } ?: ""),
                                color = Color(0xFF166534), fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            if (isLoading || response == null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = AgriPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AgriPrimary)
                        Text("Menyiapkan pertanyaan tambahan\u2026", color = Color.Gray)
                    }
                }
            } else {
                val questions = response!!.questions
                Text(
                    "Beberapa hal kecil untuk memastikan kematangan",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF334155)
                )
                questions.forEachIndexed { index, question ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${index + 1}. ${question.text}", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E293B))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                question.options.forEach { option ->
                                    FilterChip(
                                        selected = answers[question.id] == option,
                                        onClick = { viewModel.selectQuestionAnswer(question, option) },
                                        label = { Text(option) },
                                        leadingIcon = if (answers[question.id] == option) {
                                            { Icon(Icons.Default.CheckCircle, contentDescription = null) }
                                        } else null,
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = AgriPrimaryContainer,
                                            selectedLabelColor = Color(0xFF166534),
                                            selectedLeadingIconColor = AgriPrimary
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                val isComplete = questions.isNotEmpty() && questions.all { !answers[it.id].isNullOrBlank() }
                Button(
                    onClick = viewModel::submitQuestions,
                    enabled = isComplete,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AgriPrimary)
                ) {
                    Text("Lihat hasil gabungan", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
