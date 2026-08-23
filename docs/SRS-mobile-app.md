# SRS — Mobile App (Android, Dua Mode)

> Bagian dari Software Requirements Specification RipenAI. Fokus: requirement fungsional & non-fungsional aplikasi Android untuk **Mode Konsumen** dan **Mode Petani**.
> Requirement model CV/fusion/LLM ada di `SRS-cv-fusion-engine.md`. Requirement hardware IoT ada di `SRS-iot-hardware.md`.

---

## 1. Ruang Lingkup

Dokumen ini mencakup requirement untuk:
- Pemilihan & switching mode (Konsumen vs Petani) di dalam satu aplikasi.
- Alur UI Mode Konsumen: ambil foto → hasil awal → (kondisional) pertanyaan dinamis → hasil akhir.
- Alur UI Mode Petani: dashboard wadah/keranjang, sinkronisasi data langsung ke unit IoT, rekomendasi & pengingat.
- Requirement offline/online per mode.

---

## 2. Requirement Fungsional — Umum

| ID | Requirement | Prioritas |
|---|---|---|
| FR-G01 | Aplikasi HARUS menyediakan pemilihan mode (Konsumen / Petani) yang jelas terlihat di layar utama | Wajib |
| FR-G02 | Aplikasi HARUS dapat berjalan sepenuhnya offline di Mode Petani (tanpa fitur bergantung ke internet aktif) | Wajib |
| FR-G03 | Aplikasi HARUS mendeteksi status konektivitas internet dan menyesuaikan fitur yang tersedia (khususnya di Mode Konsumen) | Wajib |
| FR-G04 | Aplikasi HARUS menyimpan riwayat prediksi/alert secara lokal (local storage/DB) agar tetap bisa diakses tanpa internet | Wajib |

---

## 3. Requirement Fungsional — Mode Konsumen

### 3.1 Alur Utama

| ID | Requirement | Prioritas |
|---|---|---|
| FR-C01 | User HARUS bisa mengambil foto buah via kamera atau memilih dari galeri | Wajib |
| FR-C02 | User HARUS memilih/mengonfirmasi jenis buah sebelum atau sesudah foto diambil (karena model CV bersifat per-jenis-buah, bukan generalis) | Wajib |
| FR-C03 | Sistem HARUS menampilkan hasil prediksi awal (kelas kematangan + confidence score) dalam ≤3 detik setelah foto diproses secara lokal | Wajib |
| FR-C04 | JIKA confidence prediksi berada di bawah ambang tertentu (didefinisikan di `SRS-cv-fusion-engine.md`), sistem HARUS memicu alur pertanyaan lanjutan | Wajib |
| FR-C05 | Pertanyaan lanjutan HARUS ditampilkan sebagai UI terstruktur (chip/button pilihan), TIDAK BOLEH berupa kolom teks bebas (textarea) yang harus di-generate/diparsing ulang oleh LLM | Wajib |
| FR-C06 | Sistem HARUS menampilkan loading state yang jelas selama pemanggilan LLM API berlangsung (estimasi 1-3 detik) | Wajib |
| FR-C07 | JIKA pemanggilan LLM API gagal atau timeout (>3 detik), sistem HARUS otomatis menggunakan set pertanyaan default per jenis buah (fallback), BUKAN menampilkan error kosong | Wajib |
| FR-C08 | Sistem HARUS menggabungkan confidence foto + jawaban user menjadi satu hasil akhir dengan penjelasan singkat (bukan angka mentah tanpa konteks) | Wajib |
| FR-C09 | Untuk jenis buah yang secara teknis tidak bisa dinilai akurat dari foto (misal durian), sistem HARUS menampilkan disclaimer eksplisit dan mengarahkan user ke metode penilaian alternatif (non-visual) | Wajib |
| FR-C10 | JIKA tidak ada koneksi internet, sistem HARUS tetap memberi hasil dari CV-only dengan indikator bahwa akurasi mungkin lebih rendah tanpa pertanyaan lanjutan | Wajib |

### 3.2 Non-Fungsional Mode Konsumen

| ID | Requirement |
|---|---|
| NFR-C01 | Inferensi model CV lokal (on-device TFLite) HARUS berjalan di HP kelas menengah tanpa lag signifikan (target <2 detik per inferensi) |
| NFR-C02 | Pemanggilan LLM API HARUS punya timeout eksplisit (disarankan 3 detik) dengan fallback otomatis |
| NFR-C03 | UI pertanyaan dinamis HARUS di-render dari JSON terstruktur (schema tetap), bukan parsing teks bebas dari LLM |

