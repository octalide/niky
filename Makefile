# niky build orchestration.
#
# Repo layout:
#   ./                firmware (PlatformIO, Arduino-ESP32) — platformio.ini, src/
#   ./android         companion Android app (Gradle + Kotlin)
#
# Tooling assumptions (set up once per machine; see README):
#   - PlatformIO at  ~/.local/bin/pio
#   - JDK 21 at      /usr/lib/jvm/java-21-openjdk
#   - Android SDK at /opt/android-sdk (user-owned)
#   - User in `uucp` group (membership only inherits in fresh shells; the
#     firmware targets use `sg uucp -c ...` to be safe inside this Makefile).
#
# All paths are absolute so targets work regardless of `make -C` cwd.

PIO          ?= $(HOME)/.local/bin/pio
JAVA_HOME    ?= /usr/lib/jvm/java-21-openjdk
ANDROID_HOME ?= /opt/android-sdk
ADB          ?= adb

PORT         ?= /dev/ttyACM0
ANDROID_DIR  := $(CURDIR)/android
APK          := $(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk

GRADLE_ENV   := JAVA_HOME=$(JAVA_HOME) ANDROID_HOME=$(ANDROID_HOME)

.PHONY: help fw flash monitor app install run clean fw-clean app-clean

help:
	@echo "niky make targets:"
	@echo "  make fw        - build firmware"
	@echo "  make flash     - upload firmware to PORT (default $(PORT))"
	@echo "  make monitor   - open serial monitor on PORT"
	@echo "  make app       - build Android debug APK"
	@echo "  make install   - install APK on adb-connected phone"
	@echo "  make run       - install APK and launch the activity"
	@echo "  make clean     - clean both subprojects"

fw:
	$(PIO) run

flash:
	sg uucp -c '$(PIO) run -t upload --upload-port $(PORT)'

monitor:
	sg uucp -c '$(PIO) device monitor -p $(PORT) -b 115200'

app:
	cd $(ANDROID_DIR) && $(GRADLE_ENV) ./gradlew assembleDebug

install: app
	$(ADB) install -r $(APK)

run: install
	$(ADB) shell am force-stop com.octalide.niky
	$(ADB) shell am start -n com.octalide.niky/.MainActivity

fw-clean:
	$(PIO) run -t clean

app-clean:
	cd $(ANDROID_DIR) && $(GRADLE_ENV) ./gradlew clean

clean: fw-clean app-clean
