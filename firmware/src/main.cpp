// ArogyaX sensor unit firmware — ESP32-S3 + AD8232 (ECG) + MAX30102 (contact PPG).
//
// Implements the wire format in contracts/ble.md and contracts/ppg.md exactly
// (both LOCKED — this file must match them, not the other way around). No IMU:
// the MPU-6050 is not in the BOM: see ble.md section 4 and ppg.md section 5 for
// why motion is inferred on the phone instead of sensed here.
//
// STATUS: written against the contracts, never compiled. No PlatformIO/ESP32
// toolchain is available in the environment this was written in. Before the
// first flash (ticket 013, after ticket 003 confirms the electrode wiring):
//   - Verify the PIN ASSIGNMENTS block below against the actual breadboard —
//     nothing in the repo locks these down; they are this file's own choice.
//   - Verify the MAX3010x library's setup()/check()/getFIFOIR()/nextSample()
//     signatures against whatever version PlatformIO resolves — this file was
//     written against the long-stable public API, not a pinned version.
//   - Verify BLECharacteristic::getValue() returns std::string on the
//     resolved arduino-esp32 core version (older/newer cores have returned
//     String in places) — one-line fix in ControlCallbacks::onWrite if not.
//   - Verify esp_timer_create_args_t's field set against the resolved
//     esp-idf version bundled with the core.
//
// Design note: sampling happens inside esp_timer callbacks (ESP_TIMER_TASK
// dispatch — a dedicated high-priority task, not a raw hardware ISR), not in
// loop(). That is deliberate: CLAUDE.md's own reason for a timer-driven
// sample rate is that delay()/loop()-paced sampling jitters, and jitter
// corrupts RR intervals, which *are* the AF signal. Doing the actual
// analogRead()/I2C work inside loop() would reintroduce exactly that jitter
// the moment a BLE notify() call takes a few ms, since it would be competing
// with the sampling for loop() time. The esp_timer task pre-empts loop() and
// is safe to call analogRead()/Wire from, unlike a true ISR.

#include <Arduino.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <cstring>
#include <string>
#include "esp_timer.h"
#include "esp_mac.h"
#include "MAX30105.h"

// ---------------------------------------------------------------------------
// PIN ASSIGNMENTS — not specified by any contract. VERIFY AGAINST THE ACTUAL
// BREADBOARD (ticket 003) before flashing; change freely, nothing downstream
// depends on the exact numbers.
// ---------------------------------------------------------------------------
constexpr int kEcgPin      = 4;   // AD8232 OUTPUT -> ADC1_CH3 on ESP32-S3
constexpr int kLoPlusPin   = 5;   // AD8232 LO+
constexpr int kLoMinusPin  = 6;   // AD8232 LO-
constexpr int kSdaPin      = 8;   // MAX30102 SDA
constexpr int kSclPin      = 9;   // MAX30102 SCL

// ---------------------------------------------------------------------------
// contracts/ble.md section 1 — GATT layout. Do not change without updating
// the contract and telling both firmware and app "out loud" (the doc's own
// words).
// ---------------------------------------------------------------------------
static const char* kServiceUuid     = "7a9c0100-5d2e-4b81-9f13-2c6e0a4d55e0";
static const char* kEcgCharUuid     = "7a9c0101-5d2e-4b81-9f13-2c6e0a4d55e0";
static const char* kStatusCharUuid  = "7a9c0102-5d2e-4b81-9f13-2c6e0a4d55e0";
static const char* kControlCharUuid = "7a9c0103-5d2e-4b81-9f13-2c6e0a4d55e0";
static const char* kPpgCharUuid     = "7a9c0104-5d2e-4b81-9f13-2c6e0a4d55e0"; // ppg.md section 3

