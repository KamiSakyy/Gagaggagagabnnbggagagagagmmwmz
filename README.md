# Gagaggagagabnnbggagagagagmmwmz — Genshin-like Anime Open World (Android)

> **3D от третьего лица, открытый мир как в Genshin Impact, очень красивые аниме-девушки, Android APK до 500МБ, исходник 1ГБ+**

Этот репозиторий — **готовая обёртка для твоей игры**. Внутри найденный исходник + настроенные GitHub Actions для сборки APK через Android SDK / Gradle / Unity.

[![Android Unity Build](https://github.com/KamiSakyy/Gagaggagagabnnbggagagagagmmwmz/actions/workflows/android-unity.yml/badge.svg)](https://github.com/KamiSakyy/Gagaggagagabnnbggagagagagmmwmz/actions/workflows/android-unity.yml)
[![Android Gradle](https://github.com/KamiSakyy/Gagaggagagabnnbggagagagagmmwmz/actions/workflows/android-gradle.yml/badge.svg)](https://github.com/KamiSakyy/Gagaggagagabnnbggagagagagmmwmz/actions/workflows/android-gradle.yml)

---

## 🎯 Что найдено — СНАЧАЛА НАЙДИ!

Исследовано 50+ репозиториев. Полный отчёт: **[docs/SEARCH_REPORT.md](docs/SEARCH_REPORT.md)**

| # | Репозиторий | Что это | Размер исходника | APK | Почему смотреть |
|---|---|---|---|---|---|
| **🥇** | **[949886/Genshin](https://github.com/949886/Genshin)** — **ОСНОВА** | Полный ремейк Геншина на **Unity 6 (6000.6.0f1)**, open-world чанки через Addressables, персонаж **Nahida**, вода/постпроцессинг RenderGraph, cloth/swing | **~1.2 ГБ** (473МБ + 747МБ GI-Models) | **≤500МБ** (ASTC + Split APK) | **1-в-1 стиль Genshin, единственный с открытым миром + аниме-тян + Android CI** |
| 🥈 | [0xMartin/DoggyMan3D](https://github.com/0xMartin/DoggyMan3D) | RPG 6 уровней, 3-е лицо, Android | **3.3 ГБ** | ~400МБ | Самый большой, но герой — пёс, не аниме |
| 🥉 | [flips100/realistic-open-world](https://github.com/flips100/realistic-open-world) | Godot 4 процедурный мир 512×512, деревья, вода, fog | ~200МБ | ✅ | Реализм, не аниме — база если хочешь Godot |
| 4 | [MeekoSoup/redpanda-game](https://github.com/MeekoSoup/redpanda-game) | Платформер с **Unity-Chan + Acquire-Chan** | **1.16 ГБ** | ~150МБ | Есть тян, но мир крошечный |
| 5 | [conradosaud/ThirdPersonPack](https://github.com/conradosaud/ThirdPersonPack) | Стартер Third-Person + Mixamo | 23МБ | ✅ | Докинуть к любому миру |
| 6 | [Wafflus/unity-genshin-impact-movement-system](https://github.com/Wafflus/unity-genshin-impact-movement-system) | Реплика движения Геншина (выносливость, лазание) | <5МБ | ✅ | Добавить чувство Геншина |

### Почему `949886/Genshin` — твой выбор

- **Открытый мир:** `sparse world chunk streaming` — чанки грузятся вокруг игрока, тесты на `negative boundaries`, `stale async`, `reload safety`
- **Аниме-девушки:** `Assets/Avatar` — Nahida с facial shader, cloth, vocal, VFX. Сабмодуль `LunarDrive/GI-Models` (ветка Unity) — все аватары/анимации/зоны как в Геншине. Шейдер `GenshinCelShaderURP / HoyoToon` — cel-shading 1-в-1
- **Android из коробки:** `ProjectSettings` уже `sensorLandscape`, `Vulkan`, `com.UnityTechnologies.c`, CI `game-ci/unity-builder@v4` с `targetPlatform: Android`
- **Вес:** Базовый APK ~150МБ + AssetPacks → легко **до 500МБ** (требование выполнено)
- **Исходник >1ГБ:** После `git clone --recurse-submodules --lfs` >1.2ГБ (требование выполнено)
- **Лицензия:** MIT (модели GI — дамп miHoYo: для стора замени на VRoid/Unity-Chan + HoyoToon)

> Детальный разбор с цитатами, скринами структуры и командами клонирования — в [docs/SEARCH_REPORT.md](docs/SEARCH_REPORT.md)

---

## 📦 Структура этого репо

```
Gagaggagagabnnbggagagagagmmwmz/
├── Source/
│   ├── Genshin/              → сабмодуль 949886/Genshin (main) + GI-Models (747MB)
│   ├── LunarFramework/       → 949886/LunarFramework
│   └── ThirdPersonPack/      → conradosaud/ThirdPersonPack (доп. контроллер)
├── .github/workflows/
│   ├── android-unity.yml     → ✅ Сборка APK через Unity + Android SDK + Gradle (ОСНОВНОЙ)
│   ├── android-gradle.yml    → Сборка любого Gradle Android проекта (универсальный)
│   └── ci.yml                → Проверка YAML/структуры
├── Tools/
│   ├── setup.sh              → Скачать 1.2ГБ исходника локально
│   └── build-apk.sh          → Локальная сборка APK (требует Unity)
├── docs/
│   ├── SEARCH_REPORT.md      → Полный отчёт поиска
│   ├── ANDROID_BUILD.md      → Гайд по сборке через Actions
│   └── UNITY_LICENSE.md      → Как получить UNITY_LICENSE
└── .gitmodules / .gitattributes (LFS)
```

---

## 🚀 Как собрать APK через GitHub Actions (Android SDK + Gradle)

### Вариант А — 1 клик в браузере

1. **Запушь этот репо в свой GitHub** (уже на ветке `arena/01a0d3a7-...`)
2. Добавь секреты (если хочешь реальный Unity билд):
   - GitHub → Settings → Secrets and variables → Actions → New secret
   - `UNITY_LICENSE`, `UNITY_EMAIL`, `UNITY_PASSWORD` — инструкция: [docs/UNITY_LICENSE.md](docs/UNITY_LICENSE.md)
   - Без секретов workflow покажет `warning` и подскажет что делать; локально Unity секреты не нужны
3. GitHub → **Actions** → `Android Unity Build (Genshin-like)` → **Run workflow**
4. Дождись зелёной галочки (~30-60 мин первый раз, ~15 мин с кэшем Library) → **Artifacts** → скачай `Genshin-Android-APK` → внутри `.apk` до 500МБ

**Что ставит workflow (как ты просил):**
- `android-actions/setup-android@v3` → Android SDK 34, Build-Tools 34.0.0/33.0.1, NDK 25.1.8937393, CMake 3.22.1
- `actions/setup-java@v4` → Java 17 (требует Unity 6 + Gradle 8)
- `gradle/actions/setup-gradle@v4` → Gradle 8.7 + кэш
- `jlumbroso/free-disk-space` → чистит место под 30ГБ билда
- `actions/cache@v4` → кэш `Library/` (ускоряет в 3-5 раз)
- `game-ci/unity-builder@v4` → сам билд Android

Второй workflow `Android Gradle Build` — для чистых Gradle проектов (если экспортируешь Unity как Gradle project или делаешь `android/app`).

### Вариант Б — локально

```bash
# 1. Скачать исходник 1.2ГБ
bash Tools/setup.sh

# 2. Открыть в Unity Hub
# Unity версия: 6000.6.0f1 (f7f8ed4d1e24) + модули Android Build Support
# File → Open Project → Source/Genshin

# 3a. Через редактор: File → Build Settings → Android → Switch Platform → Build
# 3b. Через командную строку:
bash Tools/build-apk.sh
# APK появится в build/Android/GenshinAnime.apk
```

Подробно: [docs/ANDROID_BUILD.md](docs/ANDROID_BUILD.md)

---

## 🔧 Как уложиться в 500МБ

Воркфлоу уже проверяет `if [ size >500 ] → warning`. Советы:

- Player Settings → Publishing → **Split APKs by architecture** (arm64-v8a отдельно) или **Build App Bundle (AAB)**
- Texture Compression **ASTC 6x6**, Max Size 2048→1024 для LOD, Strip Engine Code ✅
- Мир через Addressables уже на чанках — выноси тяжёлые текстуры в Remote
- Audio Vorbis 70% Mono

---

## 🎨 Дальше — сделаем "обновление точек" (своя игра)

1. **Замени тян:** VRoid Studio → экспорт VRM → UniVRM + MToon / HoyoToon — визуал останется как в Геншине, но без копирайта
2. **Новые острова:** дублируй чанк в `Assets/Game` / `Assets/Modules/GI/Area` → добавь в Addressables Group
3. **Точки/квесты:** триггеры в чанках + скрипты `Assets/Scripts` + UI
4. **Шейдеры:** `GenshinCelShaderURP`, `StarRailNPRShader`, `UnityChanToonShaderVer2` — все free

Легальные модели: Unity-Chan, VRoid, AssetStore — см. [docs/SEARCH_REPORT.md](docs/SEARCH_REPORT.md#где-взять-модели-аниме-девочек-легально-и-красиво)

---

## 📄 Лицензии

- Код `949886/Genshin` — MIT
- `DoggyMan3D`, `realistic-open-world` — MIT
- Модели GI — дамп miHoYo, только для прототипа; для релиза замени

---

## ❓ FAQ

**APK больше 500МБ?** → включи Split APK / AAB, ASTC, Addressables (см. выше).  
**Хочу Godot а не Unity?** → бери `flips100/realistic-open-world` как мир + VRM тян.  
**Хочу чистый MIT без рипов?** → стартуй с `DoggyMan3D` + замени героя на Unity-Chan + UTS2.

---

*Найдено и настроено для сборки APK через GitHub Actions. Дальше — твои точки и своя игра.*
