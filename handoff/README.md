# handoff — готовый APK

В этой папке CI автоматически публикует подписанный APK и архив исходников.

| Файл | Для кого |
| --- | --- |
| `VortexVPN-1.0.0-arm64-v8a.apk` | современные телефоны и планшеты (99% устройств) — **рекомендуется** |
| `VortexVPN-1.0.0-armeabi-v7a.apk` | старые 32-битные устройства |
| `VortexVPN-source.zip` | исходники (Java, Gradle, CI) одним архивом |

## Установка

1. Скачайте APK по прямой ссылке:

   - arm64-v8a (рекомендуется): https://github.com/KamiSakyy/Gagaggagagabnnbggagagagagmmwmz/raw/arena/01a0d529-gagaggagagabnnbggagagagagmmwmz/handoff/VortexVPN-1.0.0-arm64-v8a.apk
   - armeabi-v7a (старые 32-битные): https://github.com/KamiSakyy/Gagaggagagabnnbggagagagagmmwmz/raw/arena/01a0d529-gagaggagagabnnbggagagagagmmwmz/handoff/VortexVPN-1.0.0-armeabi-v7a.apk
   - исходники: https://github.com/KamiSakyy/Gagaggagagabnnbggagagagagmmwmz/raw/arena/01a0d529-gagaggagagabnnbggagagagagmmwmz/handoff/VortexVPN-source.zip
2. Разрешите установку из неизвестных источников, если Android попросит.
3. Установите и откройте приложение, выдайте разрешение на VPN.
4. Добавьте подписку или ссылку: **Профили → +** (можно вставить ссылку из буфера обмена).
5. Выберите локацию: **Локации** → нажмите нужный сервер.
6. Нажмите большую кнопку на главном экране.

## Подпись

APK подписан самоподписанным ключом, который CI создаёт один раз и коммитит в репозиторий
(`keystore.jks`, пароль `vortexvpn`, алиас `vortex`). Благодаря этому новые сборки
устанавливаются поверх старых без удаления приложения.

## Что внутри

Движок: sing-box 1.14.1 (libbox.aar, реальный Go-движок внутри `lib/arm64-v8a/libbox.so`).
Язык: Java. UI: Material 3, чёрная тема, кастомные иконки. Подписки: v2ray/base64, Clash YAML,
одиночные ссылки, конфиг sing-box JSON.

Протоколы: vless (+reality/ws/grpc/httpupgrade), vmess, trojan, shadowsocks (включая 2022),
shadowtls, anytls, hysteria2, tuic, snell, ssh, socks, http, naive, wireguard.

## Проверено

* Каждая сборка прогоняет 12 сгенерированных конфигов (mode × stack × профиль) через настоящий
  sing-box 1.14.2: `failed=0`, то есть ни один конфиг не отклоняется движком.
* В APK внутри лежит `lib/arm64-v8a/libbox.so` (~81 МБ в распакованном виде) — это настоящий движок,
  а не заглушка.
* Протоколы, удалённые из sing-box (ShadowsocksR, удалён в 1.6), не попадают в конфиг: при импорте
  такие локации помечаются как «не поддерживается движком» и просто пропускаются, приложение не падает.
