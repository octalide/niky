#include <Arduino.h>
#include <NimBLEDevice.h>

#include <cstdio>
#include <cstring>

namespace {

constexpr uint8_t  PIN_CAM_TX = 43;     // Xiao D6 -> Nikon 10-pin pin 1 (NMEA into camera RX)
constexpr uint8_t  PIN_CAM_RX = 44;     // Xiao D7, unused for now
constexpr uint32_t CAM_BAUD   = 4800;   // Nikon NMEA: 4800 8-N-1
constexpr uint8_t  PIN_LED    = LED_BUILTIN;  // GPIO21 on Xiao S3, active-LOW
constexpr const char* DEVICE_NAME = "";  // anonymous; identified by service UUID

const NimBLEUUID NUS_SERVICE("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
const NimBLEUUID NUS_RX_UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");  // phone writes NMEA bytes here
const NimBLEUUID NUS_TX_UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");  // niky -> phone status notifies

HardwareSerial CamSerial(1);
NimBLECharacteristic* g_tx_char  = nullptr;
volatile bool         g_connected = false;

void ledOn()  { digitalWrite(PIN_LED, LOW);  }
void ledOff() { digitalWrite(PIN_LED, HIGH); }

void writeCam(const uint8_t* data, size_t n) {
    CamSerial.write(data, n);
    Serial.write(data, n);   // mirror to USB CDC for visibility
}

class ServerCb : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer*, NimBLEConnInfo&) override {
        g_connected = true;
        ledOn();
        Serial.println("[ble] connected");
    }
    void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int reason) override {
        g_connected = false;
        ledOff();
        Serial.printf("[ble] disconnected (reason=%d), re-advertising\n", reason);
        NimBLEDevice::startAdvertising();
    }
};

class RxCb : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
        const std::string& v = c->getValue();
        if (v.empty()) return;
        writeCam(reinterpret_cast<const uint8_t*>(v.data()), v.size());
    }
};

#if NIKY_SELFTEST
// Stationary canned fix used when no BLE peer is connected, so the hardware path
// can be exercised end-to-end before the Android companion exists.
// Coordinates: 0,0 placeholder. Date/time advance with millis() to look fresh.
uint8_t nmeaChecksum(const char* body) {
    uint8_t cs = 0;
    for (const char* p = body; *p; ++p) cs ^= static_cast<uint8_t>(*p);
    return cs;
}

void emitSelfTestSentence() {
    // Build hhmmss.ss from millis() so the camera sees a moving timestamp.
    const uint32_t ms = millis();
    const uint32_t s  = ms / 1000;
    const uint8_t  hh = static_cast<uint8_t>((s / 3600) % 24);
    const uint8_t  mm = static_cast<uint8_t>((s / 60)   % 60);
    const uint8_t  ss = static_cast<uint8_t>( s         % 60);
    const uint8_t  cs = static_cast<uint8_t>((ms / 10)  % 100);

    char tbuf[12];
    std::snprintf(tbuf, sizeof(tbuf), "%02u%02u%02u.%02u", hh, mm, ss, cs);

    // GGA: time, lat, lon, fixqual=1, sats=08, hdop=1.0, alt=0.0M, geoid=0.0M
    char gga_body[128];
    std::snprintf(gga_body, sizeof(gga_body),
                  "GPGGA,%s,0000.0000,N,00000.0000,E,1,08,1.0,0.0,M,0.0,M,,",
                  tbuf);
    char gga[160];
    std::snprintf(gga, sizeof(gga), "$%s*%02X\r\n", gga_body, nmeaChecksum(gga_body));

    // RMC: time, A=valid, lat, lon, sog, cog, ddmmyy, magvar, mode=A
    char rmc_body[160];
    std::snprintf(rmc_body, sizeof(rmc_body),
                  "GPRMC,%s,A,0000.0000,N,00000.0000,E,0.0,0.0,010125,,,A",
                  tbuf);
    char rmc[200];
    std::snprintf(rmc, sizeof(rmc), "$%s*%02X\r\n", rmc_body, nmeaChecksum(rmc_body));

    writeCam(reinterpret_cast<const uint8_t*>(gga), std::strlen(gga));
    writeCam(reinterpret_cast<const uint8_t*>(rmc), std::strlen(rmc));
}
#endif // NIKY_SELFTEST

}  // namespace

void setup() {
    pinMode(PIN_LED, OUTPUT);
    ledOff();

    Serial.begin(115200);
    delay(200);
    Serial.println("\n[niky] boot");

    CamSerial.begin(CAM_BAUD, SERIAL_8N1, PIN_CAM_RX, PIN_CAM_TX);
    Serial.printf("[uart] UART1 %u 8N1, TX=GPIO%u\n", CAM_BAUD, PIN_CAM_TX);

    NimBLEDevice::init(DEVICE_NAME);
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);

    NimBLEServer* server = NimBLEDevice::createServer();
    server->setCallbacks(new ServerCb());

    NimBLEService* nus = server->createService(NUS_SERVICE);
    NimBLECharacteristic* rx = nus->createCharacteristic(
        NUS_RX_UUID,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    rx->setCallbacks(new RxCb());

    g_tx_char = nus->createCharacteristic(
        NUS_TX_UUID,
        NIMBLE_PROPERTY::NOTIFY);

    nus->start();

    // Build the advertisement explicitly so NimBLE's defaults can't sneak the
    // GAP device name into the packet or scan response. Companion app matches
    // on the NUS service UUID, so a name isn't needed.
    NimBLEAdvertisementData adv_data;
    adv_data.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
    adv_data.addServiceUUID(NUS_SERVICE);

    NimBLEAdvertisementData scan_resp;  // empty: no name, no extra data

    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->setAdvertisementData(adv_data);
    adv->setScanResponseData(scan_resp);
    adv->enableScanResponse(false);   // suppress active-scan name reveal
    adv->start();
    Serial.println("[ble] advertising (unnamed) with NUS service UUID");
}

void loop() {
    static uint32_t last_blink     = 0;
    static uint32_t last_selftest  = 0;
    const uint32_t now = millis();

    if (!g_connected && now - last_blink >= 500) {
        last_blink = now;
        digitalWrite(PIN_LED, !digitalRead(PIN_LED));
    }

#if NIKY_SELFTEST
    if (!g_connected && now - last_selftest >= 1000) {
        last_selftest = now;
        emitSelfTestSentence();
    }
#endif

    delay(10);
}