// ---------------------------------------------------------------------------
// Frame shapes — contracts/ble.md section 3+4, contracts/ppg.md section 3.
// ---------------------------------------------------------------------------
constexpr int kEcgSamplesPerFrame = 25;   // 250 Hz / 25 = 10 Hz notify rate
constexpr int kEcgPacketBytes     = 56;   // 2 (seq) + 4 (t_ms) + 25*2 (samples)
constexpr int kPpgSamplesPerFrame = 10;   // 100 Hz / 10 = 10 Hz notify rate
constexpr int kPpgPacketBytes     = 46;   // 2 (seq) + 4 (t_ms) + 10*4 (IR counts)
constexpr int kStatusPacketBytes  = 4;

// ---------------------------------------------------------------------------
// MAX30102 configuration — contracts/ppg.md section 2.
// ledMode 2 = Red+IR (the chip has no IR-only acquisition mode); only the IR
// channel is ever read out of the FIFO and put on the wire, which is what
// actually halves the BLE payload the contract's rationale refers to.
// adcRange is this file's own choice (not contract-mandated) — 16384 for
// maximum dynamic-range headroom against a bright/dim finger; revisit during
// bring-up if perfusion index reads clipped or noisy.
// ---------------------------------------------------------------------------
constexpr uint8_t kPpgLedBrightness = 40;    // ~8 mA on a 0-255/~50 mA scale; ppg.md wants 6-12 mA
constexpr uint8_t kPpgSampleAverage = 1;     // no on-chip averaging - contract wants raw 100 Hz out
constexpr uint8_t kPpgLedMode       = 2;     // Red+IR; only IR is forwarded
constexpr int32_t kPpgSampleRateHz  = 100;
constexpr int32_t kPpgPulseWidthUs  = 411;   // 18-bit resolution, ppg.md section 2
constexpr int32_t kPpgAdcRange      = 16384;

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
MAX30105 g_ppgSensor;
bool g_ppgAvailable = false;

volatile bool g_streaming = false;
volatile uint32_t g_autoStopAtMs = 0; // 0 = no auto-stop pending

volatile bool g_loPlusOff = false;
volatile bool g_loMinusOff = false;

// ECG double buffer.
int16_t g_ecgSamples[2][kEcgSamplesPerFrame];
uint32_t g_ecgFrameT[2] = {0, 0};
uint16_t g_ecgFrameSeq[2] = {0, 0};
volatile uint8_t g_ecgWriteBuf = 0;
volatile uint8_t g_ecgWriteIdx = 0;
volatile uint8_t g_ecgReadyBuf = 0;
volatile bool g_ecgFrameReady = false;
uint16_t g_ecgSeqCounter = 0;
volatile uint16_t g_lastEcgSeqSent = 0;

// PPG double buffer.
int32_t g_ppgSamples[2][kPpgSamplesPerFrame];
uint32_t g_ppgFrameT[2] = {0, 0};
uint16_t g_ppgFrameSeq[2] = {0, 0};
volatile uint8_t g_ppgWriteBuf = 0;
volatile uint8_t g_ppgWriteIdx = 0;
volatile uint8_t g_ppgReadyBuf = 0;
volatile bool g_ppgFrameReady = false;
uint16_t g_ppgSeqCounter = 0;

bool g_deviceConnected = false;
uint32_t g_lastStatusMs = 0;

BLECharacteristic* g_ecgChar = nullptr;
BLECharacteristic* g_statusChar = nullptr;
BLECharacteristic* g_ppgChar = nullptr;
BLECharacteristic* g_controlChar = nullptr;
BLEServer* g_server = nullptr;

esp_timer_handle_t g_ecgTimer = nullptr;
esp_timer_handle_t g_ppgTimer = nullptr;

