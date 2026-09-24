#!/usr/bin/env bash
set -e
# Локальная сборка APK без GitHub Actions (требует установленный Unity + Android SDK)
# Использование: bash Tools/build-apk.sh

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$ROOT/Source/Genshin"
UNITY="${UNITY_PATH:-/opt/unity/Editor/Unity}"
# UNITY_PATH можно переопределить: UNITY_PATH="/Applications/Unity/Hub/Editor/6000.6.0f1/Unity.app/Contents/MacOS/Unity" bash Tools/build-apk.sh

if [ ! -f "$PROJECT/ProjectSettings/ProjectVersion.txt" ]; then
  echo "Проект не найден: $PROJECT"
  echo "Сначала запусти: bash Tools/setup.sh"
  exit 1
fi

VERSION=$(cat "$PROJECT/ProjectSettings/ProjectVersion.txt" | grep m_EditorVersion | awk '{print $2}')
echo "Project Unity version: $VERSION"
echo "Unity binary: $UNITY"

if [ ! -f "$UNITY" ] && [ ! -f "$UNITY.exe" ]; then
  echo "Unity не найден по пути $UNITY"
  echo "Укажи UNITY_PATH или установи Unity 6000.6.0f1 через Unity Hub"
  echo "Пример: export UNITY_PATH=\"/Applications/Unity/Hub/Editor/6000.6.0f1/Unity.app/Contents/MacOS/Unity\""
  exit 1
fi

BUILD_DIR="$ROOT/build/Android"
mkdir -p "$BUILD_DIR"

echo "=== Building Android APK (target ≤500MB) ==="
"$UNITY" -batchmode -nographics -quit \
  -projectPath "$PROJECT" \
  -buildTarget Android \
  -executeMethod BuildScript.BuildAndroid \
  -logFile "$ROOT/build/build.log" \
  || {
    # Фолбек: если нет BuildScript, пробуем стандартный билд через -buildWindowsPlayer аналог
    echo "BuildScript.BuildAndroid не найден, пробуем Unity -buildTarget ..."
    "$UNITY" -batchmode -nographics -quit \
      -projectPath "$PROJECT" \
      -buildTarget Android \
      -customBuildTarget Android \
      -customBuildName GenshinAnime \
      -customBuildPath "$BUILD_DIR/GenshinAnime.apk" \
      -logFile "$ROOT/build/build.log" || true
  }

echo "=== Build finished, logs ==="
cat "$ROOT/build/build.log" | tail -n 200 || true
ls -lh "$BUILD_DIR" || true
find "$BUILD_DIR" -name "*.apk" -exec ls -lh {} \; || echo "APK не создан — проверь лог выше"
APK=$(find "$BUILD_DIR" -name "*.apk" | head -n1)
if [ -n "$APK" ]; then
  SIZE_MB=$(du -m "$APK" | cut -f1)
  echo "APK: $APK — ${SIZE_MB}MB"
  if [ "$SIZE_MB" -gt 500 ]; then echo "⚠️ Больше 500МБ — включи Split APK / ASTC / Strip Engine Code"; else echo "✅ В пределах 500МБ"; fi
fi
