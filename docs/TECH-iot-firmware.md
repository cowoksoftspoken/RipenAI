# TECH — IoT Firmware (Unit Wadah: ESP32, DHT22, MQ-3, LED RGB)

> Spesifikasi teknis implementasi untuk `SRS-iot-hardware.md`.

---

## 1. Bill of Materials (BOM) — Prototype (per unit/wadah)

| Komponen | Fungsi | Estimasi Harga (IDR) |
|---|---|---|
| ESP32 (DevKit) | Mikrokontroler + WiFi built-in (AP lokal, tidak perlu modul tambahan) | 50.000 – 70.000 |
| Sensor DHT22 | Suhu & kelembapan udara sekitar wadah | 30.000 – 40.000 |
| Sensor MQ-3 | Deteksi gas hasil pematangan/fermentasi buah | 10.000 – 20.000 |
| LED RGB (common cathode/anode) | Indikator status visual langsung di alat | 5.000 – 10.000 |
| Baterai 18650 + holder | Daya unit (prototype) | 30.000 – 50.000 |
| Casing kecil (ditempel di wadah) | Perlindungan alat, aman dekat buah | 15.000 – 25.000 |

**Total per unit**: ±Rp 140.000 – 215.000 (memenuhi NFR-IOT02)
**Total prototype (1-2 unit untuk demo multi-wadah)**: ±Rp 280.000 – 430.000

> Catatan: dibanding desain kebun-luas sebelumnya, versi ini LEBIH MURAH karena tidak lagi butuh modul LoRa, gateway terpisah, atau sensor tanah/getaran — cukup 1 papan ESP32 yang sekaligus jadi sensor node dan WiFi server.

---

## 2. Firmware Unit IoT (Sensor + WiFi AP + HTTP Server dalam Satu Perangkat)

### 2.1 Alur Utama

```cpp
// Pseudocode firmware unit IoT wadah
void setup() {
    initSensors();          // DHT22, MQ-3
    initLED();               // LED RGB
    initLocalStorage();      // buffer histori di flash

    WiFi.softAP("RipenAI-Wadah-01", PASSWORD);  // FR-AP01
    startLocalHttpServer();  // endpoint untuk app HP, FR-AP02
}

void loop() {
    SensorData data = readSensors();       // suhu, kelembapan, level gas
    data.timestamp = getTimestampOrSequence();
    appendToLocalBuffer(data);             // FR-N04, FR-N05

    RiskScore score = computeRiskScore(getRecentBuffer());  // lihat §4
    updateLED(score);                       // FR-N03

    handleHttpRequests();                   // layani pull data dari app, non-blocking
    delay(READ_INTERVAL_MS);                // default 15-30 menit
}
```

### 2.2 Update LED Berdasarkan Skor Risiko

```cpp
void updateLED(RiskScore score) {
    if (score < 0.4)      setColor(GREEN);   // aman
    else if (score < 0.7) setColor(YELLOW);  // perhatian
    else                   setColor(RED);     // segera gunakan/jual
}
```

---

## 3. Endpoint HTTP Lokal (Diakses Aplikasi via WiFi AP Unit)

| Endpoint | Method | Fungsi |
|---|---|---|
| `/data?since=<timestamp>` | GET | Ambil seluruh data sensor baru sejak timestamp terakhir yang diketahui app (FR-AP02) |
| `/status` | GET | Ambil status ringkas terkini: suhu, kelembapan, level gas, skor risiko, rekomendasi |
| `/ping` | GET | Cek unit aktif (untuk deteksi koneksi di app) |

Response contoh `/status`:
```json
{
  "wadah_id": "Wadah-01",
  "ts": 1234789,
  "temp": 28.9,
  "hum": 72,
  "gas_level": 340,
  "risk_score": 0.62,
  "recommendation": "Gunakan/jual dalam ±2 hari"
}
```

Response contoh `/data`:
```json
{
  "data": [
    {"ts":1234567,"temp":28.5,"hum":70,"gas_level":300},
    {"ts":1234678,"temp":28.9,"hum":72,"gas_level":340}
  ],
  "last_ts": 1234678
}
```

---

## 4. Logika Recommender (Rule-Based Safety Path, Farmer ML V1 di Android)

Firmware menjalankan rule-based safety path secara lokal agar LED dan endpoint
tetap berguna tanpa internet. Farmer ML V1 berjalan di aplikasi Android setelah
histori sensor ditarik; model tidak dipaksakan berjalan di ESP32 yang memiliki
resource terbatas. Android menggabungkan rule score 75% dan sinyal model 25%
dengan confidence floor, lalu menyimpan kalibrasi lokal dari label pemeriksaan.

```python
# Pseudocode — dijalankan di firmware ATAU di app setelah data ditarik
def compute_risk_score(recent_readings, thresholds, fruit_type):
    cfg = thresholds[fruit_type]

    gas_rate = rate_of_change(recent_readings, key="gas_level")
    humidity_avg = average(recent_readings, key="hum")

    score = 0.0
    score += weight_from_range(gas_rate, cfg["gas_rate_bands"])
    score += weight_from_range(humidity_avg, cfg["humidity_bands"])

    return clamp(score, 0.0, 1.0)


def recommend_action(score, fruit_type):
    if score > 0.7:
        return "Segera gunakan/jual — kondisi wadah berisiko tinggi"
    elif score > 0.4:
        return "Gunakan/jual dalam ±2 hari — kondisi mulai berisiko"
    else:
        return "Kondisi aman, cek kembali besok"
```

Nilai `thresholds` (band gas_rate, band humidity, dsb) per jenis buah disimpan di file konfigurasi (lihat `TECH-mobile-app.md` §6 untuk pola penyimpanan konfigurasi serupa), ditentukan dari riset domain, BUKAN hasil training — sesuai FR-REC04.

---

## 5. Manajemen Daya

| Teknik | Tujuan |
|---|---|
| Interval baca dapat dikonfigurasi (15-30 menit default) | Trade-off antara resolusi data vs daya tahan baterai |
| WiFi AP aktif terus (bukan deep sleep penuh) | Diperlukan karena app bisa connect kapan saja saat petani mendekat — trade-off: daya tahan baterai lebih pendek dibanding mode deep sleep total, perlu diukur saat validasi lapangan |
| Opsi mode hemat daya | Jika baterai jadi masalah di pengujian, pertimbangkan WiFi AP aktif hanya pada jendela waktu tertentu (misal otomatis nyala tiap 30 menit selama beberapa menit) sebagai penyesuaian lanjutan |

---

## 6. Validasi Lapangan (Wajib Sebelum Demo)

| Item | Cara Validasi |
|---|---|
| Daya tahan baterai dengan WiFi AP aktif terus-menerus | Ukur konsumsi arus rata-rata → hitung estimasi jam/hari operasional, sesuaikan strategi daya bila terlalu boros |
| Auto-connect WiFi AP dari HP | Uji di beberapa merek/versi Android berbeda |
| Sensitivitas & kalibrasi MQ-3 | Uji dengan buah yang levelnya sudah diketahui (matang/busuk) untuk menentukan band threshold yang wajar per jenis buah |
| Akurasi LED indikator vs kondisi buah nyata | Bandingkan status LED dengan penilaian manual pada beberapa sampel wadah |

---

## 7. Referensi Silang

- Requirement fungsional & threshold rekomendasi → `SRS-iot-hardware.md`
- Implementasi sisi Android (deteksi & pull data dari unit IoT) → `TECH-mobile-app.md`
- Diagram alur data end-to-end → `DESIGN-architecture.md`
