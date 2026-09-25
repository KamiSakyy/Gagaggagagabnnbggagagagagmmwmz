#!/usr/bin/env bash
# Starts an x86_64 Android emulator and waits for it with a watchdog, so a dead or misconfigured
# emulator fails the build in minutes instead of hanging until the job timeout.
#
# Usage: tools/start_emulator.sh [system-image] [api-level]
set -uo pipefail

SDK="${SDK:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
IMAGE="${1:-system-images;android-30;default;x86_64}"
API="${2:-30}"
AVD="${AVD_NAME:-echidna}"
LOG="${EMULATOR_LOG:-ci-out/emulator.log}"
BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-900}"

export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p ci-out "$(dirname "$LOG")"

echo "=== подготовка эмулятора ($IMAGE) ==="
if [ ! -x "$SDK/emulator/emulator" ]; then
  echo "ЭМУЛЯТОРА НЕТ: $SDK/emulator/emulator не найден"
  exit 1
fi
"$SDK/emulator/emulator" -version 2>&1 | head -n 3 || true
adb start-server > /dev/null 2>&1 || true

SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
AV="$SDK/cmdline-tools/latest/bin/avdmanager"
if [ -x "$SM" ]; then
  yes | "$SM" --licenses > /dev/null 2>&1 || true
  if ! "$SM" --install "platform-tools" "emulator" "$IMAGE" > ci-out/sdkmanager.log 2>&1; then
    echo "ПРОВАЛ: не удалось поставить $IMAGE, смотри ci-out/sdkmanager.log"
    tail -n 30 ci-out/sdkmanager.log || true
    exit 1
  fi
fi
if [ -x "$AV" ]; then
  echo no | "$AV" create avd -n "$AVD" -k "$IMAGE" --force > ci-out/avdmanager.log 2>&1 || true
  "$AV" list avd | head -n 20 || true
fi

echo "=== запуск ==="
# -writable-system is not needed; both cameras are emulated so the app gets a real camera pipeline.
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
    tail -n 80 "$LOG" || true
    exit 1
  fi
  STATE=$(timeout 30 adb get-state 2>/dev/null | tr -d '\r')
  if [ "$STATE" = "device" ] \
     && [ "$(timeout 30 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    BOOTED=1
    break
  fi
  sleep 10
done

if [ "$BOOTED" != "1" ]; then
  echo "ПРОВАЛ: эмулятор не загрузился за $((BOOT_TIMEOUT_SECONDS / 60)) минут"
  tail -n 80 "$LOG" || true
  exit 1
fi

echo "EMULATOR BOOTED"
adb devices -l || true
adb shell input keyevent 82 > /dev/null 2>&1 || true
adb shell svc power stayon true > /dev/null 2>&1 || true
sleep 5
