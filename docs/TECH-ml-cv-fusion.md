# TECH — ML: CV Model, LLM Prompt Design, Fusion Scoring

> Spesifikasi teknis implementasi untuk `SRS-cv-fusion-engine.md`.

---

## 1. Model CV Per-Jenis-Buah

### 1.1 Arsitektur
- Basis: MobileNetV2 pretrained (ImageNet weights) via transfer learning.
- Fine-tuning: freeze layer awal, unfreeze beberapa layer akhir + tambah classification head baru sesuai jumlah kelas kematangan (4-5 kelas) per jenis buah.
- Training dilakukan **terpisah per jenis buah** (model file berbeda), sesuai FR-CV01.

```
Input (224x224x3)
   │
   ▼
MobileNetV2 base (frozen sebagian, pretrained ImageNet)
   │
   ▼
GlobalAveragePooling2D
   │
   ▼
Dense(128, relu) + Dropout(0.3)
   │
   ▼
Dense(N_kelas, softmax)   # N_kelas = 4-5 per jenis buah
```

### 1.2 Konversi ke TFLite
```
1. Train/fine-tune model di TensorFlow/Keras.
2. Convert ke TFLite dengan post-training quantization (int8 atau float16)
   untuk ukuran file lebih kecil & inferensi lebih cepat di HP.
3. Validasi akurasi tidak turun signifikan setelah quantization
   (bandingkan akurasi float32 vs quantized di test set yang sama).
4. Simpan sebagai <jenis_buah>_ripeness_v1.tflite
```

### 1.3 Penanganan Kasus Durian (dan buah sejenis)
- Model durian (jika dibuat) HARUS diberi label eksplisit "low-reliability" di metadata, dan aplikasi menampilkan disclaimer (FR-CV06/FR-C09).
- Alternatif: untuk v1, durian tidak masuk daftar buah yang didukung CV sama sekali — cukup diarahkan ke penjelasan edukatif ("kematangan durian dinilai dari bunyi ketukan & aroma, bukan visual") sebagai pendekatan paling jujur.

---

## 2. Deteksi Ambiguitas

```python
# Pseudocode — dijalankan setelah inferensi TFLite
probs = model_output  # array probabilitas per kelas
sorted_probs = sorted(probs, reverse=True)
top1, top2 = sorted_probs[0], sorted_probs[1]

is_ambiguous = (top1 < AMBIGUITY_THRESHOLD) or ((top1 - top2) < CONFIDENCE_GAP_THRESHOLD)
# AMBIGUITY_THRESHOLD default 0.70, CONFIDENCE_GAP_THRESHOLD default 0.15
# (nilai dari file konfigurasi, lihat TECH-mobile-app.md §6)
```

---

## 3. Prompt Design — LLM Question Generation

### 3.1 System Prompt (Template)

```
Kamu adalah asisten yang membuat pertanyaan singkat untuk membantu
menentukan kematangan buah, khusus untuk kasus di mana model computer
vision ragu antara dua kategori kematangan.

Aturan WAJIB:
1. Buat 2-3 pertanyaan singkat (maksimal ±80 karakter per pertanyaan).
2. Setiap pertanyaan HARUS punya 2-4 pilihan jawaban tertutup (bukan pertanyaan terbuka).
3. Pertanyaan harus bisa dijawab orang awam tanpa alat khusus
   (berdasarkan pengamatan visual/sentuhan/penciuman sederhana).
4. Fokus HANYA pada ciri yang membedakan dua kategori yang disebutkan.
5. Output HARUS berupa JSON valid sesuai schema yang diberikan,
   tanpa teks tambahan di luar JSON.
```

### 3.2 User Prompt (Template, diisi dinamis)

```
Jenis buah: {fruit_type}
Model ragu antara kategori: "{class_a}" (confidence {conf_a}) dan
"{class_b}" (confidence {conf_b}).

Buatkan pertanyaan sesuai aturan di atas untuk membantu membedakan
kedua kategori tersebut.
```

