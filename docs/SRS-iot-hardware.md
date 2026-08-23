# SRS - IoT Hardware (Unit Wadah, Sensor, Recommender & Pengingat)

> Requirement untuk unit IoT pada wadah buah, logika rekomendasi, pengingat, dan sinkronisasi lokal ke aplikasi Mode Petani. Spesifikasi UI ada di `SRS-mobile-app.md`; firmware dan BOM ada di `TECH-iot-firmware.md`.

---

## 1. Ruang Lingkup

Dokumen ini mencakup satu unit IoT per wadah/keranjang: DHT22, MQ-3, LED RGB, ESP32 WiFi AP lokal, histori sensor, analisis risiko, dan pengingat. Unit tidak membutuhkan internet atau gateway agar dapat melayani aplikasi.

## 2. Prinsip Desain

| Prinsip | Penjelasan |
|---|---|
| Scope per wadah | Setiap unit memantau kondisi lingkungan di dalam/sekitar satu wadah, bukan area kebun luas. |
| 1 unit = sensor + server | ESP32 membaca sensor dan menyediakan WiFi AP agar ponsel terhubung langsung. |
| AI sebagai sinyal bantu | Farmer ML V1 membaca tren sensor; rule engine tetap menjadi pengaman utama dan hasilnya transparan. |
| Multi-wadah independen | Banyak wadah dilayani dengan menambah unit IoT; setiap unit memiliki identitas WiFi sendiri. |

## 3. Requirement Fungsional - Sensor dan Unit

| ID | Requirement | Prioritas |
|---|---|---|
| FR-N01 | Unit membaca suhu dan kelembapan berkala menggunakan DHT22; interval default 15-30 menit. | Wajib |
| FR-N02 | Unit membaca level gas proxy hasil pematangan/fermentasi menggunakan MQ-3 pada interval yang sama. | Wajib |
| FR-N03 | Unit menampilkan status ringkas melalui LED RGB: hijau aman, kuning perhatian, merah segera diperiksa. | Wajib |
| FR-N04 | Unit menyimpan histori pembacaan secara lokal sampai berhasil disinkronkan ke aplikasi. | Wajib |
| FR-N05 | Setiap pembacaan memiliki timestamp atau sequence number. | Wajib |
| FR-N06 | Unit mendukung operasi baterai/power bank yang cukup untuk pengujian dan demo minimal 3-5 hari. | Wajib |
| FR-N07 | Casing aman diletakkan dekat buah, tidak mencemari, dan terlindung dari kelembapan langsung. | Wajib |

## 4. Requirement - WiFi AP dan Sinkronisasi

| ID | Requirement | Prioritas |
|---|---|---|
| FR-AP01 | Unit memancarkan WiFi AP dengan SSID unik per wadah, misalnya `RipenAI-Wadah-01`. | Wajib |
| FR-AP02 | Unit menyediakan endpoint untuk menarik data baru sejak timestamp sinkronisasi terakhir. | Wajib |
| FR-AP03 | Unit tidak memerlukan internet untuk melayani permintaan data dari aplikasi. | Wajib |
| FR-AP04 | Backup cloud boleh dilakukan jika internet tersedia, tetapi bukan syarat fungsi utama. | Opsional |
| FR-AP05 | Jangkauan WiFi cukup untuk jarak wajar beberapa meter di sekitar wadah. | Wajib |

## 5. Requirement - Logika Recommender Farmer ML V1

### 5.1 Rule engine sebagai safety path

| ID | Requirement | Prioritas |
|---|---|---|
| FR-REC01 | Sistem menghitung skor risiko berdasarkan tren suhu, kelembapan, dan gas MQ-3, bukan nilai sesaat saja. | Wajib |
| FR-REC02 | Sistem memetakan skor ke rekomendasi aksi yang jelas, seperti gunakan/jual, periksa, atau pisahkan buah. | Wajib |
| FR-REC03 | Threshold risiko dapat dikonfigurasi per jenis buah. | Wajib |
| FR-REC04 | Rule engine tetap menjadi pengaman utama; Farmer ML V1 hanya sinyal bantu dan tidak boleh menutupi hasil rule yang lebih berisiko. | Wajib |

### 5.2 Farmer ML V1 dan pembelajaran lokal

| ID | Requirement | Prioritas |
|---|---|---|
| FR-REC05 | Aplikasi memuat model TFLite Farmer ML V1 dengan input 105 fitur (window 32 x 3 sensor + one-hot buah). | Wajib |
| FR-REC06 | Model dilatih memakai trajektori sensor sintetis yang mencakup variasi normal, drift, noise, missing reading, stuck sensor, dan out-of-distribution stress. | Wajib |
| FR-REC07 | Aplikasi menampilkan risk score, confidence, status, dan estimasi jam tindakan dari model bila confidence memenuhi floor. | Wajib |
| FR-REC08 | Pengguna dapat memberi label eksplisit Aman, Perhatian, atau Urgent setelah memeriksa kondisi nyata buah. | Wajib |
| FR-REC09 | Label hanya memperbarui kalibrasi lokal per jenis buah dengan learning rate menurun dan batas bias; bobot TFLite tetap frozen. | Wajib |
| FR-REC10 | Model, rule engine, feedback, dan sumber analisis harus terlihat transparan di UI agar hasil tidak dipresentasikan sebagai kepastian. | Wajib |
| FR-REC11 | Dataset, konfigurasi fitur, metrik holdout/OOD, dan batasan MQ-3 harus diaudit di `TECH-farmer-ml-v1.md`. | Wajib |

## 6. Requirement - Pengingat

| ID | Requirement | Prioritas |
|---|---|---|
| FR-REM01 | Aplikasi mengirim pengingat lokal saat skor risiko melewati threshold. | Wajib |
| FR-REM02 | Aplikasi mengingatkan saat wadah belum disinkronkan, misalnya lebih dari 24 jam. | Disarankan |
| FR-REM03 | Pengingat menyertakan alasan singkat, misalnya gas meningkat cepat atau kelembapan tinggi. | Wajib |

## 7. Non-Fungsional dan Batasan

| ID | Requirement |
|---|---|
| NFR-IOT01 | Komunikasi unit IoT ke ponsel tidak memerlukan internet aktif. |
| NFR-IOT02 | BOM prototype berada di kisaran Rp150.000-220.000 jika memungkinkan. |
| NFR-IOT03 | Threshold dan konfigurasi model tersimpan sebagai asset/config terpisah, bukan hardcoded di UI. |
| NFR-IOT04 | MQ-3 diperlakukan sebagai gas proxy; angka tidak boleh diklaim sebagai ppm tanpa kalibrasi laboratorium. |
| NFR-IOT05 | Metrik sintetis tidak boleh dianggap sebagai akurasi lapangan; validasi dengan label nyata tetap wajib. |

## 8. Referensi Silang

- Model, dataset, evaluasi, dan kalibrasi: `TECH-farmer-ml-v1.md`
- Pinout dan firmware ESP32: `TECH-iot-firmware.md` dan `firmware/esp32_ripenai/README.md`
- Arsitektur dan alur data: `DESIGN-architecture.md`
- Dashboard dan pengingat: `SRS-mobile-app.md`
