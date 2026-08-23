# SRS — IoT Hardware (Unit Wadah, Sensor, Rekomendasi & Pengingat)

> Bagian dari Software Requirements Specification RipenAI. Fokus: requirement untuk unit IoT yang ditempel di wadah/keranjang buah, logika rekomendasi & pengingat, dan mekanisme sinkronisasi langsung ke aplikasi (Mode Petani).
> Requirement UI Mode Petani ada di `SRS-mobile-app.md`. Spesifikasi firmware & BOM ada di `TECH-iot-firmware.md`.

---

## 1. Ruang Lingkup

Dokumen ini mencakup requirement untuk:
- Unit IoT tunggal per wadah/keranjang (sensor + WiFi AP lokal, tanpa gateway/LoRa terpisah).
- Logika rekomendasi (recommender) dan pengingat (reminder) berbasis tren sensor.
- Protokol sinkronisasi langsung unit IoT ↔ HP.
- Definisi threshold rekomendasi rule-based.

---

## 2. Peran & Prinsip Desain

| Prinsip | Penjelasan |
|---|---|
| Scope per-wadah, bukan per-kebun | Setiap unit IoT memantau kondisi lingkungan **di dalam/sekitar satu wadah/keranjang** hasil panen, bukan area kebun yang luas. |
| 1 unit = sensor + server, tanpa gateway terpisah | Unit IoT (ESP32) berfungsi ganda: membaca sensor DAN memancarkan WiFi Access Point lokal agar HP bisa connect langsung. Tidak ada perangkat "gateway" terpisah, dan tidak dibutuhkan LoRa karena jarak HP↔wadah selalu dekat (petani harus fisik dekat wadah). |
| AI sebagai rekomender & pengingat | Peran AI di mode ini BUKAN mengklasifikasi kematangan buah individual (itu tugas CV di Mode Konsumen), melainkan membaca **tren kondisi wadah dari waktu ke waktu** dan menerjemahkannya menjadi rekomendasi aksi + pengingat. |
| Multi-wadah = multi-unit independen | Skala ke banyak wadah dilakukan dengan menambah unit IoT baru per wadah (masing-masing WiFi AP sendiri), bukan dengan menambah kompleksitas jaringan. |

---

## 3. Requirement Fungsional — Unit IoT (Sensor)

| ID | Requirement | Prioritas |
|---|---|---|
| FR-N01 | Unit HARUS membaca suhu & kelembapan udara di sekitar wadah secara berkala (interval disarankan 15-30 menit untuk hemat baterai) menggunakan sensor DHT22 | Wajib |
| FR-N02 | Unit HARUS membaca level gas hasil proses pematangan/fermentasi buah secara berkala menggunakan sensor MQ-3 pada interval yang sama | Wajib |
| FR-N03 | Unit HARUS menampilkan status ringkas secara visual langsung di alat menggunakan LED RGB (misal hijau = aman, kuning = perhatian, merah = segera gunakan/jual) tanpa perlu membuka aplikasi | Wajib |
| FR-N04 | Unit HARUS menyimpan histori pembacaan sensor secara lokal (buffer internal) sampai berhasil disinkronkan ke aplikasi | Wajib |
| FR-N05 | Unit HARUS menyertakan timestamp (atau sequence number bila RTC tidak tersedia) pada setiap data yang disimpan | Wajib |
| FR-N06 | Unit HARUS dirancang untuk operasi baterai (power bank/18650) dengan estimasi daya tahan minimal mencukupi durasi pengujian/demo (disarankan ≥3-5 hari kontinu) | Wajib |
| FR-N07 | Unit HARUS ditempatkan dalam casing yang aman digunakan berdekatan dengan buah (tidak mencemari, tahan kelembapan) | Wajib |

---

## 4. Requirement Fungsional — WiFi Access Point Lokal & Sinkronisasi

