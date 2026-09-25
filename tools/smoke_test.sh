#!/usr/bin/env bash
# Proves on a real Android device (the CI emulator) that the app works: it installs the APK, waits
# for the model to load, drives every mode through the documented intent extras, screenshots each
# one, runs the in-app self test, hammers the app with random input and finally checks that the
# screenshots really contain the model.
#
# Usage: tools/smoke_test.sh <apk> [screens-directory]
# Requires: adb on PATH, a booted device (see tools/start_emulator.sh), python3 with Pillow.
set -uo pipefail

APK="${1:-}"
SCREENS="${2:-ci-out/screens}"
PKG="com.echidna.studio"
ACTIVITY="$PKG/.MainActivity"
FAILED=0

say() {
  echo "$@"
}

problem() {
  echo "ПРОБЛЕМА: $*"
  FAILED=1
}

dump_logs() {
  echo "--- что писало приложение ---"
  adb logcat -d -s EchidnaStudio:* 2>/dev/null | tail -n 150 || true
  echo "--- исключения ---"
  adb logcat -d -s AndroidRuntime:E 2>/dev/null | tail -n 120 || true
}

shot() {
  local name="$1"
  mkdir -p "$SCREENS"
  if timeout 60 adb exec-out screencap -p > "$SCREENS/$name.png"; then
    say "снимок: $name.png"
  else
    problem "не удалось снять экран ($name)"
  fi
}

open() {
  # Every mode is addressed by intent extras, the same ones the documentation mentions.
  timeout 60 adb shell am start -n "$ACTIVITY" "$@" > /dev/null 2>&1 || true
}

# ---------------------------------------------------------------- device
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  problem "APK не найден: '$APK'"
  exit 1
fi

say "=== устройство ==="
if ! timeout 300 adb wait-for-device; then
  problem "устройство не появилось"
  exit 1
