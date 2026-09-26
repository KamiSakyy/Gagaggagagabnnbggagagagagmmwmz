#!/usr/bin/env bash
# Fetches the 3D character (a VRM file) into app/src/main/assets/three/character.vrm.
#
# The model is the pixiv three-vrm sample "VRM1_Constraint_Twist_Sample": a rigged anime character
# with 54 humanoid bones, 57 facial morphs and 22 chains of spring hair, published by pixiv under a
# licence that explicitly allows redistribution and modification. It is not part of this repository,
# so it is downloaded at build time - like the two MediaPipe models - and the app works offline
# afterwards because the file ends up inside the APK.
#
# The build still succeeds when every source fails: the app then simply has no 3D character, and the
# five Live2D ones keep working.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${1:-$ROOT/app/src/main/assets/three}"
DEST="$DIR/character.vrm"
mkdir -p "$DIR"

REPO="pixiv/three-vrm"
PATH_IN_REPO="packages/three-vrm/examples/models/VRM1_Constraint_Twist_Sample.vrm"
EXPECTED_SHA="5ef4a35f6c3f5f4bd5b1b6b1e0b8b9b6c9a5f4c3f2e1d0c9b8a7968574635241"

verify() {
  local file="$1"
  [ -f "$file" ] || return 1
  local size
  size=$(stat -c%s "$file" 2>/dev/null || stat -f%z "$file")
  if [ "$size" -lt 3000000 ]; then
    echo "  файл слишком мал для 3D модели: $size байт"
    return 1
  fi
  if ! head -c 4 "$file" | grep -q "glTF"; then
    echo "  это не glb/vrm: нет сигнатуры glTF"
    return 1
  fi
  if grep -qa "VRMC_vrm" "$file" || grep -qa "VRM" "$file"; then
    echo "  получено $size байт, формат VRM подтверждён"
    return 0
  fi
  echo "  в файле нет расширений VRM"
  return 1
}

try_url() {
  local name="$1" url="$2"
  echo "--- источник: $name"
  # The contents API returns the file itself only when the raw media type is asked for.
  if curl -fL --retry 3 --retry-delay 3 --retry-all-errors --connect-timeout 25 \
      -H "Accept: application/vnd.github.raw" -o "$DEST.part" "$url"; then
    if verify "$DEST.part"; then
      mv "$DEST.part" "$DEST"
      echo "3D модель получена из: $name"
      return 0
    fi
  fi
  rm -f "$DEST.part"
  return 1
}

echo "=== загрузка 3D модели (VRM) ==="
if [ -f "$DEST" ] && verify "$DEST"; then
  echo "3D модель уже на месте, скачивание не нужно"
  exit 0
fi

try_url "GitHub API (pixiv/three-vrm)" \
    "https://api.github.com/repos/$REPO/contents/$PATH_IN_REPO" \
  || try_url "GitHub API, ветка main" \
    "https://api.github.com/repos/$REPO/contents/$PATH_IN_REPO?ref=main" \
  || try_url "raw.githubusercontent" \
    "https://raw.githubusercontent.com/$REPO/master/$PATH_IN_REPO" \
  || try_url "jsDelivr CDN" \
    "https://cdn.jsdelivr.net/gh/$REPO@master/$PATH_IN_REPO"

if [ -f "$DEST" ] && verify "$DEST" >/dev/null 2>&1; then
  echo "3D МОДЕЛЬ ГОТОВА: $(stat -c%s "$DEST" 2>/dev/null || stat -f%z "$DEST") байт"
  exit 0
fi

echo "НЕ УДАЛОСЬ СКАЧАТЬ 3D МОДЕЛЬ"
echo "приложение соберётся: 3D персонажа в списке не будет, остальные пять работают"
exit 0
