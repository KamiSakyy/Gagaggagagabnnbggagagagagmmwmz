#!/usr/bin/env bash
# Загружает официальную модель кисти MediaPipe Hand Landmarker в app/src/main/assets/models/.
#
# Модель даёт 21 точку на руку и до двух рук: по ней считаются пальцы, видно ладонь и понятно,
# дотянулся ли человек рукой до подбородка. Без неё приложение работает как раньше - лицо и тело, -
# но жестов рукой не будет, поэтому неудача громкая.
#
# Официальный Google Storage доступен не из всякой сети (в песочнице сборки он недоступен вовсе),
# поэтому второй источник - npm: пакет expo-vision-camera-v4-mediapipe несёт в себе ровно тот же
# официальный бандл. Размер и sha256 проверяются, а внутри архива ищутся обе tflite-модели.
set -uo pipefail

DIR="${1:-app/src/main/assets/models}"
mkdir -p "$DIR"
DEST="$DIR/hand_landmarker.task"

# Официальный float16 бандл MediaPipe 1.0.0: детектор ладони + модель 21 точки.
EXPECTED_SHA="fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"
EXPECTED_SIZE=7819105

verify() {
  local file="$1"
  [ -f "$file" ] || return 1
  local size
  size=$(stat -c%s "$file" 2>/dev/null || stat -f%z "$file")
  if [ "$size" -lt 3000000 ]; then
    echo "  файл слишком мал для модели кисти: $size байт"
    return 1
  fi
  if grep -q "git-lfs" "$file" 2>/dev/null; then
    echo "  вместо модели скачался указатель Git LFS - источник не годится"
    return 1
  fi
  # Бандл - это zip с двумя моделями внутри; так отсекается любой не тот файл.
  if ! python3 - "$file" <<'PY'
import sys, zipfile
try:
    names = zipfile.ZipFile(sys.argv[1]).namelist()
except Exception as error:
    print("  не читается как бандл задачи: %s" % error)
    raise SystemExit(1)
if "hand_detector.tflite" not in names or "hand_landmarks_detector.tflite" not in names:
    print("  внутри нет моделей кисти: %s" % ", ".join(names))
    raise SystemExit(1)
PY
  then
    return 1
  fi
  local sha
  sha=$(sha256sum "$file" | cut -d' ' -f1)
  echo "  получено $size байт, sha256=$sha"
  if [ "$sha" = "$EXPECTED_SHA" ]; then
    echo "  это ровно официальная модель кисти ($EXPECTED_SIZE байт)"
  else
    echo "  внимание: sha256 отличается от официального (формат всё равно проверен)"
  fi
  return 0
}

try_url() {
  local name="$1" url="$2"
  echo "--- источник: $name"
  if curl -fL --retry 4 --retry-delay 4 --retry-all-errors --connect-timeout 20 \
      -o "$DEST.part" "$url"; then
    if verify "$DEST.part"; then
      mv "$DEST.part" "$DEST"
      echo "модель кисти получена из: $name"
      return 0
    fi
  fi
  rm -f "$DEST.part"
  return 1
}

# Скачивает npm-тарбол и достаёт из него один файл: package/<inner>.
try_npm() {
  local package="$1" inner="$2"
  echo "--- источник: npm $package"
  local tarball
  tarball=$(curl -s --connect-timeout 20 "https://registry.npmjs.org/$package" \
    | python3 -c "import json,sys
d=json.load(sys.stdin)
v=d['dist-tags']['latest']
print(d['versions'][v]['dist']['tarball'])" 2>/dev/null)
  if [ -z "$tarball" ]; then
    echo "  не удалось получить ссылку на пакет"
    return 1
  fi
  if ! curl -fL --retry 3 --retry-delay 3 --connect-timeout 20 -o "$DEST.tgz" "$tarball"; then
    rm -f "$DEST.tgz"
    return 1
  fi
  local scratch
  scratch=$(mktemp -d)
  if ! tar -xzf "$DEST.tgz" -C "$scratch" "package/$inner" 2>/dev/null \
      && ! tar -xzf "$DEST.tgz" -C "$scratch" --wildcards "package/$inner" 2>/dev/null; then
    echo "  файл $inner не найден в архиве"
    rm -f "$DEST.tgz"
    rm -rf "$scratch"
    return 1
  fi
  rm -f "$DEST.tgz"
  mv "$scratch/package/$inner" "$DEST.part"
  rm -rf "$scratch"
  if verify "$DEST.part"; then
    mv "$DEST.part" "$DEST"
    echo "модель кисти получена из: npm $package"
    return 0
  fi
  rm -f "$DEST.part"
  return 1
}

echo "=== загрузка модели кисти (пальцы, ладонь, подбородок) ==="

if [ -f "$DEST" ] && verify "$DEST" >/dev/null 2>&1; then
  echo "модель кисти уже на месте"
  ls -la "$DEST"
  echo "МОДЕЛЬ КИСТИ ГОТОВА"
  exit 0
fi

try_url "официальный Google Storage (hand_landmarker, latest)" \
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task" \
  || try_url "официальный Google Storage (hand_landmarker, релиз 1)" \
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task" \
  || try_npm "expo-vision-camera-v4-mediapipe" "hand_landmarker.task" \
  || {
    echo "НЕ УДАЛОСЬ СКАЧАТЬ МОДЕЛЬ КИСТИ MEDIAPIPE"
    echo "приложение соберётся: лицо и тело отслеживаются, жестов рукой не будет"
    exit 0
  }

ls -la "$DEST"
echo "МОДЕЛЬ КИСТИ ГОТОВА"
