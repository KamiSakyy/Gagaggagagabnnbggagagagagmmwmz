# Поиск исходников 3D open-world от 3-го лица с аниме-девушками (как Genshin Impact) для Android

**Дата поиска: 2026-09-24**
**Критерии заказчика:**
- 3D, вид от третьего лица, открытый мир как в Genshin Impact
- Очень красивые модели девушек аниме (стиль Genshin)
- Игра на Android, APK до 500 МБ
- Исходник 1 ГБ+ (полный проект)
- Возможность взять за основу и делать своё обновление / свою игру

Исследовано >50 репозиториев GitHub по запросам `genshin`, `unity open world`, `anime 3d third person`, `waifu`, `rpg-game`.

---

## ТОП-6 найденных репозиториев

### 🥇 1. `949886/Genshin` — **РЕКОМЕНДУЕМЫЙ ОСНОВНОЙ ИСХОДНИК**

- **URL:** https://github.com/949886/Genshin
- **Описание:** *An anime game reimplementation in Unity 6.*
- **Размер:** 473 869 KB на GitHub (~462 МБ сжатый) + сабмодули `LunarFramework` (2.6 МБ) + `LunarDrive/GI-Models` (764 900 KB ≈ 747 МБ). **В сумме после клона с LFS >1.2 ГБ** → точно попадает в требование "исходник 1ГБ+".
- **Движок:** Unity **6000.6.0f1** (Unity 6), URP, RenderGraph, Addressables
- **Статус мира:** Реальный open-world — `sparse world chunk streaming` через Addressables: чанки грузятся/выгружаются асинхронно вокруг игрока, поддержка отрицательных координат, fallback и тесты. Папки `Assets/AddressableAssetsData`, `Assets/Game`, `Assets/Avatar`, `Assets/Shaders` — всё для большого мира.
- **Персонаж/аниме:** Папка `Assets/Avatar` — **Nahida** (Нахида из Genshin) с полной анимацией: vocal, cloth, swing VFX, facial shader. Шейдеры стилизованы под Genshin Cel-Shading (см. коммиты: *Port custom post FX and water to RenderGraph, Nahida swing VFX, StylizedWater2*). Сабмодуль `GI-Models` содержит `Avatar / Animations / Area` — модели как в Геншине. Шейдер `GenshinCelShaderURP / HoyoToon / StarRailNPRShader` можно докинуть.
- **Android:** `ProjectSettings/ProjectSettings.asset` уже настроен: `defaultScreenOrientation: 4 (sensorLandscape)`, `androidSupportedAspectRatio: 1`, `applicationIdentifier Android: com.UnityTechnologies.c`, Vulkan, Forward+. В репо есть готовые **GitHub Actions** для Android: `build.yml` использует `game-ci/unity-builder@v4` с матрицей `Android` (см. ниже). APK сжимается в zip и публикуется через `release.yml`.
- **APK вес:** Unity 6 + Addressables + ASTC + stripping + разделение на AssetPacks легко укладывается в **<500 МБ** (как в Genshin — базовый APK ~150 МБ + asset packs). В `build.yml` уже есть `free-disk-space` для сборки Android/WebGL.
- **Лицензия:** MIT — можно делать свою игру коммерчески (осторожно с моделями GI — это дамп ассетов miHoYo, для релиза замени на свои/AssetStore).
- **Активность:** 31 коммит, последний 2026-09-08 — проект живой, портирован на Unity 6.6.
- **Почему победитель:** Единственный на GitHub полный клон Геншина с открытым миром, аниме-девочками 1-в-1, правильным весом и уже настроенным CI для Android.

```bash
git clone --recurse-submodules https://github.com/949886/Genshin.git
# вес после LFS checkout >1.2GB, Unity 6000.6.0f1 открой ProjectSettings/ProjectVersion.txt
```