fi
for i in $(seq 1 60); do
  if [ "$(timeout 20 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    break
  fi
  sleep 5
done
adb devices -l || true
adb shell getprop ro.build.version.sdk || true
adb shell getprop ro.product.cpu.abi || true
if [ "$(timeout 20 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
  problem "устройство не загрузилось"
  exit 1
fi

# ---------------------------------------------------------------- install
say "=== установка $APK ==="
if ! timeout 600 adb install -r -g "$APK"; then
  say "первая попытка не удалась, повторяю"
  adb uninstall "$PKG" > /dev/null 2>&1 || true
  if ! timeout 600 adb install -r -g "$APK"; then
    problem "APK не установился"
    exit 1
  fi
fi
timeout 30 adb shell pm grant "$PKG" android.permission.CAMERA > /dev/null 2>&1 || true
timeout 30 adb shell pm grant "$PKG" android.permission.RECORD_AUDIO > /dev/null 2>&1 || true
adb shell dumpsys package "$PKG" | grep -E "versionName|granted=true" | head -n 6 || true

# ---------------------------------------------------------------- launch
say "=== запуск ==="
adb logcat -c || true
open --ez echidna_autoplay true
READY=0
for i in $(seq 1 30); do
  if adb logcat -d | grep -q "APPREADY"; then
    READY=1
    break
  fi
  sleep 4
done
adb logcat -d | grep -a -E "APPREADY|MODEL \||GL \|" | tail -n 20 || true
if [ "$READY" != "1" ]; then
  problem "приложение не дошло до готовности, модель не загрузилась"
  dump_logs
  exit 1
fi
if adb logcat -d | grep -q "UnsatisfiedLinkError"; then
  problem "нативная библиотека Live2D не загрузилась"
  adb logcat -d | grep -a -A 15 "UnsatisfiedLinkError" | head -n 40 || true
  exit 1
fi
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  problem "приложение упало при запуске"
  dump_logs
  exit 1
fi
shot 00-start

# ---------------------------------------------------------------- shows
say "=== номера ==="
for show in cute dance charm greet surprise; do
  open --ez echidna_show "$show"
  sleep 4
  shot "show-$show"
done

say "=== хромакей ==="
open --ez echidna_show greet --es echidna_background CHROMA_GREEN
sleep 4
shot background-chroma
open --es echidna_background NIGHT
sleep 3

say "=== галерея всех анимаций ==="
open --ez echidna_gallery true
sleep 3
shot gallery

# ---------------------------------------------------------------- camera
say "=== режим камеры ==="
open --ez echidna_camera true
sleep 10
shot camera
adb logcat -d | grep -a -E "CAMERA \||TRACK \||VIDEO \|" | tail -n 25 || true
FRAMES=$(adb logcat -d | grep -a -o "кадров камеры=[0-9]*" | tail -n 1 | cut -d= -f2)
FRAMES=${FRAMES:-0}
say "кадров камеры в отчёте трекера: $FRAMES"
if [ "$FRAMES" = "0" ]; then
  echo "ПРЕДУПРЕЖДЕНИЕ: трекер не получил ни одного кадра камеры (на некоторых эмуляторах камеры нет)" | tee ci-out/camera-frames.txt
else
  say "камера выдаёт кадры" | tee ci-out/camera-frames.txt
fi
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  problem "приложение упало в режиме камеры"
  dump_logs
  exit 1
fi

# ---------------------------------------------------------------- self test
say "=== самопроверка внутри приложения ==="
adb shell am force-stop "$PKG" || true
sleep 3
adb logcat -c || true
open --ez echidna_selftest true --ez echidna_selftest_camera true
DONE=0
for i in $(seq 1 45); do
  adb logcat -d | grep -a "SELFTEST | ИТОГ" > ci-out/selftest.txt || true
  if [ -s ci-out/selftest.txt ]; then
    DONE=1
    break
  fi
  sleep 5
done
adb logcat -d | grep -a "SELFTEST" | tail -n 60 || true
shot selftest
if [ "$DONE" != "1" ]; then
  problem "самопроверка не завершилась"
  dump_logs
  exit 1
fi
cat ci-out/selftest.txt
if ! grep -q "ИТОГ OK" ci-out/selftest.txt; then
  problem "самопроверка сообщила о провале"
  dump_logs
  exit 1
fi
say "самопроверка пройдена"

# ---------------------------------------------------------------- stress
say "=== случайные нажатия ==="
adb shell monkey -p "$PKG" --throttle 250 120 > /dev/null 2>&1 || true
sleep 3
if ! adb shell pidof "$PKG" > /dev/null; then
  problem "приложение умерло после monkey"
  dump_logs
  exit 1
fi
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  problem "приложение упало во время стресса"
  dump_logs
  exit 1
fi
open --ez echidna_show dance
sleep 12
shot after-stress

# ---------------------------------------------------------------- screenshots
say "=== анализ скриншотов ==="
pip install --quiet pillow > /dev/null 2>&1 || true
COUNT=$(ls "$SCREENS"/*.png 2>/dev/null | wc -l)
say "кадров снято: $COUNT"
if [ "$COUNT" -lt 8 ]; then
  problem "скриншотов слишком мало, снимать не удалось"
fi
for image in "$SCREENS"/*.png; do
  name=$(basename "$image")
  case "$name" in
    background-chroma.png)
      python3 tools/verify_screens.py "$image" 1.0 --chroma 5,217,33 || problem "кадр $name не прошёл проверку"
      ;;
    gallery.png)
      python3 tools/verify_screens.py "$image" 1.0 || problem "кадр $name не прошёл проверку"
      ;;
    *)
      python3 tools/verify_screens.py "$image" 3.0 || problem "кадр $name не прошёл проверку"
      ;;
  esac
done

# ---------------------------------------------------------------- verdict
if [ "$FAILED" != "0" ]; then
  echo "SMOKE FAILED: что-то из проверок не прошло, подробности выше"
  exit 1
fi
echo "SMOKE OK: приложение установилось, запустилось, нарисовало модель во всех режимах, прошло самопроверку и стресс"