// ---------------------------------------------------------------------------
// Little-endian byte packing — contracts/ble.md section 2. Written as
// explicit shifts rather than a packed struct + memcpy so each line maps
// directly to one row of the contract's offset table, regardless of the
// host's actual endianness or any struct-padding surprise.
// ---------------------------------------------------------------------------
static inline void putU16LE(uint8_t* buf, size_t off, uint16_t v) {
  buf[off] = (uint8_t)(v & 0xFF);
  buf[off + 1] = (uint8_t)((v >> 8) & 0xFF);
}
static inline void putU32LE(uint8_t* buf, size_t off, uint32_t v) {
  buf[off] = (uint8_t)(v & 0xFF);
  buf[off + 1] = (uint8_t)((v >> 8) & 0xFF);
  buf[off + 2] = (uint8_t)((v >> 16) & 0xFF);
  buf[off + 3] = (uint8_t)((v >> 24) & 0xFF);
}
static inline void putI16LE(uint8_t* buf, size_t off, int16_t v) {
  putU16LE(buf, off, (uint16_t)v);
}
static inline void putI32LE(uint8_t* buf, size_t off, int32_t v) {
  putU32LE(buf, off, (uint32_t)v);
}

// ---------------------------------------------------------------------------
// Timer callbacks. ESP_TIMER_TASK dispatch (set below in setupTimers): these
// run on a dedicated high-priority task, not a hardware ISR, so calling
// analogRead()/Wire directly here is safe. Kept small on purpose — no BLE
// calls happen in here, only buffer writes; loop() drains the "ready" flags
// and does the (slower, less time-critical) BLE notify() calls.
// ---------------------------------------------------------------------------
void ecgTimerCallback(void* /*arg*/) {
  if (!g_streaming) return;

  int adc = analogRead(kEcgPin);
  int16_t sample = (int16_t)(adc - 2048); // contracts/ble.md section 3: ADC minus 2048

  // Read directly from hardware every tick - "the hardware already knows,
  // and it knows faster" (ble.md section 4).
  g_loPlusOff = digitalRead(kLoPlusPin) == HIGH;
  g_loMinusOff = digitalRead(kLoMinusPin) == HIGH;

  uint8_t buf = g_ecgWriteBuf;
  uint8_t idx = g_ecgWriteIdx;
  if (idx == 0) {
    g_ecgFrameT[buf] = millis(); // t_ms at the FIRST sample of this frame
  }
  g_ecgSamples[buf][idx] = sample;
  idx++;

  if (idx >= kEcgSamplesPerFrame) {
    g_ecgFrameSeq[buf] = g_ecgSeqCounter++;
    g_ecgReadyBuf = buf;
    g_ecgFrameReady = true;
    g_ecgWriteBuf = buf ^ 1; // swap to the other buffer while this one is sent
    idx = 0;
  }
  g_ecgWriteIdx = idx;
}

void ppgTimerCallback(void* /*arg*/) {
  if (!g_streaming || !g_ppgAvailable) return;

  g_ppgSensor.check();
  if (!g_ppgSensor.available()) return; // FIFO not ready this tick; catches up next tick

  int32_t ir = (int32_t)g_ppgSensor.getFIFOIR();
  g_ppgSensor.nextSample();

  uint8_t buf = g_ppgWriteBuf;
  uint8_t idx = g_ppgWriteIdx;
  if (idx == 0) {
    g_ppgFrameT[buf] = millis(); // same millis() clock as ECG - ppg.md section 4
  }
  g_ppgSamples[buf][idx] = ir;
  idx++;

  if (idx >= kPpgSamplesPerFrame) {
    g_ppgFrameSeq[buf] = g_ppgSeqCounter++;
    g_ppgReadyBuf = buf;
    g_ppgFrameReady = true;
    g_ppgWriteBuf = buf ^ 1;
    idx = 0;
  }
  g_ppgWriteIdx = idx;
}

// ---------------------------------------------------------------------------
// BLE callbacks
// ---------------------------------------------------------------------------
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* /*server*/) override {
    g_deviceConnected = true;
  }
  void onDisconnect(BLEServer* server) override {
    g_deviceConnected = false;
    // Without this the device goes permanently invisible after the first
    // disconnect - a well-known ESP32 BLE gotcha, not optional here.
    server->startAdvertising();
  }
};

class ControlCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    // contracts/ble.md section 5. std::string is the traditional getValue()
    // return type for this library; if the resolved core version returns
    // Arduino String instead, this is the one line to change.
    std::string v = c->getValue();
    if (v.length() < 2) return;

    uint8_t opcode = (uint8_t)v[0];
    uint8_t arg = (uint8_t)v[1];
    switch (opcode) {
      case 0x01: // start
        g_streaming = true;
        g_autoStopAtMs = 0;
        break;
      case 0x02: // stop
        g_streaming = false;
        g_autoStopAtMs = 0;
        break;
      case 0x03: // start for `arg` seconds, then auto-stop
        g_streaming = true;
        g_autoStopAtMs = millis() + (uint32_t)arg * 1000UL;
        break;
      default:
        break; // unknown opcode - ignore, do not crash on a malformed write
    }
  }
};

// ---------------------------------------------------------------------------
// Setup helpers
// ---------------------------------------------------------------------------
void setupBle() {
  uint8_t mac[6] = {0};
  esp_read_mac(mac, ESP_MAC_BT);
  char deviceName[24];
  snprintf(deviceName, sizeof(deviceName), "ArogyaX-%02X%02X", mac[4], mac[5]);

  BLEDevice::init(deviceName);
  g_server = BLEDevice::createServer();
  g_server->setCallbacks(new ServerCallbacks());

  BLEService* service = g_server->createService(kServiceUuid);

  g_ecgChar = service->createCharacteristic(
      kEcgCharUuid, BLECharacteristic::PROPERTY_NOTIFY);
  g_ecgChar->addDescriptor(new BLE2902());

  g_statusChar = service->createCharacteristic(
      kStatusCharUuid, BLECharacteristic::PROPERTY_NOTIFY);
  g_statusChar->addDescriptor(new BLE2902());

  g_ppgChar = service->createCharacteristic(
      kPpgCharUuid, BLECharacteristic::PROPERTY_NOTIFY);
  g_ppgChar->addDescriptor(new BLE2902());

  g_controlChar = service->createCharacteristic(
      kControlCharUuid, BLECharacteristic::PROPERTY_WRITE);
  g_controlChar->setCallbacks(new ControlCallbacks());

  service->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(kServiceUuid);
  advertising->setMinPreferred(0x10); // ~20 ms - ble.md section 6
  advertising->setMaxPreferred(0x20); // ~40 ms
  BLEDevice::startAdvertising();
}

void setupTimers() {
  const esp_timer_create_args_t ecgArgs = {
      .callback = &ecgTimerCallback,
      .arg = nullptr,
      .dispatch_method = ESP_TIMER_TASK,
      .name = "ecg250hz",
      .skip_unhandled_events = true,
  };
  esp_timer_create(&ecgArgs, &g_ecgTimer);
  esp_timer_start_periodic(g_ecgTimer, 4000); // 4000 us = 250 Hz

  const esp_timer_create_args_t ppgArgs = {
      .callback = &ppgTimerCallback,
      .arg = nullptr,
      .dispatch_method = ESP_TIMER_TASK,
      .name = "ppg100hz",
      .skip_unhandled_events = true,
  };
  esp_timer_create(&ppgArgs, &g_ppgTimer);
  if (g_ppgAvailable) {
    esp_timer_start_periodic(g_ppgTimer, 10000); // 10000 us = 100 Hz
  }
}

