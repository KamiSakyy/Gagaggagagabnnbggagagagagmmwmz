# Как получить UNITY_LICENSE для GitHub Actions

`game-ci/unity-builder` требует лицензию Unity, иначе билд упадёт.

## Вариант A — Personal (бесплатно, до ~100k$ дохода)

1. Установи Unity Hub + Unity 6000.6.0f1, залогинься.
2. На этой машине получи файл лицензии:
   ```bash
   # Linux/macOS
   cat ~/.local/share/unity3d/Unity/Unity_lic.ulf 2>/dev/null | base64 -w 0
   # Windows
   # type %APPDATA%\Unity\Unity_lic.ulf
   ```
   Или через game-ci активацию:
   - Запусти workflow `GameCI Activation` (есть в game-ci docs) — он выдаст .ulf
   - Или локально: https://game.ci/docs/github/activation

3. В GitHub репо → Settings → Secrets and variables → Actions → New repository secret
   - `UNITY_LICENSE` = содержимое Unity_lic.ulf (base64 или текст, как выдаст активатор)
   - `UNITY_EMAIL` = твой email Unity ID
   - `UNITY_PASSWORD` = пароль Unity ID

4. Запусти Actions → `Android Unity Build` → Run workflow

Подробно: https://game.ci/docs/github/activation

## Вариант B — Unity Pro/Plus серийник

Если есть серийник — можно использовать `UNITY_SERIAL` вместо `UNITY_LICENSE`.

## Проверка

В логе Actions должно быть:
```
Activated Unity ...
Build succeeded
```

Если видишь `No valid license` — проверь секреты и что версия Unity в workflow (`6000.6.0f1`) совпадает с `ProjectSettings/ProjectVersion.txt`.

## Локальная сборка без лицензии CI

Локально на своём ПК с установленным Unity лицензия не нужна — просто открой `Source/Genshin` в Unity Hub и нажми Build.

