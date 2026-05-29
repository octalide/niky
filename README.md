# niky

DIY replacement for the discontinued Nikon GP-1.

A XIAO ESP32-S3 plugs into the Nikon 10-pin port and re-emits GPS fixes
received from a phone over BLE as NMEA-0183 on UART1 at 4800 8N1. Tested on
a D800e.

## Wiring

| Nikon 10-pin | Function     | XIAO ESP32-S3    |
|--------------|--------------|------------------|
| pin 1        | NMEA in (RX) | D6 / GPIO43 (TX) |
| pin 7        | GND          | GND              |

Power over USB-C.

## Build

Requires PlatformIO, JDK 21, and the Android SDK with API 36.

```sh
make fw        # build firmware
make flash     # upload to PORT=/dev/ttyACM0
make monitor   # serial monitor at 115200
make app       # build android debug APK
make install   # install on adb-connected phone
make run       # install + launch
make clean
```

## BLE

Standard Nordic UART Service:

| What        | UUID                                 |
|-------------|--------------------------------------|
| Service     | 6E400001-B5A3-F393-E0A9-E50E24DCCA9E |
| RX (write)  | 6E400002-B5A3-F393-E0A9-E50E24DCCA9E |
| TX (notify) | 6E400003-B5A3-F393-E0A9-E50E24DCCA9E |

Phone writes CRLF-terminated NMEA to RX; firmware forwards bytes verbatim to
UART1.
