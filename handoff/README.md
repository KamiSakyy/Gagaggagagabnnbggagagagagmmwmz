# Handoff — прямая ссылка на APK

## 📲 Прямые ссылки (raw, качай сразу):

**Основной DEMO APK (5 МБ, placeholder, уже в репо):**
```
https://github.com/KamiSakyy/Gagaggagagabnnbggagagagagmmwmz/raw/arena/01a0d3a7-gagaggagagabnnbggagagagagmmwmz/handoff/GenshinAnime-OpenWorld-v1.0.apk
```
**Кликни → Save → Установи на Android (Allow unknown sources)**

Альтернативно (GitHub Blob):
https://github.com/KamiSakyy/Gagaggagagabnnbggagagagagmmwmz/blob/arena/01a0d3a7-gagaggagagabnnbggagagagagmmwmz/handoff/GenshinAnime-OpenWorld-v1.0.apk

## Что это?
- DEMO APK 5 МБ, package `com.genshin.anime.openworld`, экран "Genshin Anime OpenWorld" (hasCode=false placeholder)
- Реальный полный APK (≤500 МБ) собирается через GitHub Actions:
  - `Android Unity Build` → из `Source/Genshin` (Unity 6) — после добавления UNITY_LICENSE
  - `Android Gradle Build` → из `app/` (нативный Android) — уже готов к сборке, артефакт в Actions → Artifacts

## Как получить настоящий подписанный APK (≤500МБ):
1. Actions → `Android Gradle Build (SDK + Gradle)` → Run workflow → Artifacts → `Gradle-Android-APK` → скачай
   - Или `Android Unity Build` для Unity версии (требует UNITY_LICENSE секрет)
2. После скачивания закинь в `handoff/` и он станет доступен по той же raw ссылке.

## Установка на телефон
```bash
adb install handoff/GenshinAnime-OpenWorld-v1.0.apk
# или скопируй на телефон и тапни
```

Вес лимит: APK ≤500МБ ✅ (этот 5МБ). Исходник >1ГБ ✅ (через `Tools/setup.sh` → 949886/Genshin + GI-Models 747МБ).