### 3.3 Schema Output (JSON Mode / Structured Output)

Gunakan fitur structured output (JSON schema enforcement) dari API yang dipakai, agar response terjamin sesuai schema berikut (identik dengan kontrak di `SRS-cv-fusion-engine.md` §4.2):

```json
{
  "type": "object",
  "properties": {
    "fruit_type": {"type": "string"},
    "ambiguous_between": {
      "type": "array",
      "items": {"type": "string"}
    },
    "questions": {
      "type": "array",
      "minItems": 2,
      "maxItems": 3,
      "items": {
        "type": "object",
        "properties": {
          "id": {"type": "string"},
          "text": {"type": "string"},
          "options": {
            "type": "array",
            "minItems": 2,
            "maxItems": 4,
            "items": {"type": "string"}
          }
        },
        "required": ["id", "text", "options"]
      }
    }
  },
  "required": ["fruit_type", "ambiguous_between", "questions"]
}
```

### 3.4 Fallback Default Questions

Simpan set pertanyaan default per jenis buah sebagai file statis (bundled di app, tidak perlu API):

```json
// fallback_questions/mangga.json
{
  "fruit_type": "mangga",
  "questions": [
    {"id": "tekstur", "text": "Tekstur saat ditekan?", "options": ["Keras", "Agak lunak", "Lunak"]},
    {"id": "warna", "text": "Warna kulit?", "options": ["Hijau", "Kuning-hijau", "Kuning penuh"]}
  ]
}
```

---

## 4. Fusion Scoring — Implementasi v1 (Rule-Based)

```python
# Pseudocode
def compute_final_score(cv_confidence, answers, weights_config, fruit_type):
    score = cv_confidence  # baseline dari model CV (confidence kelas top-1)
    weights = weights_config[fruit_type]

    for question_id, selected_option in answers.items():
        key = f"{question_id}_{selected_option}"  # contoh: "tekstur_lunak"
        score += weights.get(key, 0.0)

    score = clamp(score, 0.0, 1.0)

    if score > 0.7:
        final_label = "matang"
    elif score > 0.4:
        final_label = "mengkal"
    else:
        final_label = "mentah"

    return final_label, score
```

Bobot awal (`weights_config`) ditentukan dari riset domain/wawancara informal dengan yang paham buah (bisa keluarga petani), didokumentasikan per jenis buah, dan disimpan di file konfigurasi (lihat `TECH-mobile-app.md` §6) — bukan hasil training.

---

## 5. Fusion Scoring — Roadmap v2 (Data-Driven)

### 5.1 Pengumpulan Data
Setiap sesi lengkap (foto → CV confidence → jawaban quick-select → label hasil verifikasi manual, jika tersedia saat testing) disimpan sebagai satu baris data:

```
| foto_id | fruit_type | cv_top1_class | cv_confidence | jawaban_tekstur | jawaban_warna | label_verifikasi_manual |
```

### 5.2 Training (Setelah Data Cukup, ~100-200 sampel)

```python
from sklearn.linear_model import LogisticRegression

# Fitur: cv_confidence + one-hot encoding jawaban quick-select
X = build_features(cv_confidence, answers_onehot)
y = verified_labels

model = LogisticRegression(max_iter=1000)
model.fit(X, y)
# Export sebagai file ringan (pickle/ONNX) untuk inferensi cepat
```

Model ini menggantikan fungsi `compute_final_score` di atas, tapi tetap harus ringan (bukan neural network besar) agar tetap cepat dan tidak menambah beban komputasi berarti.

---

## 6. Referensi Silang

- Requirement fungsional lengkap → `SRS-cv-fusion-engine.md`
- Implementasi Android (pemanggilan API, render UI, parsing) → `TECH-mobile-app.md`
- Diagram alur data end-to-end → `DESIGN-architecture.md`