| ID | Requirement | Prioritas |
|---|---|---|
| FR-AP01 | Unit HARUS memancarkan WiFi Access Point lokal dengan SSID yang dapat diidentifikasi unik per wadah (misal `RipenAI-Wadah-01`) | Wajib |
| FR-AP02 | Unit HARUS menyediakan endpoint/protokol sederhana agar aplikasi HP dapat menarik (pull) seluruh data baru sejak sinkronisasi terakhir | Wajib |
| FR-AP03 | Unit TIDAK BOLEH memerlukan koneksi internet untuk melayani permintaan data dari aplikasi HP | Wajib |
| FR-AP04 | Unit BOLEH melakukan sinkronisasi ke server cloud sebagai backup HANYA JIKA tersedia WiFi/internet di lokasi unit (opsional, tidak menjadi syarat fungsi utama) | Opsional |
| FR-AP05 | Jangkauan WiFi AP unit HARUS cukup untuk digunakan pada jarak wajar di sekitar wadah (beberapa meter), TIDAK memerlukan jangkauan jarak jauh seperti LoRa karena penggunaannya memang mengharuskan petani berada dekat wadah | Wajib |

---

## 5. Requirement — Logika Rekomendasi (Recommender)

### 5.1 v1 — Rule-Based (Wajib, tanpa training tambahan)

| ID | Requirement | Prioritas |
|---|---|---|
| FR-REC01 | Sistem HARUS menghitung skor risiko/kematangan-lanjutan wadah berdasarkan kombinasi tren suhu, kelembapan, dan level gas MQ-3 dari waktu ke waktu (bukan hanya nilai sesaat) | Wajib |
| FR-REC02 | Sistem HARUS memetakan skor risiko ke rekomendasi aksi yang jelas dan dapat ditindaklanjuti, contoh: "gunakan/jual dalam ±N hari", "segera periksa & pisahkan buah yang terlalu matang" | Wajib |
| FR-REC03 | Seluruh ambang (threshold) yang dipakai dalam perhitungan skor risiko HARUS dapat dikonfigurasi per jenis buah (laju kenaikan gas yang wajar berbeda antar komoditas) | Wajib |
| FR-REC04 | Sistem TIDAK BOLEH mengklaim rekomendasi v1 sebagai hasil machine learning prediktif — ini murni threshold rule-based dan harus dikomunikasikan demikian di dokumentasi maupun UI | Wajib |

### 5.2 v2 — Data-Driven (Roadmap, opsional untuk v1)

| ID | Requirement | Prioritas |
|---|---|---|
| FR-REC05 | Sistem BOLEH mengumpulkan data time-series (suhu, kelembapan, gas, dan hasil verifikasi manual kondisi buah) selama penggunaan/pengujian sebagai calon dataset training | Opsional |
| FR-REC06 | JIKA data historis mencukupi (multi-wadah, multi-musim), sistem BOLEH melatih model ringan untuk memprediksi sisa waktu layak-jual/pakai secara lebih akurat dibanding rule-based | Roadmap v2 |

---

## 6. Requirement — Pengingat (Reminder)

| ID | Requirement | Prioritas |
|---|---|---|
| FR-REM01 | Sistem HARUS mengirim pengingat (notifikasi lokal di aplikasi) saat skor risiko wadah naik melewati ambang tertentu | Wajib |
| FR-REM02 | Sistem HARUS mengirim pengingat saat sebuah wadah belum disinkronkan dalam durasi tertentu (misal >24 jam), agar petani tidak lupa mengeceknya | Disarankan |
| FR-REM03 | Pengingat HARUS menyertakan alasan singkat (bukan notifikasi kosong), contoh: "Wadah 2: level gas naik cepat 6 jam terakhir, segera cek" | Wajib |

---

## 7. Requirement Non-Fungsional

| ID | Requirement |
|---|---|
| NFR-IOT01 | Seluruh komunikasi unit IoT ↔ HP TIDAK BOLEH memerlukan internet aktif untuk berfungsi |
| NFR-IOT02 | Total biaya komponen (BOM) untuk 1 unit IoT prototype HARUS berada di kisaran Rp 150.000–220.000 |
| NFR-IOT03 | Seluruh bobot/threshold rekomendasi (FR-REC01-03) HARUS disimpan sebagai konfigurasi terpisah, bukan hardcoded, agar mudah disesuaikan per jenis buah |

---

## 8. Referensi Silang

- Spesifikasi BOM lengkap, pinout, dan firmware detail → `TECH-iot-firmware.md`
- Diagram alur data & topologi sederhana → `DESIGN-architecture.md`
- Alur UI dashboard, rekomendasi, dan pengingat di aplikasi → `SRS-mobile-app.md`
