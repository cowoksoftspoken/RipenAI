/*
 * RipenAI farmer-mode IoT firmware.
 *
 * Target: ESP32 DevKit + DHT22 + MQ-3 + common-cathode RGB LED.
 * The unit is an offline WiFi AP and HTTP sensor server. It stores a bounded
 * CSV history in LittleFS so Android can retry /data?since=... safely.
 *
 * Important: MQ-3 is a broad VOC/alcohol proxy, not a selective ethylene
 * sensor. Calibrate it with real fruit batches before using thresholds.
 */

#include <Arduino.h>
#include <DHT.h>
#include <LittleFS.h>
#include <Preferences.h>
#include <WebServer.h>
#include <WiFi.h>

constexpr uint8_t DHT_PIN = 4;
constexpr uint8_t MQ3_PIN = 34;  // ADC1 pin; safe while WiFi is active.
constexpr uint8_t RGB_RED_PIN = 25;
constexpr uint8_t RGB_GREEN_PIN = 26;
constexpr uint8_t RGB_BLUE_PIN = 27;
constexpr bool RGB_COMMON_ANODE = false;

constexpr uint32_t SAMPLE_INTERVAL_MS = 15UL * 60UL * 1000UL;
constexpr uint32_t MQ3_WARMUP_MS = 60UL * 1000UL;
constexpr size_t HISTORY_CAPACITY = 192;  // 48 hours at a 15-minute interval.
constexpr char AP_SSID[] = "RipenAI-Wadah-01";
constexpr char AP_PASSWORD[] = "ripenai01";
constexpr char CONTAINER_ID[] = "Wadah-01";
constexpr char HISTORY_FILE[] = "/readings.csv";
constexpr char FIRMWARE_VERSION[] = "farmer-v1.0.0";

struct SensorReading {
  uint64_t timestamp;
  float temperature;
  float humidity;
  float gas;
};

DHT dht(DHT_PIN, DHT22);
WebServer server(80);
Preferences preferences;
SensorReading history[HISTORY_CAPACITY];
size_t historyCount = 0;
uint32_t bootId = 1;
uint32_t lastSampleMillis = 0;
uint32_t mq3WarmupUntil = 0;
uint32_t sampleIntervalMs = SAMPLE_INTERVAL_MS;

String uint64String(uint64_t value) {
  char buffer[24];
  snprintf(buffer, sizeof(buffer), "%llu", static_cast<unsigned long long>(value));
  return String(buffer);
}

void addCorsHeaders() {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("Cache-Control", "no-store");
}

void setRgb(uint8_t red, uint8_t green, uint8_t blue) {
  if (RGB_COMMON_ANODE) {
    red = 255 - red;
    green = 255 - green;
    blue = 255 - blue;
  }
  analogWrite(RGB_RED_PIN, red);
  analogWrite(RGB_GREEN_PIN, green);
  analogWrite(RGB_BLUE_PIN, blue);
}

void setStatusLed(float risk) {
  if (risk < 0.4f) setRgb(0, 220, 70);
  else if (risk < 0.7f) setRgb(245, 170, 0);
  else setRgb(230, 30, 35);
}

float clamp01(float value) {
  return constrain(value, 0.0f, 1.0f);
}

float computeRiskScore() {
  if (historyCount == 0) return 0.0f;
  const size_t start = historyCount > 24 ? historyCount - 24 : 0;
  const SensorReading &first = history[start];
  const SensorReading &last = history[historyCount - 1];
  float humidityAverage = 0.0f;
  float temperatureAverage = 0.0f;
  for (size_t index = start; index < historyCount; ++index) {
    humidityAverage += history[index].humidity;
    temperatureAverage += history[index].temperature;
  }
  const float count = static_cast<float>(historyCount - start);
  humidityAverage /= count;
  temperatureAverage /= count;
  const float elapsedHours = max(1.0f / 60.0f, static_cast<float>(last.timestamp - first.timestamp) / 3600.0f);
  const float gasRate = max(0.0f, (last.gas - first.gas) / elapsedHours);

  float gasScore = gasRate < 5.0f ? 0.0f : (gasRate < 15.0f ? 0.30f : 0.60f);
  float humidityScore = humidityAverage < 60.0f ? 0.0f : (humidityAverage < 80.0f ? 0.20f : 0.40f);
  float temperatureScore = temperatureAverage < 24.0f ? 0.0f : (temperatureAverage < 30.0f ? 0.05f : 0.12f);
  return clamp01(gasScore + humidityScore + temperatureScore);
}

