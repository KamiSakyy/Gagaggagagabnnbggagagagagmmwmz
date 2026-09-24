#!/usr/bin/env bash
set -e
# Tools/setup.sh — клонирует тяжёлый исходник Genshin-like (1.2ГБ) и готовит Unity проект
# Запуск: bash Tools/setup.sh

echo "=== Genshin-like Open World Setup ==="
echo "Требуется: git lfs, ~5GB свободно, Unity Hub + Unity 6000.6.0f1"

if ! command -v git-lfs &>/dev/null; then
  echo "Установи git-lfs: https://git-lfs.com (sudo apt install git-lfs; git lfs install)"
  exit 1
fi
git lfs install

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/Source/Genshin"

if [ -f "$SRC/ProjectSettings/ProjectVersion.txt" ]; then
  echo "Source/Genshin уже на месте: $SRC"
else
  echo "Клонирую 949886/Genshin в Source/Genshin ..."
  mkdir -p "$ROOT/Source"
  if [ -d "$SRC/.git" ]; then
    echo "Папка уже есть, обновляю..."
    git -C "$SRC" pull --recurse-submodules || true
  else
    rm -rf "$SRC"
    git clone --recurse-submodules https://github.com/949886/Genshin.git "$SRC"
  fi
fi

# докачать GI-Models отдельно если submodule пустой (747MB)
if [ ! -d "$SRC/Assets/Modules/GI/Avatar" ]; then
  echo "Докачиваю GI-Models (аватары Nahida и т.д.) ..."
  rm -rf "$SRC/Assets/Modules/GI"
  git clone --depth 1 --branch Unity https://github.com/LunarDrive/GI-Models.git "$SRC/Assets/Modules/GI" || echo "Не удалось скачать GI-Models, мир будет без моделей — скачай вручную"
fi

# LunarFramework
if [ ! -d "$SRC/Packages/LunarFramework" ] || [ -z "$(ls -A "$SRC/Packages/LunarFramework" 2>/dev/null)" ]; then
  echo "Докачиваю LunarFramework ..."
  rm -rf "$SRC/Packages/LunarFramework"
  git clone --depth 1 https://github.com/949886/LunarFramework.git "$SRC/Packages/LunarFramework" || true
fi

# ThirdPersonPack (доп. контроллер)
if [ ! -d "$ROOT/Source/ThirdPersonPack/.git" ]; then
  echo "Клонирую ThirdPersonPack..."
  rm -rf "$ROOT/Source/ThirdPersonPack"
  git clone --depth 1 https://github.com/conradosaud/ThirdPersonPack.git "$ROOT/Source/ThirdPersonPack" || true
fi

echo ""
echo "=== Проверка размеров ==="
du -sh "$SRC" 2>/dev/null || true
du -sh "$SRC/Assets/Modules/GI" 2>/dev/null || true
cat "$SRC/ProjectSettings/ProjectVersion.txt" 2>/dev/null || echo "Version file missing"

echo ""
echo "✅ Готово."
echo "Открой Unity Hub → Add project from disk → выбери $SRC"
echo "Unity версия: 6000.6.0f1 (f7f8ed4d1e24) — установи через Unity Hub если нет"
echo "Модули: Android Build Support + OpenJDK + Android SDK & NDK"
echo "Затем: File → Build Settings → Android → Switch Platform → Build"
echo "Или собери через Actions: git push и смотри вкладку Actions → Android Unity Build"
echo ""
echo "Для замены модели на свою аниме-тян:"
echo " - Импортируй UnityChan / VRoid VRM + HoyoToon shader"
echo " - Перетащи префаб в Assets/Avatar"
echo " - См. docs/SEARCH_REPORT.md секция 'Где взять модели'"