// ---------------------------------------------------------------------------
// loop() work: consume whatever the timers produced, at loop()'s own pace.
// ---------------------------------------------------------------------------
void sendReadyFrames() {
  if (g_ecgFrameReady) {
    uint8_t b = g_ecgReadyBuf;
    int16_t local[kEcgSamplesPerFrame];
    memcpy(local, g_ecgSamples[b], sizeof(local));
    uint16_t seq = g_ecgFrameSeq[b];
    uint32_t t = g_ecgFrameT[b];
    g_ecgFrameReady = false; // clear before the (slower) notify() call below

    uint8_t packet[kEcgPacketBytes];
    putU16LE(packet, 0, seq);
    putU32LE(packet, 2, t);
    for (int i = 0; i < kEcgSamplesPerFrame; i++) {
      putI16LE(packet, 6 + i * 2, local[i]);
    }
    g_lastEcgSeqSent = seq;

    if (g_deviceConnected) {
      g_ecgChar->setValue(packet, sizeof(packet));
      g_ecgChar->notify();
    }
  }

  if (g_ppgFrameReady) {
    uint8_t b = g_ppgReadyBuf;
    int32_t local[kPpgSamplesPerFrame];
    memcpy(local, g_ppgSamples[b], sizeof(local));
    uint16_t seq = g_ppgFrameSeq[b];
    uint32_t t = g_ppgFrameT[b];
    g_ppgFrameReady = false;

    uint8_t packet[kPpgPacketBytes];
    putU16LE(packet, 0, seq);
    putU32LE(packet, 2, t);
    for (int i = 0; i < kPpgSamplesPerFrame; i++) {
      putI32LE(packet, 6 + i * 4, local[i]);
    }

    if (g_deviceConnected) {
      g_ppgChar->setValue(packet, sizeof(packet));
      g_ppgChar->notify();
    }
  }
}

void maybeSendStatusFrame() {
  uint32_t now = millis();
  if (now - g_lastStatusMs < 1000) return; // 1 Hz - ble.md section 4
  g_lastStatusMs = now;

  uint8_t flags = 0;
  if (g_loPlusOff) flags |= 0x01;
  if (g_loMinusOff) flags |= 0x02;
  // bit2 reserved - the IMU-ready bit this used to be is deliberately unset,
  // not repurposed, so old firmware built against an earlier contract
  // revision fails visibly rather than silently setting a bit nothing reads.
  if (g_streaming) flags |= 0x08;

  uint8_t packet[kStatusPacketBytes];
  packet[0] = flags;
  packet[1] = 255; // battery: no fuel gauge in this BOM revision - 255 = unknown
  putU16LE(packet, 2, g_lastEcgSeqSent);

  if (g_deviceConnected) {
    g_statusChar->setValue(packet, sizeof(packet));
    g_statusChar->notify();
  }
}

void handleAutoStop() {
  if (g_streaming && g_autoStopAtMs != 0 &&
      (int32_t)(millis() - g_autoStopAtMs) >= 0) {
    g_streaming = false;
    g_autoStopAtMs = 0;
  }
}

// ---------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);

  pinMode(kLoPlusPin, INPUT);
  pinMode(kLoMinusPin, INPUT);

  analogReadResolution(12); // 0-4095, matches the "ADC value minus 2048" contract
  analogSetPinAttenuation(kEcgPin, ADC_11db); // ~0-3.3 V range for the AD8232's biased swing

  Wire.begin(kSdaPin, kSclPin);
  g_ppgAvailable = g_ppgSensor.begin(Wire, I2C_SPEED_FAST);
  if (g_ppgAvailable) {
    g_ppgSensor.setup(kPpgLedBrightness, kPpgSampleAverage, kPpgLedMode,
                       kPpgSampleRateHz, kPpgPulseWidthUs, kPpgAdcRange);
  } else {
    // No MAX30102 found. ECG-only capture still works - PPG fusion and
    // PPG-derived motion inference are simply unavailable, same as any
    // other capture where "a simultaneous contact-PPG capture exists" is
    // false (ble.md section 4, ppg.md section 6).
    Serial.println("MAX30102 not found - continuing ECG-only");
  }

  setupBle();
  setupTimers(); // both timers run continuously; g_streaming gates real work

  // Firmware boots not streaming - the app explicitly starts it (ble.md
  // section 5), so battery isn't spent sampling before anyone asked for it.
  g_streaming = false;
}

void loop() {
  handleAutoStop();
  sendReadyFrames();
  maybeSendStatusFrame();
}
