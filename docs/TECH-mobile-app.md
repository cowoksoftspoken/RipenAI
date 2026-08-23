# TECH — Mobile App (Android)

> Spesifikasi teknis implementasi untuk `SRS-mobile-app.md`.
> **Catatan:** stack di bawah adalah rekomendasi berbasis diskusi arsitektur & kebutuhan offline-first/TFLite. Sesuaikan dengan stack yang sudah berjalan di codebase RipenAI saat ini bila berbeda — tandai bagian yang perlu dikonfirmasi/diubah.

---

## 1. Stack Teknis (Rekomendasi)

| Layer | Teknologi | Alasan |
|---|---|---|
| Bahasa aplikasi | Kotlin | Standar modern Android, interop baik dengan TFLite/CameraX |
| UI | Jetpack Compose (atau XML+ViewBinding jika codebase existing pakai ini) | Memudahkan render UI dinamis (pertanyaan LLM) sebagai komponen reusable |
| Inferensi model lokal | TensorFlow Lite (TFLite) Android library | Wajib untuk inferensi on-device tanpa internet |
| Kamera | CameraX | API modern, stabil di berbagai device Android |
| Local storage | Room (SQLite) | Untuk riwayat prediksi, data sensor, cache pertanyaan default |
| Networking (LLM API, cloud sync opsional) | Retrofit/OkHttp | Standar untuk REST call ke LLM API |
| WiFi lokal (unit IoT) | Android WifiManager / ConnectivityManager API | Deteksi & koneksi langsung ke AP lokal unit IoT wadah tanpa internet |
| Background sync | WorkManager | Untuk retry sinkronisasi ke unit IoT saat koneksi terputus |

---

## 2. Struktur Modul Aplikasi (Disarankan)

```
app/
├── mode-selector/        # Layar pilih mode Konsumen/Petani
├── consumer/
│   ├── capture/           # Kamera & pemilihan jenis buah
│   ├── cv-inference/      # Wrapper TFLite per-jenis-buah
│   ├── dynamic-question/  # Render UI dari JSON pertanyaan LLM
│   ├── fusion/            # Rule-based scoring (v1) / model ringan (v2)
│   └── result/            # Tampilan hasil akhir + disclaimer
├── farmer/
│   ├── dashboard/         # Daftar wadah/keranjang + status terakhir
│   ├── sync/               # Deteksi & koneksi WiFi AP unit IoT, pull data
│   ├── recommend/          # Tampilan rekomendasi & pengingat (recommender/reminder)
│   └── history/            # Grafik tren historis per wadah
├── shared/
│   ├── network/            # Retrofit client (LLM API, cloud backup opsional)
│   ├── storage/            # Room DB, DAO
│   └── config/             # File konfigurasi threshold & bobot (lihat NFR-CV03)
```

---

## 3. Implementasi Inferensi CV On-Device

- Model per jenis buah disimpan sebagai file `.tflite` terpisah di `assets/models/<jenis_buah>.tflite`.
- Load model sesuai jenis buah yang dipilih user (lazy-load, jangan load semua model sekaligus ke memori).
- Gunakan `Interpreter` TFLite dengan delegate NNAPI/GPU bila tersedia di device untuk mempercepat inferensi (fallback ke CPU jika tidak).
- Output: array probabilitas per kelas kematangan → ambil top-1 dan top-2 untuk deteksi ambiguitas (lihat FR-AM01 di `SRS-cv-fusion-engine.md`).

---

## 4. Implementasi Pertanyaan Dinamis (LLM-Generated)

1. Setelah deteksi ambigu (FR-AM01/AM02), kirim request ke backend/LLM API (lihat kontrak schema di `TECH-ml-cv-fusion.md`).
2. Parse response JSON menggunakan model data Kotlin (data class) dengan validasi schema — gunakan library seperti `kotlinx.serialization` atau `Moshi` dengan strict parsing agar response tidak sesuai schema langsung terdeteksi sebagai gagal (trigger fallback).
3. Render tiap `question` sebagai grup `FilterChip`/`RadioButton` (Compose) — bukan `TextField`.
4. Simpan jawaban user sebagai map `{question_id: selected_option}` untuk dikirim ke fusion engine.

