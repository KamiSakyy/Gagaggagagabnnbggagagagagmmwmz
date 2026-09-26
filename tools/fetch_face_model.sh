#!/usr/bin/env bash
# Fetches the MediaPipe Face Landmarker model (with blendshapes, the VTube Studio grade tracker)
# into app/src/main/assets/models/face_landmarker.task.
#
# The model is a 3.7 MB task bundle that is not part of this repository, so it is downloaded at
# build time. Several mirrors are tried because the canonical Google Storage URL is not reachable
# from every network; the last resort is the Git LFS object of a public repository, which is fetched
# through the documented LFS batch API. When every source fails the build still succeeds - the app
# then falls back to ML Kit for face tracking - but the CI job records the failure loudly.
set -uo pipefail

DEST="${1:-app/src/main/assets/models/face_landmarker.task}"
mkdir -p "$(dirname "$DEST")"

# The sha256 of the official float16 face_landmarker.task of the 1.0.0 release.
EXPECTED_SHA="64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"
LFS_SIZE=3758596

verify() {
  local file="$1"
  [ -f "$file" ] || return 1
  local size
  size=$(stat -c%s "$file" 2>/dev/null || stat -f%z "$file")
  if [ "$size" -lt 1000000 ]; then
    echo "  слишком маленький файл: $size байт"
    return 1
  fi
  if grep -q "git-lfs" "$file" 2>/dev/null; then
    echo "  вместо модели скачался указатель Git LFS - источник не годится"
    return 1
  fi
  # The official bundle is a flatbuffer with metadata and weighs 3.7 MB; anything much smaller is a
  # truncated download or an error page.
  if [ "$size" -lt 2000000 ]; then
    echo "  файл слишком мал для модели MediaPipe"
    return 1
  fi
  local sha
  sha=$(sha256sum "$file" | cut -d' ' -f1)
  echo "  получено $size байт, sha256=$sha"
  if [ "$sha" = "$EXPECTED_SHA" ]; then
    echo "  это ровно официальная модель"
  else
    echo "  внимание: sha256 отличается от официального (файл всё равно проверен на формат)"
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
      echo "модель получена из: $name"
      return 0
    fi
  fi
  rm -f "$DEST.part"
  return 1
}

try_lfs() {
  local repo="$1" path="$2"
  echo "--- источник: Git LFS $repo/$path"
  local href
  href=$(curl -s --connect-timeout 20 -X POST "https://github.com/$repo.git/info/lfs/objects/batch" \
    -H "Accept: application/vnd.git-lfs+json" -H "Content-Type: application/vnd.git-lfs+json" \
    -d "{\"operation\":\"download\",\"transfers\":[\"basic\"],\"objects\":[{\"oid\":\"$EXPECTED_SHA\",\"size\":$LFS_SIZE}]}" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['objects'][0]['actions']['download']['href'])" 2>/dev/null)
  if [ -z "$href" ]; then
    echo "  LFS не отдал ссылку"
    return 1
  fi
  echo "  ссылка получена"
  try_url "Git LFS $repo" "$href"
  return $?
}

echo "=== загрузка модели Face Landmarker ==="
if [ -f "$DEST" ] && verify "$DEST"; then
  echo "модель уже на месте, скачивание не нужно"
  exit 0
fi

GCS="https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16"
# Сначала пробуем вторую версию бандла: она устойчивее читает мимику. Приложение выбирает её
# автоматически, если она лежит в сборке.
try_url "официальный Google Storage (вторая версия, через mediapipe-assets)" \
    "https://storage.googleapis.com/mediapipe-assets/face_landmarker_v2_with_blendshapes.task" \
  && cp "$DEST" "$(dirname "$DEST")/face_landmarker_v2.task" \
  && echo "вторая версия лица сохранена отдельно: face_landmarker_v2.task" \
  && exit 0
try_url "официальный Google Storage (релиз 1)" "$GCS/1/face_landmarker.task" \
  || try_url "официальный Google Storage (последний)" "$GCS/latest/face_landmarker.task" \
  || try_url "официальный Google Storage (blendshapes v2)" \
    "https://storage.googleapis.com/mediapipe-assets/face_landmarker_v2_with_blendshapes.task" \
  || try_lfs "devp1866/face-recognition" "face_landmarker_v2_with_blendshapes.task" \
  || try_lfs "DCP0001/face-render" "face_landmarker_v2_with_blendshapes.task" \
  || try_lfs "sandipan004/SmartCompanion" "tasks/face_landmarker.task"

if [ -f "$DEST" ] && verify "$DEST" >/dev/null 2>&1; then
  echo "МОДЕЛЬ ГОТОВА: $(stat -c%s "$DEST" 2>/dev/null || stat -f%z "$DEST") байт"
  exit 0
fi

echo "НЕ УДАЛОСЬ СКАЧАТЬ МОДЕЛЬ MEDIAPIPE"
echo "приложение соберётся и будет работать, но трекером станет ML Kit (без blendshape)"
exit 0
