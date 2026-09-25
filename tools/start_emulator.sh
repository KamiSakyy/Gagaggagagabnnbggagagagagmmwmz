#!/usr/bin/env bash
# Starts an x86_64 Android emulator and waits for it with a watchdog, so a dead or misconfigured
# emulator fails the build in minutes instead of hanging until the job timeout.
#
# The script finds the Android SDK on its own (CI images keep it in different places), installs the
# emulator and the system image if they are missing, and prints everything it decides so a failure
# is diagnosable from the log alone.
#
# Usage: tools/start_emulator.sh [system-image] [api-level]
set -uo pipefail

find_sdk() {
  local candidate
  for candidate in "${SDK:-}" "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
                   /usr/local/lib/android/sdk "$HOME/Android/Sdk" /opt/android-sdk; do
    if [ -n "$candidate" ] && [ -d "$candidate" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

find_sdkmanager() {
  local sdk="$1" candidate
  for candidate in "$sdk/cmdline-tools/latest/bin/sdkmanager" \
                   "$sdk/cmdline-tools/bin/sdkmanager" \
                   "$sdk/tools/bin/sdkmanager" \
                   "$(command -v sdkmanager 2>/dev/null)"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

find_avdmanager() {
  local sdk="$1" candidate
  for candidate in "$sdk/cmdline-tools/latest/bin/avdmanager" \
                   "$sdk/cmdline-tools/bin/avdmanager" \
                   "$sdk/tools/bin/avdmanager" \
                   "$(command -v avdmanager 2>/dev/null)"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

SM_IMAGE="${1:-system-images;android-30;default;x86_64}"
AVD="${AVD_NAME:-echidna}"
LOG="${EMULATOR_LOG:-ci-out/emulator.log}"
BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-1200}"
mkdir -p ci-out "$(dirname "$LOG")"

echo "=== где Android SDK ==="
SDK=$(find_sdk) || {
  echo "ПРОВАЛ: не нашёл Android SDK. Проверял SDK=$SDK ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-} ANDROID_HOME=${ANDROID_HOME:-}"
  exit 1
}
export SDK
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
echo "SDK=$SDK"
ls -la "$SDK" || true
echo "содержимое sdk/emulator:"; ls -la "$SDK/emulator" 2>/dev/null | head -n 10 || echo "нет каталога"

SM=$(find_sdkmanager "$SDK" || true)
AV=$(find_avdmanager "$SDK" || true)
echo "sdkmanager: ${SM:-не найден}"
echo "avdmanager: ${AV:-не найден}"

echo "=== ставлю emulator, platform-tools и образ $SM_IMAGE ==="
if [ -n "$SM" ]; then
  yes | "$SM" --sdk_root="$SDK" --licenses > /dev/null 2>&1 || true
  if ! "$SM" --sdk_root="$SDK" --install "platform-tools" "emulator" "$SM_IMAGE" \
      > ci-out/sdkmanager.log 2>&1; then
    echo "установка через sdkmanager не удалась, смотри ниже"
    tail -n 40 ci-out/sdkmanager.log || true
  fi
  grep -E "Installing|Installed|Warning|error" ci-out/sdkmanager.log 2>/dev/null | tail -n 15 || true
else
  echo "sdkmanager не найден, полагаюсь на уже установленный эмулятор"
fi

if [ ! -x "$SDK/emulator/emulator" ]; then
  echo "ПРОВАЛ: $SDK/emulator/emulator так и не появился"
  find /usr/local/lib/android /opt /home/runner -maxdepth 4 -name "emulator" -type d 2>/dev/null | head -n 5 || true
  exit 1
fi

export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
echo "версия эмулятора:"; "$SDK/emulator/emulator" -version 2>&1 | head -n 3 || true
adb start-server > /dev/null 2>&1 || true

if [ -n "$AV" ]; then
  echo no | "$AV" create avd -n "$AVD" -k "$SM_IMAGE" --force > ci-out/avdmanager.log 2>&1 || true
  "$AV" list avd 2>/dev/null | head -n 20 || true
else
  echo "avdmanager не найден, пробую запустить уже созданный AVD"
fi

echo "=== запуск ==="
# Both cameras are emulated, which gives the app a real camera pipeline to talk to.
nohup "$SDK/emulator/emulator" -avd "$AVD" \
  -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics \
  -gpu swiftshader_indirect \
  -camera-back emulated -camera-front emulated \
  -verbose > "$LOG" 2>&1 &
EMULATOR_PID=$!
echo "pid эмулятора: $EMULATOR_PID"

DEADLINE=$(( $(date +%s) + BOOT_TIMEOUT_SECONDS ))
BOOTED=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    echo "ПРОВАЛ: процесс эмулятора завершился"
    tail -n 100 "$LOG" || true
    exit 1
  fi
  if [ "$(timeout 30 adb get-state 2>/dev/null | tr -d '\r')" = "device" ] \
     && [ "$(timeout 30 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    BOOTED=1
    break
  fi
  sleep 10
done

if [ "$BOOTED" != "1" ]; then
  echo "ПРОВАЛ: эмулятор не загрузился за $((BOOT_TIMEOUT_SECONDS / 60)) минут"
  tail -n 100 "$LOG" || true
  exit 1
fi

echo "EMULATOR BOOTED"
adb devices -l || true
adb shell input keyevent 82 > /dev/null 2>&1 || true
adb shell svc power stayon true > /dev/null 2>&1 || true
sleep 5
