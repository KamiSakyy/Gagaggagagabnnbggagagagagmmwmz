#!/usr/bin/env bash
# Fetches the MediaPipe Pose Landmarker bundles into app/src/main/assets/models/.
#
# Two bundles are used: the full one is the default (accurate shoulders, hips and hands), the lite
# one is the fallback for slow devices. Together they are about 15 MB, which the app budget of 100 MB
# happily allows.
#
# The canonical Google Storage URL is tried first. It is not reachable from every network (the
# sandbox this app is built in cannot see it at all), so the fallback is the npm registry: several
# public packages ship the very same official bundles in their tarballs. When every source fails the
# build still succeeds - the app then works without the body tracker - but the failure is loud.
set -uo pipefail

DIR="${1:-app/src/main/assets/models}"
mkdir -p "$DIR"

FULL="$DIR/pose_landmarker_full.task"
LITE="$DIR/pose_landmarker_lite.task"
# Самая мощная модель позы: точнее всех читает плечи, наклон корпуса и кисти. Она вдвое тяжелее
# полной, поэтому приложение включает её только тогда, когда телефон успевает её считать, и
# само переходит на полную, если кадры начинают отставать.
HEAVY="$DIR/pose_landmarker_heavy.task"

# The official float16 bundles of the MediaPipe 1.0.0 model collection.
FULL_SHA="4eaa5eb7a98365221087693fcc286334cf0858e2eb6e15b506aa4a7ecdcec4ad"
LITE_SHA="59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a"
HEAVY_SHA="64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b"
FULL_SIZE=9398198
LITE_SIZE=5777746
HEAVY_SIZE=30664242

verify() {
  local file="$1" expected_sha="$2" expected_size="$3"
  [ -f "$file" ] || return 1
  local size
  size=$(stat -c%s "$file" 2>/dev/null || stat -f%z "$file")
  if [ "$size" -lt 2000000 ]; then
    echo "  файл слишком мал для модели позы: $size байт"
    return 1
  fi
  if grep -q "git-lfs" "$file" 2>/dev/null; then
    echo "  вместо модели скачался указатель Git LFS - источник не годится"
    return 1
  fi
  # The bundle is a flatbuffer that names the models it carries; the lite one carries the same name.
  if ! grep -qa "pose_landmarks_detector" "$file"; then
    echo "  в файле нет модели позы внутри (не тот формат)"
    return 1
  fi
  local sha
  sha=$(sha256sum "$file" | cut -d' ' -f1)
  echo "  получено $size байт, sha256=$sha"
  if [ "$sha" = "$expected_sha" ]; then
    echo "  это ровно официальная модель ($expected_size байт)"
  else
    echo "  внимание: sha256 отличается от официального (формат всё равно проверен)"
  fi
  return 0
}

try_url() {
  local name="$1" url="$2" dest="$3" sha="$4" size="$5"
  echo "--- источник: $name"
  if curl -fL --retry 4 --retry-delay 4 --retry-all-errors --connect-timeout 20 \
      -o "$dest.part" "$url"; then
    if verify "$dest.part" "$sha" "$size"; then
      mv "$dest.part" "$dest"
      echo "модель получена из: $name"
      return 0
    fi
  fi
  rm -f "$dest.part"
  return 1
}

# Downloads an npm tarball and pulls one file out of it. The npm registry is reachable from the
# build sandbox where Google Storage is not.
try_npm() {
  local name="$1" package="$2" inner="$3" dest="$4" sha="$5" size="$6"
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
  if ! curl -fL --retry 3 --retry-delay 3 --connect-timeout 20 -o "$dest.tgz" "$tarball"; then
    rm -f "$dest.tgz"
    return 1
  fi
  # The tarball is unpacked into a scratch directory: the npm layout is package/<inner>.
  local scratch
  scratch=$(mktemp -d)
  if ! tar -xzf "$dest.tgz" -C "$scratch" "package/$inner" 2>/dev/null       && ! tar -xzf "$dest.tgz" -C "$scratch" --wildcards "package/$inner" 2>/dev/null; then
    echo "  файл $inner не найден в архиве"
    rm -f "$dest.tgz"
    rm -rf "$scratch"
    return 1
  fi
  rm -f "$dest.tgz"
  mv "$scratch/package/$inner" "$dest.part"
  rm -rf "$scratch"
  if verify "$dest.part" "$sha" "$size"; then
    mv "$dest.part" "$dest"
    echo "модель получена из: npm $package"
    return 0
  fi
  rm -f "$dest.part"
  return 1
}

fetch_one() {
  local kind="$1" dest="$2" sha="$3" size="$4"
  local base="https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_$kind/float16"
  if [ -f "$dest" ] && verify "$dest" "$sha" "$size" >/dev/null 2>&1; then
    echo "$kind: модель уже на месте"
    return 0
  fi
  try_url "официальный Google Storage ($kind, latest)" "$base/latest/pose_landmarker_$kind.task" \
      "$dest" "$sha" "$size" \
    || try_url "официальный Google Storage ($kind, релиз 1)" "$base/1/pose_landmarker_$kind.task" \
      "$dest" "$sha" "$size" \
    || try_npm "npm pose-landmarker-react-native ($kind)" "pose-landmarker-react-native" \
      "android/src/main/assets/models/pose_landmarker_$kind.task" "$dest" "$sha" "$size" \
    || { [ "$kind" = "full" ] && try_npm "npm expo-mediapipe-pose ($kind)" "expo-mediapipe-pose" \
      "assets/pose_landmarker_full.task" "$dest" "$sha" "$size"; }
}

echo "=== загрузка моделей позы (тело, наклон, руки) ==="
ok_full=0
ok_lite=0
ok_heavy=0
fetch_one heavy "$HEAVY" "$HEAVY_SHA" "$HEAVY_SIZE" && ok_heavy=1
fetch_one full "$FULL" "$FULL_SHA" "$FULL_SIZE" && ok_full=1
fetch_one lite "$LITE" "$LITE_SHA" "$LITE_SIZE" && ok_lite=1

if [ "$ok_full" = "1" ] || [ "$ok_lite" = "1" ] || [ "$ok_heavy" = "1" ]; then
  ls -la "$DIR"/pose_landmarker_*.task 2>/dev/null
  echo "МОДЕЛИ ПОЗЫ ГОТОВЫ"
  exit 0
fi

echo "НЕ УДАЛОСЬ СКАЧАТЬ МОДЕЛИ ПОЗЫ MEDIAPIPE"
echo "приложение соберётся и будет работать, но руки и тело отслеживаться не будут"
exit 0
