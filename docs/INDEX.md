# RipenAI — Dokumentasi Index

> Dokumen ini adalah peta navigasi seluruh dokumentasi teknis RipenAI. Baca bagian ini dulu sebelum membuka file lain — pilih file yang relevan dengan task, jangan baca semua file sekaligus (hemat konteks/token).

---

## Cara Pakai Index Ini

Tiap entri di bawah punya: **file**, **isi singkat**, dan **kapan dibuka**. Kalau task kamu berkaitan dengan salah satu area di bawah, buka HANYA file yang relevan.

---

## 1. Level Produk (Business & Requirement)

| File | Isi | Buka kalau... |
|---|---|---|
| [`PRD.md`](./PRD.md) | Problem statement, persona, tujuan produk, fitur utama, roadmap v1/v2, keterkaitan SDG | Butuh konteks "kenapa" produk ini dibangun, presentasi ke juri/stakeholder, atau perlu justifikasi keputusan produk |

---

## 2. Level Requirement Fungsional (SRS)

| File | Isi | Buka kalau... |
|---|---|---|
| [`SRS-mobile-app.md`](./SRS-mobile-app.md) | Requirement UI/alur aplikasi Android untuk Mode Konsumen & Mode Petani, error handling | Mengerjakan/mengubah alur layar, navigasi mode, penanganan offline/online di app |
| [`SRS-cv-fusion-engine.md`](./SRS-cv-fusion-engine.md) | Requirement model CV per-buah, deteksi ambiguitas, kontrak LLM question generation, fusion scoring | Mengerjakan logika prediksi kematangan, integrasi LLM API, atau scoring akhir Mode Konsumen |
| [`SRS-iot-hardware.md`](./SRS-iot-hardware.md) | Requirement unit IoT per wadah, WiFi AP lokal, logika rekomendasi & pengingat rule-based | Mengerjakan/mengubah logika sensor, threshold rekomendasi, atau perilaku sinkronisasi Mode Petani |

---

## 3. Level Spesifikasi Teknis (TECH)

| File | Isi | Buka kalau... |
|---|---|---|
| [`TECH-mobile-app.md`](./TECH-mobile-app.md) | Stack Android, struktur modul, implementasi inferensi TFLite, sinkronisasi langsung ke unit IoT, contoh kode Kotlin | Menulis/mengubah kode Android, integrasi TFLite di app, atau implementasi sync WiFi lokal |
| [`TECH-ml-cv-fusion.md`](./TECH-ml-cv-fusion.md) | Arsitektur model CV, konversi TFLite, prompt LLM lengkap + schema JSON, pseudocode fusion scoring v1 & roadmap v2 | Melatih/mengonversi model CV, menulis prompt LLM, atau implementasi rule-based/data-driven fusion |
| [`TECH-iot-firmware.md`](./TECH-iot-firmware.md) | BOM lengkap dengan harga, pseudocode firmware unit IoT (ESP32+DHT22+MQ-3+LED), format data, endpoint HTTP lokal, logika rekomendasi | Menulis firmware ESP32, mendesain payload data, atau menghitung budget hardware |

---

## 4. Level Desain Sistem & UX

| File | Isi | Buka kalau... |
|---|---|---|
| [`DESIGN-architecture.md`](./DESIGN-architecture.md) | Diagram arsitektur sistem end-to-end, data flow kedua mode, penjelasan koneksi langsung unit IoT-HP, skalabilitas multi-wadah | Butuh gambaran besar bagaimana semua komponen terhubung, atau menjelaskan sistem ke pihak lain (juri, tim) |
| [`DESIGN-ux-flow.md`](./DESIGN-ux-flow.md) | Wireframe ASCII tiap layar, alur navigasi, prinsip desain UI | Mendesain/mengubah tampilan layar, atau butuh referensi visual alur aplikasi |

---

## 5. Peta Cepat Berdasarkan Task

| Task yang mau dikerjakan | File yang perlu dibuka |
|---|---|
| Menjelaskan project ke juri/dokumen submission Intel | `PRD.md` |
| Menambah jenis buah baru di Mode Konsumen | `SRS-cv-fusion-engine.md` + `TECH-ml-cv-fusion.md` |
| Mengubah alur pertanyaan LLM | `SRS-cv-fusion-engine.md` (§4) + `TECH-ml-cv-fusion.md` (§3) |
| Mengubah bobot/threshold fusion scoring | `TECH-mobile-app.md` (§6, file konfigurasi) + `TECH-ml-cv-fusion.md` (§4) |
| Menambah sensor baru di unit IoT wadah | `SRS-iot-hardware.md` + `TECH-iot-firmware.md` |
| Mengubah logika rekomendasi/pengingat petani | `SRS-iot-hardware.md` (§5-6) + `TECH-iot-firmware.md` (§4) |
| Mendesain ulang layar/UI | `DESIGN-ux-flow.md` + `SRS-mobile-app.md` |
| Menghitung ulang budget hardware | `TECH-iot-firmware.md` (§1) |
| Menjelaskan kenapa arsitektur dipisah CV vs IoT | `DESIGN-architecture.md` (§4) + `PRD.md` (§5) |

---

## 6. Prinsip Penting yang Berlaku di Seluruh Dokumen (Jangan Dilanggar)

Beberapa keputusan arsitektur fundamental yang HARUS tetap konsisten di semua perubahan ke depan:

1. **CV dan IoT adalah dua subsistem independen** — CV menjawab "buah spesifik ini matang atau tidak, sekarang" (Mode Konsumen, pra-beli), IoT menjawab "kondisi wadah/keranjang ini bagaimana, dari waktu ke waktu" (Mode Petani, pasca panen). Jangan memaksa keduanya jadi satu pipeline tunggal.
2. **Pertanyaan lanjutan (LLM atau default) selalu berupa pilihan tertutup**, tidak pernah teks bebas — ini menjaga sistem tetap reliable tanpa NLP tambahan.
3. **Mode Petani harus 100% berfungsi tanpa internet.** Internet hanya opsional untuk backup cloud.
4. **Fusion scoring v1 adalah rule-based, bukan ML terlatih** — jangan mengklaim ini sebagai "model AI" ke juri sebelum benar-benar upgrade ke v2 dengan data yang cukup.
5. **Durian (dan buah sejenis) tidak dipaksa dinilai akurat dari foto** — ini keterbatasan modalitas data, bukan kekurangan model yang bisa "diperbaiki dengan training lebih banyak".
6. **Mode Petani berskala per-wadah, bukan per-kebun.** Satu unit IoT (ESP32+DHT22+MQ-3+LED) melayani satu wadah, berfungsi sekaligus sebagai sensor dan WiFi server — tidak ada gateway atau LoRa terpisah. Multi-wadah berarti multi-unit independen, bukan satu jaringan besar.
7. **AI di Mode Petani berperan sebagai rekomender & pengingat**, bukan pengklasifikasi buah individual — ia membaca tren kondisi wadah dari waktu ke waktu, bukan menilai satu buah sekali lihat (itu tugas CV di Mode Konsumen).