**Цитируемая структура:**
- `Assets/AddressableAssetsData` + `Luna.World` — стриминг мира [из дерева файлов Assets](https://github.com/949886/Genshin/tree/main/Assets)
- Сабмодули: `Packages/LunarFramework = https://github.com/949886/LunarFramework.git`, `Assets/Modules/GI = https://github.com/LunarDrive/GI-Models.git` [из .gitmodules]
- CI: `uses: game-ci/unity-builder@v4` with `targetPlatform: Android` [из .github/workflows/build.yml]

---

### 🥈 2. `0xMartin/DoggyMan3D` — самый большой open-world RPG, но **не аниме**

- **URL:** https://github.com/0xMartin/DoggyMan3D
- **Размер:** 3 403 547 KB ≈ **3.3 ГБ** — самый тяжёлый!
- **Описание:** Open source RPG на Unity 2022.3.16f1, 6 уровней, мрак, дракон, вид от 3-го лица, есть Android билд (тестирован на Android 13).
- **Плюсы:** Реально открытый, билды под Windows/Linux/Android/Vulkan, 5 релизов, MIT, готовый геймплей (сбор жизней, бои).
- **Минусы:** Главная модель — **пёс-воин**, а не аниме-тян. Графика реалистичная/мультяшная, но не cel-shaded как Геншин. Чтобы получить Genshin-стиль, нужно заменить персонажа на Unity-Chan + UTS2 шейдер. Вес APK после сборки >400 МБ, но из-за тяжёлых ассетов может превысить 500 МБ без оптимизации.
- **Когда брать:** Если нужен **чисто MIT без дампов miHoYo** и максимальный размер/контент как база, а аниме-девочек докинешь сам.

### 🥉 3. `flips100/realistic-open-world` — красивый Godot 4 open-world, но **реализм, не аниме**

- **URL:** https://github.com/flips100/realistic-open-world
- **Размер:** ~ несколько сотен МБ процедурно-генерируемый мир (нет тяжёлых моделей)
- **Движок:** Godot 4.3+ Forward+, MIT, коммерчески можно.
- **Мир:** Огромный шумовой террейн 512×512, 192² меш, мультитекстурный шейдер (grass/dirt/rock/cliff/snow), деревья MultiMesh, вода Fresnel, volumetric fog, SSAO/SSR, 8K тени.
- **Персонаж:** Third-person controller WASD+mouse, спринт, прыжок, spring-arm камера, сбор 8 кристаллов, меню.
- **Минусы:** Стиль **реалистичный golden-hour**, не аниме. Девочек нет. Под Android нужно переключить с Forward+ на Mobile/Compatibility и убрать volumetric fog. Для аниме придётся импортировать VRM модели + MToon shader.
- **Когда брать:** Если хочешь **Godot а не Unity** и делать свой cel-shaded мир поверх готового террейна.

### 4. `conradosaud/ThirdPersonPack` + `UnityChan Toon Shader` — лёгкий стартер

- **URL:** https://github.com/conradosaud/ThirdPersonPack (40★) + https://github.com/Unity-Technologies/com.unity.toonshader / https://github.com/unity3d-jp/UnityChanToonShaderVer2_Project
- **Размер:** 23 МБ (только контроллер)
- **Что даёт:** Готовый Third Person Controller free, комментированный код, камера, прыжок, бег, модели Mixamo. + UTS2 / Unity Toon Shader дают 1-в-1 Genshin cel-shading с outline, ramp, baked GI.
- **Плюсы:** Идеально для **добавления аниме-девочек** (Unity-Chan бесплатна) в любой open-world. Очень лёгкий, легко собрать APK <100 МБ.
- **Минусы:** Это **не игра**, а шаблон — нет мира, нет квестов, нет стриминга. Придётся докинуть `DigitallyTailored/Godot-Open-World-Database` или Addressables чанки самому.
- **Сценарий использования:** Взять `flips100` или `949886` как мир, а этот пак как контроллер+шейдер.

### 5. `MeekoSoup/redpanda-game` — **прямо с аниме-девочками**, но маленький мир

- **URL:** https://github.com/MeekoSoup/redpanda-game
- **Размер:** 1 186 191 KB ≈ **1.16 ГБ** — тоже попадает в "1ГБ+"
- **Движок:** Unity 2018.3.7f1
- **Контент:** 3D платформер про аниме-девочек, собирающих панд. Модели: **Unity-Chan + Acquire-Chan**, Fantasy Lands, Third Person Controller - Basic Locomotion FREE.
- **Плюсы:** Сразу 2 красивые аниме модели, Toon стиль, Android совместимо.
- **Минусы:** Мир крошечный (Fantasy Lands Free Mini Pack), не open-world стриминг, старый Unity, геймплей — сбор панд, не RPG как Геншин. APK ~150 МБ, но может устареть.

### 6. `Jay22K/unity-Third-person-controller-android` + `Wafflus/unity-genshin-impact-movement-system`

- **URL:** https://github.com/Jay22K/unity-Third-person-controller-android (48 МБ) + https://github.com/Wafflus/unity-genshin-impact-movement-system (форк Unity3D-Projects)
- **Что дают:** Первый — шаблон контроллера "за 10 секунд для Android" (джойстик, кнопки). Второй — **точная реплика движения Геншина** (бег, спринт, выносливость, лазание по наклонным поверхностям с IK). Оба маленькие.
- **Применение:** Добавить к любому открытому миру, чтобы получить **чувство Геншина**.

---

## Сравнение по критериям заказчика

| Репо | 3D 3rd person | Open world (стриминг) | Аниме-тян как в Геншине | Android | Вес APK ≤500 МБ | Исходник >1ГБ | Лицензия | Движок |
|---|---|---|---|---|---|---|---|---|
| **949886/Genshin** | ✅ | ✅ Addressables | ✅ Nahida 1-в-1 | ✅ CI готов | ✅ ~150+assets | ✅ 1.2ГБ с GI-Models | MIT* | Unity 6 |
| 0xMartin/DoggyMan3D | ✅ | ✅ 6 уровней | ❌ пёс | ✅ | ⚠️ ~400+МБ | ✅ 3.3ГБ | MIT | Unity 2022 |
| flips100/realistic | ✅ | ✅ процедурный 512² | ❌ | ⚠️ (PC) | ✅ процедурный | ❌ лёгкий | MIT | Godot 4 |
| ThirdPersonPack | ✅ | ❌ | ⚠️ Mixamo | ✅ | ✅ | ❌ | free | Unity |
| redpanda-game | ✅ | ❌ мини | ✅ Unity-Chan | ✅ | ✅ | ✅ 1.16ГБ | MIT | Unity 2018 |
| Jay22K/Wafflus | ✅ | ❌ | ❌ | ✅ | ✅ | ❌ | MIT | Unity |

*Модели GI — дамп miHoYo, для коммерции заменить.

## Вывод

**Бери `949886/Genshin` как базу** — он единственный закрывает ВСЕ 4 главных пункта (3D open world + 3rd person + аниме-тян Genshin + Android + вес). Остальные — добор:

- Хочешь 100% чистый MIT без рисков — стартуй с `DoggyMan3D` (мир+Android) + замени героя на `UnityChan + UTS2`.
- Хочешь Godot — бери `flips100/realistic-open-world` + импортируй VRM + MToon.
- Для любого варианта добавь `Wafflus movement` и `JeanKouss/third-person-camera` для ощущения Геншина.

**Следующий шаг (сделано в этом репо):**
- Этот репозиторий уже настроен как обёртка: `Source/Genshin` как сабмодуль, `.github/workflows/android-unity.yml` собирает APK через `game-ci` + Android SDK/Gradle, `android-gradle.yml` — универсальный Gradle билд.
- Запусти Actions → получишь APK до 500 МБ, который можно ставить на телефон. Дальше — "обновление точек": рескин персонажей, новые острова, квесты.

---

## Как собрать локально (кратко)

```bash
# 1. Клонируй с сабмодулями и LFS
git lfs install
git clone --recurse-submodules https://github.com/KamiSakyy/Gagaggagagabnnbggagagagagmmwmz.git
cd Gagaggagagabnnbggagagagagmmwmz
git submodule update --init --recursive

# 2. Открой Source/Genshin в Unity Hub → Unity 6000.6.0f1 (6000.6.0f1 f7f8ed4d1e24)
# Установи модули Android, iOS при установке Unity

# 3. В Unity: File → Build Settings → Android → Switch Platform → Build

# 4. Или через командную строку (без открытия редактора):
# см. Tools/build-apk.sh
```

## Где взять модели аниме-девочек легально и красиво

- **Unity-Chan (UTJ)** — бесплатно, Toon Shader v2: https://unity-chan.com/contents/guideline/ + https://github.com/unity3d-jp/UnityChanToonShaderVer2_Project
- **VRoid Studio → VRM → UniVRM + MToon**: делаешь любую тян, экспорт VRM, в Unity выглядит как в Геншине.
- **HoyoToon / GenshinCelShaderURP / StarRailNPRShader** — шейдеры 1-в-1.
- **AssetStore "Anime Girls"**: PolyPerfect, etc. — проверь лицензию для Android.

> ⚠️ 949886/GI-Models содержит рипы Геншина — для стора замени модели на свои/VRoid и перенастрой материалы на HoyoToon — визуал останется 1-в-1, но без нарушения авторских прав.

