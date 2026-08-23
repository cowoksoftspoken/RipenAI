package com.ripenai.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import com.ripenai.ui.theme.AgriPrimary
import com.ripenai.ui.theme.AgriPrimaryContainer

private data class FruitOption(val id: String, val label: String)

@Composable
fun FruitSelector(selectedFruit: String?, onFruitSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val options = remember(context) { loadFruitOptions(context) }
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)), border = BorderStroke(1.dp, Color(0xFFE2E8F0))) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pilih buah yang ingin kamu cek", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF1E293B))
            Text("Foto dan pertanyaan berikutnya akan disesuaikan dengan pilihan ini.", color = Color(0xFF64748B), fontSize = 12.sp)
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 8.dp)
            ) {
                items(options, key = { it.id }) { option ->
                    FilterChip(
                        modifier = Modifier.height(44.dp),
                        selected = selectedFruit == option.id,
                        onClick = { onFruitSelected(option.id) },
                        label = { Text(option.label, fontSize = 13.sp) },
                        leadingIcon = { FruitIcon(option.id) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AgriPrimaryContainer,
                            selectedLabelColor = AgriPrimary,
                            selectedLeadingIconColor = AgriPrimary
                        )
                    )
                }
            }
            if (selectedFruit == "durian" || selectedFruit == "avocado") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFB45309))
                    Text(
                        if (selectedFruit == "avocado") "Untuk alpukat, foto saja belum cukup. Pertanyaan akan menuntun pemeriksaan warna, tekstur, dan tingkat kematangan."
                        else "Untuk durian, pertanyaan akan menuntun pemeriksaan bunyi, aroma, dan duri karena foto saja tidak cukup.",
                        color = Color(0xFF92400E),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun FruitIcon(id: String) {
    val context = LocalContext.current
    val bitmap = remember(context, id) {
        runCatching {
            context.assets.open("fruit_icons/$id.png").use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
    } else {
        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF64748B))
    }
}

private fun loadFruitOptions(context: Context): List<FruitOption> {
    val loaded = try {
        val json = context.assets.open("fruit_catalog.json").bufferedReader().use { JSONArray(it.readText()) }
        (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val label = item.optString("label").trim()
            if (id.isBlank() || label.isBlank()) null else FruitOption(id, label)
        }
    } catch (_: Exception) {
        listOf(FruitOption("apple", "Apel"), FruitOption("banana", "Pisang"), FruitOption("mango", "Mangga"), FruitOption("orange", "Jeruk"), FruitOption("tomato", "Tomat"), FruitOption("avocado", "Alpukat"), FruitOption("durian", "Durian"))
    }
    val withAvocado = if (loaded.any { it.id == "avocado" }) loaded else loaded + FruitOption("avocado", "Alpukat")
    return if (withAvocado.any { it.id == "durian" }) withAvocado else withAvocado + FruitOption("durian", "Durian")
}