```kotlin
// Contoh kontrak data class (ilustratif)
data class QuestionResponse(
    val fruitType: String,
    val ambiguousBetween: List<String>,
    val questions: List<DynamicQuestion>
)

data class DynamicQuestion(
    val id: String,
    val text: String,
    val options: List<String>
)
```

---

## 5. Implementasi Sinkronisasi Mode Petani

1. **Deteksi jaringan unit IoT**: scan SSID dengan prefix tetap (misal `RipenAI-Wadah-`) menggunakan `WifiManager`. Setiap wadah punya SSID berbeda (FR-AP01), jadi app perlu menyimpan daftar SSID yang sudah dikenal untuk mendukung multi-wadah (FR-P09).
2. **Auto-connect**: gunakan `WifiNetworkSuggestion` (Android 10+) atau `ConnectivityManager.NetworkRequest` untuk konek ke AP lokal tanpa membuka browser/captive portal.
3. **Pull data**: setelah terhubung, panggil endpoint lokal unit IoT (HTTP server ringan di ESP32, default `http://192.168.4.1/data?since=<last_sync_timestamp>` dan `/status` untuk rekomendasi terkini).
4. **Simpan ke Room DB**: insert data baru, update `last_sync_timestamp` per wadah.
5. **Idempotency**: gunakan kombinasi `wadah_id + timestamp/sequence` sebagai primary key agar data duplikat dari sync berulang tidak masuk dua kali (memenuhi NFR-P03).
6. **Hitung/refresh rekomendasi**: setelah data baru masuk, jalankan ulang `compute_risk_score` (lihat `TECH-iot-firmware.md` §4) di sisi app bila skor risiko juga ingin dihitung ulang dengan histori lebih lengkap dibanding yang dihitung di firmware.

---

## 6. Konfigurasi Threshold & Bobot (File Terpisah)

Sesuai NFR-CV03, seluruh nilai berikut HARUS berada di file konfigurasi (JSON/YAML), bukan hardcoded:

```json
{
  "ambiguity_threshold": 0.70,
  "confidence_gap_threshold": 0.15,
  "fusion_weights": {
    "mangga": {
      "tekstur_lunak": 0.15,
      "tekstur_keras": -0.15,
      "warna_kuning_penuh": 0.10,
      "warna_hijau": -0.10
    }
  },
  "iot_recommendation_thresholds": {
    "mangga": {
      "gas_rate_bands": [[0, 5, 0.0], [5, 15, 0.3], [15, 999, 0.6]],
      "humidity_bands": [[0, 60, 0.0], [60, 80, 0.2], [80, 100, 0.4]]
    }
  }
}
```

File ini idealnya di-load saat startup aplikasi dan mudah di-update tanpa rebuild (misal via remote config sederhana bila online, atau bundled default bila offline).

---

## 7. Penanganan Error & Fallback (Ringkasan Implementasi)

| Kasus | Implementasi |
|---|---|
| LLM API timeout | `OkHttpClient` dengan `callTimeout(3, SECONDS)`, `catch` → load `fallback_questions/<jenis_buah>.json` dari assets lokal |
| Tidak ada internet (Mode Konsumen) | Cek `ConnectivityManager.activeNetwork` sebelum memanggil LLM; jika tidak ada, skip langsung ke hasil CV-only |
| Unit IoT wadah tidak terjangkau | Tampilkan data cache terakhir dari Room DB + badge "belum sinkron sejak [X]" |
| JSON dari LLM tidak valid | Validasi via try-catch parsing; gagal → fallback sama seperti timeout |

---

## 8. Referensi Silang

- Requirement fungsional lengkap → `SRS-mobile-app.md`
- Detail model CV & fusion → `TECH-ml-cv-fusion.md`
- Detail protokol unit IoT (endpoint, format data, logika rekomendasi) → `TECH-iot-firmware.md`
