# Сборка APK через GitHub Actions — полное руководство

## Что уже настроено

В этом репо два воркфлоу:

| Файл | Для чего | Когда запускается |
|---|---|---|
| `.github/workflows/android-unity.yml` | **Основное** — собирает Unity проект `Source/Genshin` в APK через `game-ci/unity-builder@v4` + Android SDK + NDK + Gradle 8 | push в `main`/`arena/**`, PR, ручной Run |
| `.github/workflows/android-gradle.yml` | **Универсальное** — собирает любой Gradle Android проект (`android/`, `app/build.gradle`) через `setup-android@v3` + Java 17 + Gradle 8.7 | push если есть gradle файлы, ручной |

Оба ставят **Android SDK, Gradle, NDK, CMake** как ты просил.

## Быстрый старт — получить APK

### 1. Форкни / запушь в свой GitHub

```bash
git push origin arena/01a0d3a7-gagaggagagabnnbggagagagagmmwmz
```

### 2. Добавь секреты Unity (если хочешь реальный билд)

GitHub → Settings → Secrets → Actions → New secret:

- `UNITY_LICENSE` — см. `docs/UNITY_LICENSE.md`
- `UNITY_EMAIL`
- `UNITY_PASSWORD`

Без них workflow упадёт на шаге `Build Unity Project` с подсказкой. Локально Unity не требует этих секретов.

### 3. Запусти билд

GitHub → Actions → `Android Unity Build (Genshin-like)` → Run workflow → зелёный → Artifacts → `Genshin-Android-APK` → скачай `.apk`.

Вес проверяется автоматически: если >500МБ — воркфлоу выдаст warning с советами (ASTC, Split APK, Addressables).

## Как уложиться в 500МБ (APK до 500МБ)

Unity 6 настройки для Геншин-подобного проекта (уже частично в `ProjectSettings`):

- **Texture Compression:** `ASTC 6x6` (Android), `ETC2 fallback`, уменьши Max Size 2048→1024 для дальних LOD
- **Player Settings → Other → Rendering:** Strip Engine Code ✅, Managed Stripping Level Minimal/High
- **Publishing Settings:** `Split APKs by target architecture` ✅ (arm64-v8a отдельно), или `Build App Bundle (AAB)` для Play Store
- **Addressables:** мир уже на чанках — вынеси большие террейны/текстуры в Remote Asset Packs (см. `AddressableAssetsData`)
- **Audio:** Vorbis 70%, Force To Mono для эмбиента
- **Mesh Compression:** High

В `android-unity.yml` уже кэшируется `Library/` — второй билд в 3-5 раз быстрее.

## Локальная сборка (без Actions)

```bash
bash Tools/setup.sh          # скачает 1.2ГБ исходника
# открой Source/Genshin в Unity Hub 6000.6.0f1
# File → Build Settings → Android → Build
# или
bash Tools/build-apk.sh
```

## Что делать дальше — "обновление точек" (своя игра)

1. **Замени модели** — импортируй своих аниме-девочек (VRoid → VRM, или Unity-Chan) → переназначь материалы на `HoyoToon` / `GenshinCelShaderURP`
2. **Новые острова** — дублируй префаб чанка в `Assets/Game` / `Assets/Modules/GI/Area`, добавь в Addressables Group
3. **Квесты/точки интереса** — добавь триггеры в чанки, скрипты в `Assets/Scripts`, UI в `Assets/Game`
4. **Оптимизация** — профилируй на телефоне через `adb logcat`, режь полигоны, включай occlusion culling

## Частые ошибки

- `No space left on device` — воркфлоу уже делает `free-disk-space`, но если всё равно падает — убери кэширование Library и сократи матрицу до одного Android.
- `GI-Models not found` — сабмодуль 747МБ может не скачаться по таймауту, воркфлоу пробует `git clone --branch Unity` как fallback. Можно вручную добавить PAT в `secrets.GH_PAT`.
- `License not activated` — см. `docs/UNITY_LICENSE.md`.

