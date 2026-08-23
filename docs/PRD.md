# RipenAI — Product Requirements Document (PRD)

> **Status:** Living document, mencerminkan hasil revisi arsitektur pasca lolos seleksi Intel AI Global Impact Festival.
> **Dokumen terkait:** lihat `INDEX.md` untuk peta seluruh dokumentasi.

---

## 1. Ringkasan Produk

RipenAI adalah aplikasi Android **dua-mode** yang membantu dua kelompok pengguna berbeda dalam mengambil keputusan terkait kematangan buah:

1. **Mode Konsumen (Awam)** — membantu orang biasa (misal remaja/pembeli di pasar) memilih buah matang yang tepat, menggunakan foto + computer vision, dibantu pertanyaan lanjutan yang di-generate AI saat model ragu.
2. **Mode Petani (Pasca Panen)** — membantu petani memantau kondisi buah hasil panen di dalam wadah/keranjang penyimpanan, memberi rekomendasi & pengingat kapan buah harus segera digunakan/dijual sebelum busuk, menggunakan 1 unit IoT offline-first yang ditempel langsung di wadah (tanpa perlu internet).

Kedua mode menjawab pertanyaan yang sama secara konsep ("kapan/apakah buah ini matang atau layak dipanen") tapi dari sudut pandang, skala data, dan kondisi konektivitas yang sepenuhnya berbeda — sehingga keduanya dirancang sebagai **dua subsistem yang independen namun saling melengkapi**, bukan satu pipeline tunggal.

---

## 2. Latar Belakang & Masalah

### 2.1 Masalah Konsumen Awam
Orang tanpa pengalaman berkebun/berdagang buah kesulitan menilai kematangan buah hanya dari tampilan visual di toko/pasar. Untuk sebagian jenis buah (misal durian), kematangan bahkan **tidak bisa** dinilai dari foto sama sekali — butuh sinyal non-visual seperti tekstur, aroma, atau bunyi ketukan.

### 2.2 Masalah Penyimpanan Pasca Panen
Setelah dipanen, buah biasanya dikumpulkan dalam wadah/keranjang sebelum dijual/didistribusikan. Selama masa tunggu ini, kondisi lingkungan (suhu, kelembapan, gas hasil proses pematangan/fermentasi) terus memengaruhi kecepatan pematangan lanjutan dan risiko busuk. Petani sulit memantau kondisi tiap wadah secara manual terus-menerus, terutama bila disimpan di gudang/pos panen yang jarang dicek.

Tantangan utama: **lokasi penyimpanan (gudang/pos panen) tidak selalu punya WiFi/internet**, dan petani tidak bisa terus-menerus berada di dekat wadah untuk memonitor manual.

### 2.3 Kenapa "hanya foto" tidak cukup
Divalidasi lewat diskusi teknis proyek: model computer vision (CV) sebaik apa pun tidak bisa mengekstrak informasi yang **tidak ada di gambar** — misal tekstur saat ditekan, aroma, atau bunyi. Ini bukan soal "akurasi model kurang tinggi", tapi keterbatasan modalitas data. Solusi harus multi-modal, bukan sekadar model CV yang lebih besar.

---

## 3. Tujuan Produk & Metrik Sukses

| Tujuan | Metrik |
|---|---|
| Prediksi kematangan buah lebih akurat dari CV-only | Naik ≥15% akurasi dibanding baseline foto-saja (per jenis buah), diukur di test set berlabel manual |
| Sistem tetap fungsional tanpa internet (mode petani) | 100% fitur inti mode petani (baca sensor, rekomendasi, pengingat, sync data) berjalan tanpa koneksi internet aktif |
| Bermanfaat nyata untuk petani | Minimal 1 studi kasus/simulasi yang menunjukkan rekomendasi/pengingat wadah terverifikasi sesuai kondisi buah sebenarnya |
| Kejelasan fungsi IoT terhadap juri Intel | Dokumentasi & demo mampu menjelaskan pemisahan peran CV (per-buah, pra-beli) vs IoT (per-wadah, pasca-panen, tren waktu) tanpa ambiguitas |
| Biaya prototype terjangkau untuk tim sekolah | Total BOM hardware prototype (1-2 unit IoT wadah) ≤ Rp 400.000 |

---

## 4. Target Pengguna & Persona

### Persona 1 — "Rani", Konsumen Awam
- Usia 16-30 tahun, tinggal di kota, biasa belanja buah di pasar/supermarket.
- Punya HP dengan akses internet (data seluler/WiFi) hampir selalu aktif.
- Tidak punya pengalaman bertani/berdagang buah.
- Kebutuhan: keputusan cepat "buah ini matang atau belum?" sebelum membeli.

