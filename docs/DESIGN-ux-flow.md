# DESIGN — Alur UX (Wireframe ASCII)

> Alur layar untuk Mode Konsumen dan Mode Petani. Untuk requirement detail tiap layar, lihat `SRS-mobile-app.md`.

---

## 1. Layar Awal — Pemilihan Mode

```
┌─────────────────────────────┐
│         RipenAI              │
│                               │
│   ┌───────────────────────┐  │
│   │   🍎 Mode Konsumen      │  │
│   │  Cek kematangan buah   │  │
│   │  yang mau kamu beli    │  │
│   └───────────────────────┘  │
│                               │
│   ┌───────────────────────┐  │
│   │   🌱 Mode Petani        │  │
│   │  Pantau kondisi wadah  │  │
│   └───────────────────────┘  │
└─────────────────────────────┘
```

---

## 2. Alur Mode Konsumen

```
┌───────────────┐     ┌────────────────┐     ┌─────────────────┐
│ Pilih jenis    │────▶│ Ambil/pilih     │────▶│ Hasil awal +      │
│ buah           │     │ foto            │     │ confidence         │
└───────────────┘     └────────────────┘     └─────────┬─────────┘
                                                           │
                                        ┌──────────────────┴──────────────────┐
                                        │                                       │
                              confidence tinggi                      confidence rendah
                                        │                                       │
                                        ▼                                       ▼
                          ┌───────────────────────┐              ┌───────────────────────┐
                          │  Layar Hasil Akhir      │              │ Loading: "Menyiapkan   │
                          │  - Kelas kematangan     │              │ pertanyaan tambahan..." │
                          │  - Confidence            │              └───────────┬───────────┘
                          │  - Penjelasan singkat    │                          │
                          └───────────────────────┘                          ▼
                                        ▲                       ┌───────────────────────────┐
                                        │                       │  Pertanyaan Terstruktur     │
                                        │                       │                              │
                                        │                       │  Tekstur saat ditekan?       │
                                        │                       │  [Keras][Agak lunak][Lunak]  │
                                        │                       │                              │
                                        │                       │  Warna kulit?                 │
                                        │                       │  [Hijau][Kuning-hijau][Kuning] │
                                        │                       └───────────────┬───────────────┘
                                        │                                       │
                                        └───────────────── (fusion scoring) ────┘
```

### 2.1 Kasus Khusus — Buah Tidak Bisa Dinilai dari Foto (mis. Durian)

```
┌───────────────────────────────────────────┐
│  ⚠️  Kematangan durian sulit dinilai dari    │
│      foto saja.                              │
│                                               │
│  Durian biasanya dinilai dari:                │
│  • Bunyi saat diketuk                         │
│  • Aroma yang tercium                          │
│  • Kelenturan duri                             │
│                                               │
│  [ Pelajari cara cek durian manual → ]         │
└───────────────────────────────────────────┘
```

---

## 3. Alur Mode Petani

```
┌───────────────────────────┐
│   Dashboard Wadah            │
│                              │
│  Wadah 1  🟢 Aman            │
│  Suhu 28°C · Lembap 65%      │
│                              │
│  Wadah 2  🟡 Perhatian        │
│  Level gas naik cepat         │
│  6 jam terakhir               │
│                              │
│  Wadah 3  🔴 Segera gunakan   │
│  Skor risiko tinggi            │
│                              │
│  [+ Tambah unit wadah baru]    │
└─────────────┬───────────────┘
              │ tap salah satu wadah
              ▼
┌───────────────────────────┐
│   Detail Wadah 2              │
│                              │
│   [Grafik tren suhu,          │
│    kelembapan, level gas       │
│    beberapa hari terakhir]     │
│                              │
│   Rekomendasi:                │
│   Gunakan/jual dalam ±2 hari    │
│                              │
│   Status sync: ✅ terhubung     │
│   ke unit ini, baru saja        │
└───────────────────────────┘
```

### 3.1 Status Sinkronisasi (Belum Terhubung ke Unit IoT)

```
┌───────────────────────────────────────┐
│  ⚠️  Belum sinkron sejak kemarin 18:40   │
│                                          │
│  Menampilkan data terakhir yang          │
│  tersimpan. Dekati wadah untuk            │
│  terhubung otomatis ke WiFi unit ini      │
│  dan memperbarui data.                    │
└───────────────────────────────────────┘
```

---

## 4. Prinsip Desain UI (Berlaku di Kedua Mode)

| Prinsip | Alasan |
|---|---|
| Pilihan tertutup (chip/button), bukan input teks bebas | Menghindari kebutuhan parsing NLP berat & menjaga hasil tetap konsisten (lihat FR-LLM04, FR-C05) |
| Selalu tampilkan confidence/penjelasan, bukan hanya label mentah | Transparansi ke user soal seberapa yakin sistem |
| Disclaimer eksplisit untuk kasus yang secara teknis tidak reliable (durian) | Kejujuran teknis > kesan "AI bisa semua", penting untuk kredibilitas di depan juri |
| Indikator status sinkron selalu terlihat (Mode Petani) | Petani perlu tahu apakah data yang dilihat itu real-time atau data lama |
| Kontras tinggi, ikon jelas (Mode Petani) | Kondisi penggunaan outdoor, literasi digital bervariasi |

---

## 5. Referensi Silang

- Requirement fungsional tiap layar → `SRS-mobile-app.md`
- Arsitektur data di balik tiap layar → `DESIGN-architecture.md`
- Implementasi teknis Android → `TECH-mobile-app.md`