---

## 4. Requirement Fungsional — Mode Petani

> Scope Mode Petani adalah monitoring **per-wadah/keranjang** hasil panen (bukan kebun/lahan secara luas). Setiap wadah punya 1 unit IoT sendiri yang berfungsi sekaligus sebagai sensor dan WiFi Access Point lokal — tidak ada gateway atau LoRa terpisah.

### 4.1 Dashboard, Rekomendasi & Pengingat

| ID | Requirement | Prioritas |
|---|---|---|
| FR-P01 | Aplikasi HARUS menampilkan daftar wadah/keranjang yang terdaftar beserta status terakhir (suhu, kelembapan, level gas, waktu update terakhir) | Wajib |
| FR-P02 | Aplikasi HARUS menampilkan riwayat tren data per wadah (minimal grafik sederhana beberapa hari terakhir) | Wajib |
| FR-P03 | Aplikasi HARUS menampilkan rekomendasi aksi (misal "gunakan/jual buah di wadah ini dalam N hari") berdasarkan hasil perhitungan rekomendasi (lihat `SRS-iot-hardware.md` untuk logika & threshold) | Wajib |
| FR-P04 | Aplikasi HARUS menampilkan pengingat (reminder) berupa notifikasi saat skor risiko wadah naik atau saat wadah lama tidak dicek/disinkronkan | Wajib |

### 4.2 Sinkronisasi Data

| ID | Requirement | Prioritas |
|---|---|---|
| FR-P05 | Aplikasi HARUS otomatis mendeteksi & terhubung ke WiFi Access Point lokal yang dipancarkan langsung oleh unit IoT wadah ketika berada dalam jangkauan | Wajib |
| FR-P06 | Aplikasi HARUS menarik (pull) seluruh data baru yang tersimpan di unit IoT saat berhasil terhubung | Wajib |
| FR-P07 | Aplikasi TIDAK BOLEH memerlukan koneksi internet untuk proses sinkronisasi dengan unit IoT (koneksi hanya WiFi lokal langsung ke alat, bukan internet) | Wajib |
| FR-P08 | JIKA unit IoT memiliki akses internet (opsional, di lokasi yang ada WiFi rumah/gudang), aplikasi/unit IoT BOLEH melakukan backup data ke server cloud, tapi ini tidak boleh menjadi syarat fungsi utama | Opsional |
| FR-P09 | Aplikasi HARUS mendukung koneksi ke lebih dari satu unit IoT (multi-wadah) dengan SSID/identitas yang berbeda per wadah | Wajib |

### 4.3 Non-Fungsional Mode Petani

| ID | Requirement |
|---|---|
| NFR-P01 | Seluruh fungsi inti (lihat data, lihat rekomendasi/pengingat, sinkronisasi langsung ke unit IoT) HARUS berjalan 100% tanpa koneksi internet |
| NFR-P02 | UI HARUS tetap dapat digunakan oleh pengguna dengan literasi digital terbatas (ikon jelas, teks minimal, kontras tinggi untuk penggunaan outdoor) |
| NFR-P03 | Sinkronisasi data HARUS idempotent (data yang sama tidak boleh terduplikasi jika sinkronisasi terjadi berkali-kali) |

---

## 5. Alur Kegagalan yang Harus Ditangani (Error Handling)

| Skenario | Penanganan Wajib |
|---|---|
| LLM API timeout/gagal (Mode Konsumen) | Fallback ke pertanyaan default per buah |
| Tidak ada internet saat buka Mode Konsumen | CV-only + disclaimer akurasi |
| Unit IoT wadah tidak terjangkau (Mode Petani) | Tampilkan data terakhir yang tersimpan lokal + indikator "belum sinkron sejak [waktu]" |
| Foto buram/tidak jelas | Sistem HARUS mendeteksi confidence sangat rendah dan meminta user mengambil ulang foto, bukan memaksakan hasil |

---

## 6. Referensi Silang

- Definisi threshold confidence & schema JSON pertanyaan LLM → `SRS-cv-fusion-engine.md`
- Definisi threshold rekomendasi/pengingat & protokol data dari unit IoT → `SRS-iot-hardware.md`
- Diagram alur UX kedua mode → `DESIGN-ux-flow.md`
- Stack teknis Android (bahasa, library TFLite, dsb) → `TECH-mobile-app.md`