### Persona 2 — "Pak Darto", Petani/Pengumpul Hasil Panen
- Mengumpulkan hasil panen buah (mangga, pisang, durian, dsb) dalam wadah/keranjang sebelum dijual/didistribusikan.
- Lokasi penyimpanan (gudang/pos panen) sering minim/tanpa sinyal internet.
- Rutin mendekati/mengecek wadah penyimpanan tiap hari, tapi tidak bisa memantau kondisi lingkungan wadah secara manual terus-menerus.
- Kebutuhan: rekomendasi & pengingat kapan buah di wadah tertentu harus segera digunakan/dijual sebelum busuk, tanpa harus online.

---

## 5. Ruang Lingkup Produk

### 5.1 Mode Konsumen (online-first)
- Input: foto buah (wajib) + jawaban pertanyaan terstruktur (kondisional, saat model ragu).
- Proses: CV per-jenis-buah → cek confidence → jika ambigu, panggil LLM API untuk generate pertanyaan spesifik → render UI dinamis (chip/pilihan, bukan teks bebas) → fusion score → hasil akhir.
- Kondisi: butuh internet untuk pemanggilan LLM API; fallback tersedia bila offline atau API gagal.
- Detail lengkap: lihat `SRS-mobile-app.md` (bagian Mode Konsumen) dan `SRS-cv-fusion-engine.md`.

### 5.2 Mode Petani (offline-first, per-wadah)
- Input: data sensor lingkungan (suhu & kelembapan via DHT22, gas hasil pematangan/fermentasi via MQ-3) dari 1 unit IoT yang ditempel langsung di wadah/keranjang buah.
- Proses: unit IoT membaca sensor berkala → simpan lokal & pancarkan WiFi AP sendiri → HP petani connect langsung ke WiFi unit IoT saat berada dekat wadah (tanpa internet, tanpa gateway/LoRa terpisah) → app tarik data & tren → AI berperan sebagai **rekomender** (kapan buah sebaiknya digunakan/dijual) dan **pengingat** (notifikasi saat kondisi mulai berisiko).
- Kondisi: tidak butuh internet untuk fungsi inti; internet hanya opsional untuk backup/histori jangka panjang.
- Detail lengkap: lihat `SRS-iot-hardware.md`, `TECH-iot-firmware.md`, dan `DESIGN-architecture.md`.

---

## 6. Fitur Utama

### 6.1 Mode Konsumen
| Fitur | Deskripsi | Prioritas |
|---|---|---|
| Deteksi kematangan dari foto | Model CV per-jenis-buah (MobileNetV2 fine-tuned), output kelas + confidence | Wajib (v1) |
| Kategori kematangan diperluas | Minimal 4-5 tahap per buah: mentah, mengkal, matang, sangat matang, busuk | Wajib (v1) |
| Pertanyaan lanjutan dinamis (LLM-generated) | Saat confidence rendah, LLM API generate 2-3 pertanyaan spesifik ke ambiguitas yang terjadi, dirender sebagai UI terstruktur | Wajib (v1) |
| Fallback pertanyaan default | Set pertanyaan default per jenis buah jika API gagal/timeout/offline | Wajib (v1) |
| Fusion scoring | Gabungkan confidence foto + bobot jawaban → hasil akhir (rule-based di v1) | Wajib (v1) |
| Fusion model data-driven | Upgrade dari rule-based ke Logistic Regression/Decision Tree terlatih dari data terkumpul | Roadmap (v2) |
| Penanganan kasus khusus (durian, dsb) | Untuk buah yang secara fundamental tidak bisa dinilai dari foto, beri disclaimer & arahkan ke sinyal non-visual yang relevan | Wajib (v1), terbatas |

### 6.2 Mode Petani
| Fitur | Deskripsi | Prioritas |
|---|---|---|
| Monitoring kondisi wadah | Baca suhu & kelembapan (DHT22) serta gas pematangan/fermentasi (MQ-3) secara berkala dari unit IoT di wadah | Wajib (v1) |
| Indikator visual langsung di alat | LED RGB di unit IoT (hijau/kuning/merah) sebagai status cepat tanpa perlu buka app | Wajib (v1) |
| Rekomendasi AI | Berdasarkan tren sensor, sistem merekomendasikan kapan buah di wadah sebaiknya digunakan/dijual/dipisah | Wajib (v1) |
| Pengingat (reminder) | Notifikasi saat skor risiko wadah naik atau saat lama tidak dicek | Wajib (v1) |
| Sync offline langsung ke unit IoT | HP connect langsung ke WiFi AP unit IoT saat dekat wadah, tanpa gateway/LoRa | Wajib (v1) |
| Backup histori ke cloud | Sync opsional dari unit IoT ke server saat ada internet | Opsional (v1) |
| Farmer ML V1 + kalibrasi lokal | Model TFLite terlatih dari trajektori sensor sintetis yang menantang; label pemeriksaan pengguna memperbarui kalibrasi lokal secara terbatas | Wajib (v1) |

---

## 7. Non-Goals / Di Luar Cakupan (v1)

