# SRS — CV Model, LLM Question Generation & Fusion Engine

> Bagian dari Software Requirements Specification RipenAI. Fokus: requirement untuk model computer vision per-jenis-buah, mekanisme generate pertanyaan lanjutan via LLM, dan fusion scoring (Mode Konsumen).
> Requirement UI/alur aplikasi ada di `SRS-mobile-app.md`. Spesifikasi teknis implementasi ada di `TECH-ml-cv-fusion.md`.

---

## 1. Ruang Lingkup

Dokumen ini mencakup:
- Requirement model CV (klasifikasi kematangan per jenis buah).
- Requirement mekanisme deteksi ambiguitas (confidence rendah) yang memicu pertanyaan lanjutan.
- Requirement schema & kontrak LLM API untuk generate pertanyaan.
- Requirement fusion scoring (penggabungan confidence foto + jawaban user).

---

## 2. Requirement — Model CV

| ID | Requirement | Prioritas |
|---|---|---|
| FR-CV01 | Sistem HARUS menggunakan model terpisah per jenis buah (bukan satu model generalis untuk semua buah), karena penanda visual kematangan berbeda signifikan antar jenis buah | Wajib |
| FR-CV02 | Setiap model per-buah HARUS mengklasifikasi minimal 4-5 kategori kematangan: mentah, mengkal, matang, sangat matang, busuk (kategori dapat disesuaikan per jenis buah bila relevan) | Wajib |
| FR-CV03 | Model HARUS menghasilkan confidence score (probabilitas) per kelas, bukan hanya label akhir | Wajib |
| FR-CV04 | Model HARUS berbasis transfer learning dari MobileNetV2 pretrained, di-fine-tune dengan dataset foto buah lokal per jenis | Wajib |
| FR-CV05 | Model HARUS dikonversi ke format TFLite (quantized) agar dapat berjalan on-device tanpa internet | Wajib |
| FR-CV06 | Untuk jenis buah yang secara fundamental tidak dapat dinilai akurat dari foto saja (contoh: durian — penanda kematangan berupa bunyi ketukan, aroma, kelenturan duri, bukan tampilan visual), sistem HARUS menandai jenis buah ini sebagai kategori khusus dengan confidence dibatasi/disclaimer, BUKAN dipaksakan diklasifikasi seolah-olah akurat | Wajib |

---

## 3. Requirement — Deteksi Ambiguitas & Trigger Pertanyaan Lanjutan

| ID | Requirement | Prioritas |
|---|---|---|
| FR-AM01 | Sistem HARUS mendefinisikan ambang confidence (threshold) di bawah mana prediksi dianggap "ambigu" (nilai awal disarankan: confidence kelas tertinggi <70%, atau selisih confidence dua kelas teratas <15%) | Wajib |
| FR-AM02 | JIKA prediksi ambigu, sistem HARUS memicu pemanggilan LLM API untuk generate pertanyaan; JIKA TIDAK ambigu, sistem HARUS langsung menampilkan hasil tanpa pertanyaan tambahan (menghemat biaya API & waktu) | Wajib |
| FR-AM03 | Threshold ambiguitas HARUS dapat dikonfigurasi (bukan hardcoded permanen) untuk keperluan tuning setelah pengujian nyata | Disarankan |

---

## 4. Requirement — LLM Question Generation

### 4.1 Kontrak Input/Output

| ID | Requirement | Prioritas |
|---|---|---|
| FR-LLM01 | Prompt ke LLM HARUS menyertakan konteks spesifik: jenis buah, dua (atau lebih) kelas kematangan yang menjadi sumber ambiguitas, dan confidence masing-masing | Wajib |
| FR-LLM02 | LLM HARUS diminta menghasilkan output dalam format JSON terstruktur (menggunakan structured output/JSON mode API), BUKAN teks bebas yang perlu di-parse manual dengan regex/heuristik rentan gagal | Wajib |
| FR-LLM03 | Jumlah pertanyaan yang di-generate HARUS dibatasi 2-3 pertanyaan per sesi (menjaga UX tetap cepat) | Wajib |
| FR-LLM04 | Setiap pertanyaan dalam output JSON HARUS memiliki daftar opsi jawaban tertutup (multiple choice), TIDAK BOLEH berupa field jawaban bebas (freeform text) | Wajib |
| FR-LLM05 | Sistem HARUS memvalidasi struktur JSON yang diterima dari LLM sebelum di-render; JIKA struktur tidak valid, sistem HARUS jatuh ke fallback pertanyaan default (lihat FR-C07 di `SRS-mobile-app.md`) | Wajib |