String recommendationFor(float risk) {
  if (risk < 0.4f) return "Kondisi aman, cek kembali besok";
  if (risk < 0.7f) return "Perhatian: rencanakan digunakan atau dijual dalam 2 hari";
  return "Segera gunakan atau jual, lalu periksa buah secara visual";
}

void rewriteHistoryFile() {
  File file = LittleFS.open(HISTORY_FILE, "w");
  if (!file) return;
  for (size_t index = 0; index < historyCount; ++index) {
    file.printf("%llu,%.3f,%.3f,%.3f\n",
                static_cast<unsigned long long>(history[index].timestamp),
                history[index].temperature, history[index].humidity, history[index].gas);
  }
  file.close();
}

void appendReading(const SensorReading &reading) {
  if (historyCount == HISTORY_CAPACITY) {
    memmove(history, history + 1, sizeof(SensorReading) * (HISTORY_CAPACITY - 1));
    historyCount = HISTORY_CAPACITY - 1;
    rewriteHistoryFile();
  }
  history[historyCount++] = reading;
  File file = LittleFS.open(HISTORY_FILE, "a");
  if (file) {
    file.printf("%llu,%.3f,%.3f,%.3f\n",
                static_cast<unsigned long long>(reading.timestamp),
                reading.temperature, reading.humidity, reading.gas);
    file.close();
  }
}

void loadHistory() {
  File file = LittleFS.open(HISTORY_FILE, "r");
  if (!file) return;
  while (file.available()) {
    String line = file.readStringUntil('\n');
    unsigned long long timestamp = 0;
    float temperature = 0.0f;
    float humidity = 0.0f;
    float gas = 0.0f;
    if (sscanf(line.c_str(), "%llu,%f,%f,%f", &timestamp, &temperature, &humidity, &gas) == 4) {
      SensorReading reading{static_cast<uint64_t>(timestamp), temperature, humidity, gas};
      if (historyCount < HISTORY_CAPACITY) history[historyCount++] = reading;
      else {
        memmove(history, history + 1, sizeof(SensorReading) * (HISTORY_CAPACITY - 1));
        history[HISTORY_CAPACITY - 1] = reading;
      }
    }
  }
  file.close();
}

uint64_t nextTimestamp() {
  // No RTC is required for local sync. A persisted boot namespace plus uptime
  // stays monotonic across reboots and is accepted as a sequence timestamp by
  // the Android client. Add an NTP/RTC layer later if wall-clock timestamps are
  // needed for cloud backup.
  return static_cast<uint64_t>(bootId) * 1000000ULL + millis() / 1000ULL;
}

bool readAndStoreSensor() {
  if (millis() < mq3WarmupUntil) return false;
  const float temperature = dht.readTemperature();
  const float humidity = dht.readHumidity();
  if (isnan(temperature) || isnan(humidity)) return false;
  const float gas = static_cast<float>(analogRead(MQ3_PIN)) * 1000.0f / 4095.0f;
  appendReading(SensorReading{nextTimestamp(), temperature, humidity, gas});
  setStatusLed(computeRiskScore());
  return true;
}

String statusJson() {
  addCorsHeaders();
  if (historyCount == 0) {
    return "{\"wadah_id\":\"" + String(CONTAINER_ID) + "\",\"ts\":0,\"temp\":null,\"hum\":null,\"gas_level\":null,\"risk_score\":0,\"recommendation\":\"Sensor sedang pemanasan atau belum valid\"}";
  }
  const SensorReading &latest = history[historyCount - 1];
  const float risk = computeRiskScore();
  String body = "{\"wadah_id\":\"" + String(CONTAINER_ID) + "\",\"ts\":" + uint64String(latest.timestamp);
  body += ",\"temp\":" + String(latest.temperature, 2);
  body += ",\"hum\":" + String(latest.humidity, 2);
  body += ",\"gas_level\":" + String(latest.gas, 2);
  body += ",\"risk_score\":" + String(risk, 3);
  body += ",\"recommendation\":\"" + recommendationFor(risk) + "\"}";
  return body;
}

