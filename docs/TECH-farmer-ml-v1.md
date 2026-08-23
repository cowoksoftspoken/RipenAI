# TECH - Farmer ML V1

Farmer ML V1 adalah model lokal utama untuk memperkirakan risiko kondisi buah di
dalam satu wadah. Model ini bukan pengganti inspeksi manusia dan bukan model
untuk menentukan kematangan satu buah dari foto. Jalur keselamatan tetap dimulai
dari rule engine yang transparan di Android, sementara model memberi sinyal
prediktif tambahan.

## Kontrak input dan output

- Input: 32 pembacaan terakhir, satu pembacaan setiap 15 menit.
- Kanal sensor: `temperature_c`, `humidity_percent`, `mq3_proxy_adc`.
- Identitas komoditas: one-hot untuk 9 buah (apel, pisang, mangga, jeruk,
  pepaya, nanas, tomat, alpukat, durian).
- Feature vector: `32 * 3 + 9 = 105` float, dinormalisasi memakai metadata model.
- Output TFLite: `[risk_score, hours_to_action, safe_probability,
  attention_probability, urgent_probability]`.

`mq3_proxy_adc` sengaja disebut proxy. MQ-3 bukan sensor ethylene selektif dan
nilai ADC tidak boleh dipresentasikan sebagai ppm tanpa kalibrasi terhadap batch
buah nyata.

## Dataset sintetis yang menantang

Generator ada di `scripts/generate_farmer_synthetic.py`. Ia memodelkan profil
buah berbeda, perubahan kualitas/mold, pengaruh suhu dan kelembapan, sensitivitas
silang MQ-3, gas burst, ventilasi, pintu wadah dibuka, drift, bias sensor,
warm-up, flatline singkat, outlier, dan noise label. Dengan begitu model tidak
boleh hanya menghafal "gas tinggi = urgent".

Dataset 12.000 trajectory dibagi berdasarkan trajectory, bukan potongan window
yang sama:

| Split | Isi | Jumlah |
| --- | --- | ---: |
| train | 8 skenario gangguan yang diketahui | 8.040 |
| validation | skenario train, untuk pemilihan checkpoint | 1.560 |
| holdout | skenario train, evaluasi akhir standar | 1.200 |
| OOD | `mixed_stress`, kombinasi gangguan yang tidak dilatih | 1.200 |

Target juga diberi noise proxy/manual-label. Ini membuat metrik lebih realistis,
tetapi tetap tidak boleh disebut akurasi lapangan.

## Evaluasi model V1 saat ini

| Split | Risk MAE | Horizon MAE | Accuracy | Macro-F1 | Urgent recall |
| --- | ---: | ---: | ---: | ---: | ---: |
| holdout | 0,177 | 17,9 jam | 70,9% | 0,569 | 73,1% |
| OOD mixed-stress | 0,222 | 20,6 jam | 64,1% | 0,544 | 47,6% |

Penurunan OOD adalah hasil yang diharapkan dari evaluasi yang jujur. Android
memakai rule engine 75% + model V1 25%, hanya saat confidence kelas model
minimal 65%. Jika model gagal dimuat, data kurang, atau confidence rendah,
aplikasi tetap berjalan dengan rule engine.

## Reproducible commands

```powershell
python scripts/generate_farmer_synthetic.py --samples 12000
python scripts/train_farmer_model_cuda.py --epochs 40
python scripts/convert_farmer_onnx_to_tflite.py `
  --input outputs/farmer_model_v1_cuda/farmer_risk_cuda.onnx `
  --output outputs/farmer_model_v1_tflite `
  --feature-dim 105
python scripts/evaluate_farmer_tflite.py
```

Model yang dipakai Android adalah `farmer_risk.tflite` dan metadata
`farmer_model_config.json` di `android-app/app/src/main/assets/`.

## Pembelajaran lokal dari penggunaan

Android tidak mengubah bobot neural TFLite langsung di perangkat. Setelah
petani memeriksa kondisi nyata dan memilih label `Aman`, `Perhatian`, atau
`Urgent`, aplikasi memperbarui lapisan kalibrasi kecil per jenis buah secara
lokal. Lapisan ini memakai learning rate yang menurun, bias dibatasi, jumlah
sample dibatasi, dan tidak mengirim data sensor keluar dari ponsel. Bobot dasar
model tetap beku sehingga feedback buruk atau sedikit tidak merusak model utama.

Setiap prediksi berikutnya memakai bias kalibrasi yang tersimpan. Jumlah feedback
ditampilkan di kartu risiko agar proses belajar dapat diaudit. Reset kalibrasi
per buah dapat ditambahkan saat deployment jika data pengguna perlu dihapus.

## Kalibrasi sensor dan data lapangan sebelum produksi luas

1. Bakar-in MQ-3 dan catat baseline per unit pada wadah kosong.
2. Simpan pembacaan DHT22/MQ-3 bersama timestamp, buah, jumlah buah, ventilasi,
   dan kondisi visual manual.
3. Tandai kejadian nyata: aman, perlu perhatian, urgent, serta waktu sampai
   harus dijual/dipisahkan.
4. Uji baseline rule engine dan model pada split berbasis waktu/wadah; jangan
   mencampur pembacaan dari trajectory yang sama ke train dan test.
5. Kalibrasikan threshold dan confidence gate hanya setelah jumlah log nyata
   memadai. Synthetic-only tidak cukup untuk mengubah bobot konservatif Android.