### 4.2 Schema JSON (Kontrak Wajib)

```json
{
  "fruit_type": "string",
  "ambiguous_between": ["string", "string"],
  "questions": [
    {
      "id": "string",
      "text": "string (pertanyaan singkat, maks ±80 karakter)",
      "options": ["string", "string", "..."]
    }
  ]
}
```

| ID | Requirement | Prioritas |
|---|---|---|
| FR-LLM06 | Jumlah item dalam `questions` HARUS 2-3 | Wajib |
| FR-LLM07 | Setiap item `options` HARUS berisi 2-4 pilihan | Wajib |
| FR-LLM08 | Field `text` HARUS berupa pertanyaan yang bisa dijawab tanpa penjelasan tambahan (self-explanatory bagi user awam) | Disarankan |

---

## 5. Requirement — Fusion Scoring

### 5.1 v1 — Rule-Based Weighted Scoring (Wajib, tanpa training tambahan)

| ID | Requirement | Prioritas |
|---|---|---|
| FR-FS01 | Sistem HARUS menghitung skor akhir sebagai kombinasi linear dari confidence foto + bobot manual tiap jawaban terpilih | Wajib |
| FR-FS02 | Bobot tiap opsi jawaban HARUS didefinisikan per jenis buah berdasarkan riset domain (bukan hasil training model), didokumentasikan secara eksplisit dan dapat diaudit | Wajib |
| FR-FS03 | Sistem HARUS menetapkan ambang (threshold) skor akhir untuk memetakan ke kategori kematangan final (misal: >0.7 matang, 0.4-0.7 mengkal, <0.4 mentah) | Wajib |
| FR-FS04 | Fusion scoring v1 TIDAK BOLEH bergantung pada pemanggilan API/model tambahan di luar rule aritmatika sederhana (harus tetap cepat & offline-computable setelah jawaban diterima) | Wajib |

### 5.2 v2 — Data-Driven Fusion (Roadmap, opsional untuk v1)

| ID | Requirement | Prioritas |
|---|---|---|
| FR-FS05 | Sistem BOLEH mengumpulkan data pasangan (foto, jawaban quick-select, hasil verifikasi manual) selama penggunaan/pengujian sebagai calon dataset training | Opsional |
| FR-FS06 | JIKA data terkumpul mencukupi (disarankan minimal 100-200 sampel), sistem BOLEH melatih model ringan (Logistic Regression/Decision Tree) sebagai pengganti rule-based scoring | Roadmap v2 |
| FR-FS07 | Model v2 (jika dibangun) HARUS tetap dapat berjalan ringan on-device atau via inferensi cepat, tidak boleh menambah dependensi model besar/berat | Roadmap v2 |

---

## 6. Requirement Non-Fungsional

| ID | Requirement |
|---|---|
| NFR-CV01 | Total waktu dari foto diambil sampai hasil akhir ditampilkan (termasuk LLM call bila terjadi) HARUS ≤5 detik pada kondisi jaringan normal |
| NFR-CV02 | Biaya pemanggilan LLM API HARUS diminimalkan dengan hanya memanggil saat prediksi benar-benar ambigu (lihat FR-AM02), bukan di setiap sesi |
| NFR-CV03 | Semua bobot rule-based (FR-FS02) dan threshold (FR-AM01, FR-FS03) HARUS disimpan dalam file konfigurasi terpisah (bukan hardcoded di logic UI) agar mudah di-tuning tanpa mengubah kode aplikasi |

---

## 7. Referensi Silang

- Alur UI yang memanggil fusion engine ini → `SRS-mobile-app.md`
- Spesifikasi arsitektur model, prompt LLM lengkap, dan implementasi fusion → `TECH-ml-cv-fusion.md`
- Diagram alur data end-to-end → `DESIGN-architecture.md`