void handlePing() {
  addCorsHeaders();
  server.send(200, "application/json", "{\"ok\":true,\"wadah_id\":\"" + String(CONTAINER_ID) + "\",\"firmware\":\"" + String(FIRMWARE_VERSION) + "\"}");
}

void handleStatus() {
  server.send(200, "application/json", statusJson());
}

void handleData() {
  const uint64_t since = server.hasArg("since") ? strtoull(server.arg("since").c_str(), nullptr, 10) : 0ULL;
  String body = "{\"data\":[";
  bool first = true;
  uint64_t lastTimestamp = since;
  for (size_t index = 0; index < historyCount; ++index) {
    const SensorReading &reading = history[index];
    if (reading.timestamp <= since) continue;
    if (!first) body += ",";
    first = false;
    body += "{\"ts\":" + uint64String(reading.timestamp);
    body += ",\"temp\":" + String(reading.temperature, 2);
    body += ",\"hum\":" + String(reading.humidity, 2);
    body += ",\"gas_level\":" + String(reading.gas, 2) + "}";
    lastTimestamp = reading.timestamp;
  }
  body += "],\"last_ts\":" + uint64String(lastTimestamp) + "}";
  addCorsHeaders();
  server.send(200, "application/json", body);
}

void handleLed() {
  const String payload = server.arg("plain");
  if (payload.indexOf("urgent") >= 0 || payload.indexOf("rotten") >= 0) setRgb(230, 30, 35);
  else if (payload.indexOf("attention") >= 0 || payload.indexOf("nearly") >= 0) setRgb(245, 170, 0);
  else setRgb(0, 220, 70);
  addCorsHeaders();
  server.send(200, "application/json", "{\"ok\":true}");
}

void handleConfig() {
  if (server.hasArg("interval_min")) {
    const uint32_t minutes = constrain(server.arg("interval_min").toInt(), 1, 120);
    sampleIntervalMs = minutes * 60UL * 1000UL;
  }
  addCorsHeaders();
  server.send(200, "application/json", "{\"interval_min\":" + String(sampleIntervalMs / 60000UL) + ",\"history_capacity\":" + String(HISTORY_CAPACITY) + "}");
}

void setup() {
  Serial.begin(115200);
  pinMode(MQ3_PIN, INPUT);
  pinMode(RGB_RED_PIN, OUTPUT);
  pinMode(RGB_GREEN_PIN, OUTPUT);
  pinMode(RGB_BLUE_PIN, OUTPUT);
  setRgb(0, 0, 0);
  dht.begin();

  preferences.begin("ripenai", false);
  bootId = preferences.getULong("boot_id", 0) + 1;
  preferences.putULong("boot_id", bootId);
  preferences.end();

  if (!LittleFS.begin(true)) {
    Serial.println("LittleFS gagal di-mount; sensor tetap berjalan tanpa histori flash.");
  } else {
    loadHistory();
  }

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASSWORD);
  Serial.print("AP aktif: ");
  Serial.println(WiFi.softAPIP());

  server.on("/ping", HTTP_GET, handlePing);
  server.on("/status", HTTP_GET, handleStatus);
  server.on("/data", HTTP_GET, handleData);
  server.on("/led", HTTP_POST, handleLed);
  server.on("/config", HTTP_GET, handleConfig);
  server.onNotFound([]() { server.send(404, "application/json", "{\"error\":\"not_found\"}"); });
  server.begin();
  mq3WarmupUntil = millis() + MQ3_WARMUP_MS;
  lastSampleMillis = millis();
  setRgb(0, 0, 120);  // blue while MQ-3 warms up.
}

void loop() {
  server.handleClient();
  const uint32_t now = millis();
  if (now - lastSampleMillis >= sampleIntervalMs) {
    lastSampleMillis = now;
    readAndStoreSensor();
  }
  delay(2);
}
