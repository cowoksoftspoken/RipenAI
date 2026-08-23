# RipenAI ESP32 firmware

Firmware asli untuk satu wadah: ESP32 DevKit + DHT22 + MQ-3 + LED RGB.
Unit membuat WiFi AP lokal dan menyediakan kontrak HTTP yang dikonsumsi Android.

## Pin default

| Komponen | GPIO |
|---|---:|
| DHT22 data | 4 |
| MQ-3 analog | 34 |
| RGB merah | 25 |
| RGB hijau | 26 |
| RGB biru | 27 |

Gunakan resistor LED yang sesuai, common-cathode untuk konfigurasi default, dan
ground bersama. MQ-3 diberi tegangan sesuai modul yang digunakan; jangan
memasukkan tegangan ADC di atas batas ESP32.

## Build dan flash

1. Arduino IDE/PlatformIO: pilih board `ESP32 Dev Module`.
2. Install library `DHT sensor library` dan dependensinya `Adafruit Unified Sensor`.
3. Buka `esp32_ripenai.ino`, pilih port ESP32, lalu upload.
4. Setelah boot, sambungkan ponsel ke `RipenAI-Wadah-01` dengan password `ripenai01`.

Endpoint yang tersedia:

- `GET http://192.168.4.1/ping`
- `GET http://192.168.4.1/status`
- `GET http://192.168.4.1/data?since=0`
- `GET http://192.168.4.1/config?interval_min=15`
- `POST http://192.168.4.1/led` dengan body `{"ripeness":"urgent"}`

Firmware menyimpan maksimal 192 pembacaan CSV di LittleFS, memakai timestamp
monotonik berbasis boot-id + uptime karena RTC/NTP belum dipasang. Ini cukup
untuk sync lokal; cloud backup nanti membutuhkan wall-clock dari RTC atau NTP.

MQ-3 membutuhkan pemanasan dan kalibrasi nyata. Nilai `gas_level` adalah proxy
ADC 0–1000, bukan ppm dan bukan pengukuran ethylene. Jangan mengubahnya menjadi
klaim ppm tanpa kurva kalibrasi terhadap batch buah nyata.
