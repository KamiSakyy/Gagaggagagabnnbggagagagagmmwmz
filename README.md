# Vortex VPN

Настоящий VPN-клиент для Android на Java + Android SDK, работающий на движке **sing-box 1.14.1**
(через официальные `libbox.aar` биндинги). Не заглушка: приложение поднимает `VpnService`,
собирает полноценный конфиг sing-box, управляет движком через gRPC-команды, показывает живую
статистику, журнал, таблицу соединений и умеет всё, что умеет sing-box.

## Возможности

**Все протоколы sing-box**

`vless` (включая Reality, XTLS-Vision, flow, uTLS-отпечатки), `vmess`, `trojan`, `trojan-go`,
`shadowsocks` (+ плагины, 2022-blake3), `shadowsocksr`, `hysteria2` (obfs salamander, port hopping,
up/down Mbps), `hysteria`, `tuic` (native/quic UDP relay), `anytls`, `naive`, `snell` v4/v5,
`shadowtls` v3, `ssh`, `wireguard`, `socks`, `http`, `tor`, `cloudflared`, `tailscale`, `openvpn`,
`openconnect` — всё, что умеет движок, плюс любые транспорты: TCP/UDP, WebSocket, gRPC, HTTP/2,
HTTPUpgrade, QUIC, mKCP-деградация с предупреждением.

**Распаковка подписок на локации**

* формат `v2ray`/`base64` (обычные подписки и подписки с `sub`/`profile`-заголовками),
* список одиночных ссылок (`vless://`, `vmess://`, `trojan://`, `ss://`, `ssr://`, `hysteria2://`,
  `tuic://`, `anytls://`, `naive+https://`, `snell://`, `ssh://`, `socks://`, `wg://`),
* Clash / Clash.Meta YAML (`proxies:`), включая `reality-opts`, `ws-opts`, `grpc-opts`, `h2-opts`,
* готовый конфиг sing-box JSON,
* определение страны по флагу в названии, ISO-коду или имени (EN/RU), группировка по локациям,
* дедупликация локаций и статистика по подписке (трафик из `subscription-userinfo`).

**Движок и сеть**

* `VpnService` + собственный TUN (стек `mixed`/`gvisor`/`system`, fallback на system при ошибке),
* авто-маршрутизация, «умный» режим (LAN/локальные адреса напрямую), глобальный режим,
  режим «только выбранные приложения»,
* DNS: раздельные «прямой»/«удалённый» резолверы, DoH, FakeIP, кеш, стратегия `prefer_ipv4`,
* url-test группа для выбора быстрейшей локации + переключение локации «на лету» через
  `CommandClient.selectOutbound`,
* защита сокетов движка (`protect`), мониторинг интерфейсов, определение владельца соединения,
* фрагментация TLS/записей, мультиплексирование, обход блокировок.

**Интерфейс**

* полностью чёрная тема Material 3 с кастомными минималистичными иконками (vector),
* кастомные вью: «дыхательная» кнопка питания (`PowerView`) и график трафика (`SpeedChartView`),
* экраны: подключение, локации (поиск/избранное/пинг), профили и подписки, редактор конфигурации
  с проверкой через `Libbox.checkConfig`, настройки, журнал движка, активные соединения,
  выбор приложений, «о приложении».

## Скачать APK

APK лежат в каталоге [`handoff`](handoff) этого репозитория (их обновляет CI при каждой сборке):

* `handoff/VortexVPN-1.0.0-arm64-v8a.apk` — для любых современных Android-устройств (рекомендуется),
* `handoff/VortexVPN-1.0.0-armeabi-v7a.apk` — для старых 32-битных устройств,
* `handoff/VortexVPN-source.zip` — исходники одним архивом.

Стабильная ссылка на сборку:

```
https://github.com/KamiSakyy/Gagaggagagabnnbggagagagagmmwmz/raw/arena/01a0d529-gagaggagagabnnbggagagagagmmwmz/handoff/VortexVPN-1.0.0-arm64-v8a.apk
```

## Сборка

Сборка идёт только через CI (`.github/workflows/android.yml`), потому что движок —
это 118 МБ нативных библиотек:

1. `libbox.aar` скачивается из релиза `singbox-android/libbox` 1.14.1 в `app/libs/`;
2. `tools/ConfigCheck.java` собирается обычным `javac` и генерирует все варианты конфигураций,
   каждый из них проверяется реальным `sing-box check` (CI скачивает бинарник движка);
3. `gradle assembleRelease` собирает ABI-сплиты, подписывает их ключом из `keystore.properties`
   (создаётся один раз в CI и коммитится, чтобы обновления ставились поверх);
4. APK и архив исходников публикуются в `handoff/` и как артефакты workflow.

Локальная сборка возможна так:

```bash
mkdir -p app/libs && curl -L -o app/libs/libbox.aar \
  https://github.com/singbox-android/libbox/releases/download/1.14.1/libbox.aar
gradle assembleRelease
```

Требования: JDK 17, Android SDK (compileSdk 35), Gradle 8.10+.

## Структура

```
app/src/main/java/com/vortex/vpn
├── App.java                  инициализация движка (Libbox.setup)
├── Prefs.java                все настройки (SharedPreferences)
├── cfg/                      сборка конфига sing-box + JSON-сериализация/парсинг
├── model/                    Outbound (все протоколы), Server, Subscription
├── sub/                      подписки: base64, ссылки, Clash YAML, страны, загрузка
├── core/                     VpnService, PlatformInterface, движок, команды, состояние
├── db/                       SQLite-хранилище профилей и локаций
└── ui/                       экраны, адаптеры, кастомные вью
tools/ConfigCheck.java        валидация конфигураций на JVM (используется в CI)
```
