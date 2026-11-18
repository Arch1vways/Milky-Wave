# OriginWave: сборка APK.
#   make            - собрать debug APK в dist/
#   make help       - список целей
# Переменные:  GRADLE=/path/to/gradle  ADB=adb  VERSION=1.0

GRADLE  ?= $(shell if [ -x ./gradlew ]; then echo ./gradlew; else echo gradle; fi)
ADB     ?= adb
VERSION ?= $(shell sed -n "s/.*versionName '\(.*\)'.*/\1/p" app/build.gradle)

# Android SDK: если ANDROID_HOME не задан (например, `wsl -- make apk` без .bashrc), а local.properties
# указывает на Windows-SDK (C:\android-sdk), берём Linux-SDK из домашней папки WSL.
ifeq ($(strip $(ANDROID_HOME)$(ANDROID_SDK_ROOT)),)
  ifneq ($(wildcard $(HOME)/android-sdk/platforms),)
    export ANDROID_HOME := $(HOME)/android-sdk
  else ifneq ($(wildcard $(HOME)/Android/Sdk/platforms),)
    export ANDROID_HOME := $(HOME)/Android/Sdk
  endif
endif

DIST      := dist
APK_SRC   := app/build/outputs/apk/debug/app-debug.apk
APK       := $(DIST)/MilkyWave-$(VERSION).apk
SRC_ZIP   := $(DIST)/MilkyWave-$(VERSION)-source.zip
JAVA_SRC  := $(shell find app/src -type f 2>/dev/null)
# Android Gradle Plugin can crash in mergeDebugResources on Windows when a parent directory contains
# non-ASCII characters. Build a disposable copy below an ASCII-only system temp directory.
ASCII_TMP  := $(shell if command -v cygpath >/dev/null 2>&1 && [ -n "$$TEMP" ]; then cygpath -u "$$TEMP"; else echo "$${TMPDIR:-/tmp}"; fi)
STAGE      := $(ASCII_TMP)/originwave-gradle-stage

.PHONY: all apk source install clean help

all: apk

help:
	@echo "make apk       - debug APK -> $(APK) (нужны JDK 17, Android SDK 35, Gradle 8.9+)"
	@echo "make source    - архив исходников -> $(SRC_ZIP)"
	@echo "make install   - установить APK через adb"
	@echo "make clean     - удалить dist/ и build/"

# apk всегда запускает Gradle (он сам пропускает неизменившееся) и заново копирует APK в dist/.
# Раньше цель зависела от файла dist/*.apk: если он уже был новее исходников (или версия
# не менялась), make писал "Nothing to be done for 'apk'" и ничего не собирал.
apk:
	@command -v $(firstword $(GRADLE)) >/dev/null 2>&1 || { echo "Gradle не найден. Установите Gradle 8.9+ или добавьте gradlew (gradle wrapper)."; exit 1; }
	@[ -n "$$ANDROID_HOME$$ANDROID_SDK_ROOT" ] || [ -f local.properties ] || { echo "Не задан Android SDK: export ANDROID_HOME=... или создайте local.properties (sdk.dir=...)"; exit 1; }
	@echo "Временная ASCII-копия: $(STAGE)"
	rm -rf "$(STAGE)"
	mkdir -p "$(STAGE)"
	tar -cf - --exclude='./.gradle' --exclude='./build' --exclude='./app/build' --exclude='./dist' . | tar -xf - -C "$(STAGE)"
	sed -i '/^android\.overridePathCheck=true$$/d' "$(STAGE)/gradle.properties"
	cd "$(STAGE)" && $(GRADLE) --no-build-cache :app:assembleDebug </dev/null
	@mkdir -p $(DIST)
	cp "$(STAGE)/$(APK_SRC)" $(APK)
	@echo "APK: $(APK)"

source:
	@mkdir -p $(DIST)
	@rm -f $(SRC_ZIP)
	cd .. && zip -qr $(CURDIR)/$(SRC_ZIP) $(notdir $(CURDIR)) -x '*/.gradle/*' '*/build/*' '*/dist/*' '*/local.properties'
	@echo "Source: $(SRC_ZIP)"

install: apk
	$(ADB) install -r $(APK)

clean:
	rm -rf $(DIST) build app/build .gradle
