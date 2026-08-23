# DESIGN — Arsitektur Sistem End-to-End

> Diagram & penjelasan arsitektur sistem RipenAI secara keseluruhan, menghubungkan Mode Konsumen dan Mode Petani.

---

## 1. Peta Sistem Keseluruhan

```
                        ┌───────────────────────────┐
                        │      RIPENAI ANDROID APP    │
                        │                             │
                        │  ┌───────────┐ ┌──────────┐│
                        │  │  Mode      │ │  Mode    ││
                        │  │  Konsumen  │ │  Petani  ││
                        │  └─────┬──────┘ └────┬─────┘│
                        └────────┼─────────────┼──────┘
                                 │              │
                    (online,     │              │  (offline-first,
                     LLM API)    │              │   sync langsung ke unit)
                                 ▼              ▼
                    ┌─────────────────┐   ┌──────────────────────┐
                    │   LLM API        │   │  Unit IoT per Wadah   │
                    │ (question gen)   │   │  (ESP32 + DHT22 +      │
                    │                  │   │   MQ-3 + LED RGB +     │
                    │                  │   │   WiFi AP lokal)       │
                    └─────────────────┘   └──────────────────────┘
```

Dua subsistem ini **tidak saling bergantung secara teknis** — Mode Konsumen bisa berjalan penuh tanpa IoT sama sekali, dan Mode Petani bisa berjalan penuh tanpa LLM API sama sekali. Yang menyatukan keduanya hanyalah satu aplikasi Android dan satu tujuan produk (membantu keputusan terkait kematangan buah). Mode Petani tidak lagi punya lapisan gateway/LoRa — satu unit IoT sudah mencakup sensor sekaligus WiFi server.

---

## 2. Data Flow — Mode Konsumen (Detail)

```
   [User ambil foto + pilih jenis buah]
                 │
                 ▼
   ┌─────────────────────────┐
   │ MobileNetV2 (on-device,  │
   │ TFLite, per-jenis-buah)  │
   └────────────┬─────────────┘
                 │
                 ▼
      Prediksi awal + confidence
                 │
      ┌──────────┴───────────┐
      │                        │
 Confidence tinggi        Confidence rendah
 (tidak ambigu)            (ambigu)
      │                        │
      ▼                        ▼
 Langsung tampilkan      Panggil LLM API
 hasil                   (generate 2-3 pertanyaan
      │                   terstruktur, JSON)
      │                        │
      │                  ┌─────┴─────┐
      │              Sukses        Gagal/timeout
      │                  │              │
      │                  ▼              ▼
      │          Render UI chip   Load fallback
      │          dari JSON        questions (lokal)
      │                  │              │
      │                  └──────┬───────┘
      │                         ▼
      │                Jawaban user (quick-select)
      │                         │
      │                         ▼
      │              Fusion scoring (rule-based v1 /
      │               model ringan v2)
      │                         │
      └─────────────┬───────────┘
                     ▼
           Hasil akhir + penjelasan
           (+ disclaimer khusus untuk
            buah seperti durian)
```

---

## 3. Data Flow — Mode Petani (Detail)

```
Timeline harian (contoh):

Pagi   : Unit IoT di wadah aktif baca sensor (interval 15-30 menit)
         → simpan ke buffer lokal + update LED status
         → WiFi AP tetap aktif, siap diakses kapan saja

Siang  : Petani mendekat ke wadah untuk mengecek
         → HP auto-connect ke WiFi AP unit IoT (SSID per wadah)
         → App tarik (pull) data terbaru via GET /data & /status
         → App hitung/refresh skor risiko & rekomendasi
         → Notifikasi/pengingat muncul jika perlu
           ("Wadah 2: level gas naik cepat 6 jam terakhir,
             gunakan/jual dalam ±2 hari")

Malam  : (Opsional) Unit IoT sync ke cloud jika ada WiFi/internet
         di lokasi penyimpanan → backup histori untuk roadmap v2
```

---

## 4. Kenapa WiFi AP Langsung di Unit, Bukan Gateway Terpisah?

Poin desain kunci di revisi ini: karena scope diperkecil dari kebun luas menjadi **1 wadah/keranjang**, kebutuhan jaringan jarak jauh (LoRa) hilang sepenuhnya. Alasannya:

1. **Jarak selalu dekat** — petani memang harus berada fisik dekat wadah untuk mengecek kondisi buah, sehingga WiFi jarak pendek bawaan ESP32 sudah cukup, tanpa perlu modul radio tambahan.
2. **Tidak ada banyak titik sensor yang tersebar** — berbeda dari skenario kebun (banyak node di banyak plot), di sini 1 wadah = 1 unit, jadi tidak ada kebutuhan untuk mengumpulkan data dari banyak titik ke satu gateway.
3. **Lebih sederhana & lebih murah** — menghilangkan modul LoRa dan peran gateway terpisah mengurangi kompleksitas firmware dan biaya BOM (lihat `TECH-iot-firmware.md` §1), tanpa mengurangi fungsi inti.

---

## 5. Skalabilitas (Multi-Wadah, Roadmap Lanjutan)

```
Beberapa wadah, masing-masing unit independen:

[Unit Wadah 1]      [Unit Wadah 2]      [Unit Wadah 3]
 WiFi AP sendiri      WiFi AP sendiri     WiFi AP sendiri
      │                     │                    │
      └─────────── (app menyimpan daftar SSID ───┘
                    yang sudah dikenal,
                    connect satu-satu saat
                    petani mendekati tiap wadah)
```

Untuk skala lebih besar (banyak wadah di satu lokasi gudang), roadmap v2 dapat mempertimbangkan 1 hub agregasi opsional yang mengumpulkan data dari beberapa unit terdekat — tapi ini BUKAN kebutuhan v1, karena model "1 unit = 1 WiFi AP, connect satu-satu" sudah cukup untuk skala prototype dan penggunaan wajar (beberapa wadah).

---

## 6. Referensi Silang

- Requirement per komponen → `SRS-mobile-app.md`, `SRS-cv-fusion-engine.md`, `SRS-iot-hardware.md`
- Implementasi teknis per komponen → `TECH-mobile-app.md`, `TECH-ml-cv-fusion.md`, `TECH-iot-firmware.md`
- Alur UX/tampilan layar → `DESIGN-ux-flow.md`