- Tidak membangun jaringan sensor jarak jauh (LoRa/LoRaWAN) — di luar cakupan karena scope v1 hanya 1 unit IoT per wadah dengan jangkauan WiFi lokal.
- Tidak menjanjikan rekomendasi berbasis machine learning prediktif di v1 — ini didokumentasikan sebagai roadmap v2, bukan fitur v1 (v1 rule-based).
- Tidak menargetkan monitoring kebun/lahan berskala luas dalam versi prototype kompetisi — fokus v1 adalah unit penyimpanan pasca panen (wadah/keranjang).
- Tidak membangun deteksi kematangan durian berbasis foto — karena secara teknis foto tidak menyimpan informasi yang relevan untuk itu.

---

## 8. Asumsi & Batasan

- Mode Konsumen berasumsi pengguna punya akses internet aktif (data seluler/WiFi) — asumsi ini valid karena konteks pembelian buah biasanya di area perkotaan/pasar dengan sinyal.
- Mode Petani berasumsi **tidak ada** internet di lokasi penyimpanan, tapi petani cukup mendekat secara fisik ke wadah/keranjang untuk sinkronisasi langsung (connect ke WiFi AP unit IoT, tanpa gateway/LoRa terpisah).
- Model CV memerlukan dataset foto per jenis buah yang cukup (ratusan-ribuan gambar per kelas) menggunakan transfer learning dari MobileNetV2 pretrained.
- Fusion model v1 (rule-based) tidak memerlukan data training tambahan; bobot ditentukan dari riset domain/pakar.

---

## 9. Risiko & Mitigasi

| Risiko | Dampak | Mitigasi |
|---|---|---|
| LLM API down/lambat saat demo | Fitur pertanyaan lanjutan gagal | Fallback ke set pertanyaan default per buah, timeout dengan batas jelas (misal 3 detik) |
| Sensor gas MQ-3 kurang presisi/butuh kalibrasi per komoditas | Rekomendasi kurang akurat | Kalibrasi manual per jenis buah saat pengujian, dokumentasikan sebagai batasan v1 |
| Klaim "rekomendasi/prediksi" berlebihan ke juri | Kredibilitas turun saat ditanya detail teknis | Jelaskan bahwa Farmer ML V1 adalah model bantu TFLite dengan data sintetis, rule engine tetap pengaman utama, dan validasi lapangan masih diperlukan |
| Durian/buah kompleks lain dinilai tidak akurat dari foto | Ekspektasi user tidak terpenuhi | Beri disclaimer eksplisit di UI + arahkan ke sinyal non-visual (untuk mode konsumen) |
| Baterai unit IoT habis saat demo/penyimpanan lama | Demo gagal, data terputus | Gunakan power bank/18650 dengan estimasi daya tahan yang sudah dihitung untuk durasi demo |

---

## 10. Roadmap (v1 vs v2)

| Komponen | v1 (Prototype Kompetisi) | v2 (Roadmap Produksi) |
|---|---|---|
| Fusion scoring (Konsumen) | Rule-based weighted scoring | Logistic Regression/Decision Tree data-driven |
| Rekomendasi wadah (Petani) | Rule-based threshold pada tren sensor | Model prediktif dari data historis multi-wadah/multi-musim |
| Unit IoT | 1 ESP32+DHT22+MQ-3+LED per wadah, WiFi AP langsung | Multi-wadah dengan hub agregasi opsional untuk skala lebih besar |
| Daya | Power bank/baterai 18650 | Baterai tahan lama/solar kecil untuk deployment jangka panjang |
| Konektivitas darurat | Tidak ada | Modul GSM/SMS untuk alert kritis (opsional, skala besar) |

---

## 11. Keterkaitan SDG

RipenAI selaras dengan:
- **SDG 2 (Zero Hunger):** membantu petani mengurangi kehilangan hasil pasca-panen (buah busuk di penyimpanan) melalui rekomendasi & pengingat dini.
- **SDG 8 (Decent Work & Economic Growth):** meningkatkan efisiensi kerja petani dengan mengurangi kebutuhan monitoring manual terus-menerus.
- **SDG 12 (Responsible Consumption & Production):** membantu konsumen memilih buah matang dengan tepat, mengurangi limbah makanan dari pembelian buah yang salah nilai kematangannya.

---

## 12. Dependensi Dokumen Lain

Dokumen ini adalah level produk (business/user-facing). Untuk detail requirement fungsional & teknis, lihat:
- `SRS-mobile-app.md` — requirement UI/UX & alur aplikasi kedua mode.
- `SRS-cv-fusion-engine.md` — requirement model CV, LLM question generation, fusion scoring.
- `SRS-iot-hardware.md` — requirement unit IoT wadah, sinkronisasi WiFi langsung, logika rekomendasi & pengingat.
- `TECH-mobile-app.md`, `TECH-ml-cv-fusion.md`, `TECH-iot-firmware.md` — spesifikasi teknis implementasi.
- `DESIGN-architecture.md`, `DESIGN-ux-flow.md` — diagram sistem & alur UX.
