# RipenAI model validation

## Model yang dipakai APK

- `native_cuda_candidate_3stage.tflite`: model utama 21 kelas, multi-buah, tahap `unripe`, `ripe`, dan `overripe`.
- `rotten_detector_cuda.tflite`: safety detector biner terpisah untuk sinyal busuk yang terlihat. Jika probabilitas busuk >= 0.50, hasil utama dioverride menjadi `Busuk` sebelum pertanyaan konfirmasi.
- Keduanya dilatih dengan PyTorch CUDA pada NVIDIA GeForce MX450, diekspor melalui ONNX lalu dikonversi ke TFLite float32.

## Hasil

| Evaluasi | Model utama | Rotten detector |
| --- | ---: | ---: |
| Test internal | 85.74% exact fruit + stage | 96.79% binary accuracy |
| Stage internal | 91.46% | Precision busuk 95.06% |
| BananaImageBD eksternal | 92.56% stage setelah pilihan pisang | Recall busuk 94.51% |
| Kesalahan identitas global eksternal | 1 / 820 | 4 / 820 non-busuk terpicu |

Grafik tersimpan di:

- [`model_analysis_native_cuda/model_analysis_report.png`](model_analysis_native_cuda/model_analysis_report.png)
- [`model_analysis_native_cuda/confusion_matrix.png`](model_analysis_native_cuda/confusion_matrix.png)
- [`external_analysis_native_cuda/confusion_matrix.png`](external_analysis_native_cuda/confusion_matrix.png)
- [`rotten_detector_analysis/confusion_matrix.png`](rotten_detector_analysis/confusion_matrix.png)
- [`rotten_detector_analysis/threshold_sweep.png`](rotten_detector_analysis/threshold_sweep.png)

## Kandidat empat-kelas

Kandidat tunggal 25 kelas (`unripe`, `ripe`, `overripe`, `rotten`) tidak dipromosikan karena test exact accuracy terbaiknya 83.85% dan BananaImageBD 90.49%, lebih rendah daripada arsitektur dua-model di atas. Artefaknya tetap disimpan sebagai eksperimen:

- [`native_cuda_candidate_4stage_tuned.tflite`](native_cuda_candidate_4stage_tuned.tflite)
- [`model_analysis_native_cuda_4stage_tuned/metrics.json`](model_analysis_native_cuda_4stage_tuned/metrics.json)

## Dataset tambahan

BananaImageBD diunduh ke `data/raw_external/bananaimagebd` dan dipakai sebagai external holdout. Label Rotten dari RipeNet dan Fruit Ripeness Unripe/Ripe/Rotten dipisahkan untuk detector; label yang tidak memiliki bukti visual cukup tidak dibuat-buat.

Sumber dan lisensi dicatat di [`data/raw_external/README.md`](../data/raw_external/README.md).

## Batasan yang disengaja

`Busuk` adalah safety signal visual, bukan jaminan keamanan pangan. Foto dengan pencahayaan buruk, buah terpotong, atau label dataset yang ambigu tetap perlu pertanyaan konfirmasi. Mode konsumen tetap meminta tiga pertanyaan online; Worker mengembalikan pertanyaan Groq/Cloudflare, dan memakai `rule_based_fallback` terukur bila kedua provider gagal agar tidak menghasilkan 503.
