# qWDTT — недостатки кодовой базы

Аудит от 2026-08-28, коммит `6c2f7a6` (Release 1.4.2, versionCode 40).
Объём: ~37k строк (Kotlin ~20k, Go ~10k, bash ~0.8k).

Порядок — по убыванию важности.

---

## 1. Ни одного теста

**Где:** во всём репозитории нет ни одного `*_test.go` или файла в `app/src/test` / `app/src/androidTest`
(при том что `testInstrumentationRunner` в `app/build.gradle.kts` объявлен).

**Почему важно:** непокрытым остаётся то, где ошибка тише всего и дороже всего:

- `go_client/obfs.go` — построение AEAD-nonce из RTP-полей (SSRC+SeqNum+Timestamp),
  replay-окно (`replayWindowSpan`, 8192 записей), padding;
- `go_client/protocol.go` — парсинг `RAWCONF:ip|dns|mtu`, ветки `DENIED:*`;
- `server/nat.go`, `server/raw.go` — NAT и маршрутизация raw-IP;
- `server/admin_api.go` — авторизация admin API.

Молчаливая деградация обфускации (например, повтор nonce или сломанное replay-окно)
не проявится как падение — только как потерянная незаметность или дыра в шифровании.

**Что сделать:** начать с чистых функций без сети — `obfs.go` (wrap→unwrap round-trip,
отклонение повтора, приём legacy-24-байтного варианта) и `protocol.go` (таблица
корректных и битых ответов сервера).

---

## 2. Сборка невоспроизводима — риск цепочки поставок

**Где:**
- `.gitignore:16` — `go.sum` в игноре;
- `go_client/go.sum` в репозитории отсутствует (корневой `go.sum` при этом отслеживается);
- `scripts/build-go-lib.sh:76` — `go mod tidy -e` выполняется **перед каждой сборкой**.

**Почему важно:** APK, который подписывается релизным ключом и раздаётся пользователям
как VPN, собирается из набора зависимостей, который никем не зафиксирован и не проверен
по контрольным суммам. Две сборки одного тега могут дать разные бинарники. Для проекта,
где весь смысл — доверие к клиенту, это самый серьёзный структурный недостаток после
отсутствия тестов.

**Что сделать:** убрать `go.sum` из `.gitignore`, закоммитить `go_client/go.sum`,
из `build-go-lib.sh` убрать `go mod tidy` (или заменить на `go mod verify`),
собирать с `-mod=readonly`.

---

## 3. CI ссылается на несуществующий файл

**Где:** `.github/workflows/android-release.yml` и `android-debug.yml`, шаг `actions/setup-go`:

```yaml
cache-dependency-path: |
  go.sum
  go_client/go.sum
```

`go_client/go.sum` не существует (см. п. 2). `setup-go` при неразрешённом пути выдаёт
ошибку «Some specified paths were not resolved».

**Что сделать:** решается вместе с п. 2 — после коммита `go_client/go.sum` путь станет валидным.

---

## 4. Два Go-модуля с одинаковым именем и разъезжающимися зависимостями

**Где:** `go.mod` (корень) и `go_client/go.mod` — оба объявлены как `module wg-turn-client`.

Корневой модуль фактически собирает **сервер** (`app/build.gradle.kts`, задача
`buildServerAsset` → `go build ./server`), но называется как клиент.

| | корень (сервер) | `go_client/` |
|---|---|---|
| go | 1.25.0 | 1.26 |
| `golang.org/x/crypto` | v0.51.0 | v0.54.0 |
| `golang.org/x/net` | v0.53.0 | v0.57.0 |
| `golang.zx2c4.com/wireguard` | 2025-05-21 | 2026-05-22 |

**Почему важно:** клиент и сервер говорят по одному протоколу и делят логику обфускации,
но собираются против разных версий криптобиблиотек. Одинаковое имя модуля вдобавок делает
невозможным нормальный `go.work` и путает при чтении.

**Что сделать:** переименовать корневой модуль в `wdtt-server` (или вынести сервер
в `server/go.mod`), выровнять версии общих зависимостей, добавить `go.work`.

---

## 5. `usesCleartextTraffic="true"` глобально

**Где:** `app/src/main/AndroidManifest.xml`, атрибут `<application>`.

Разрешает открытый HTTP всему приложению целиком, включая WebView авторизации VK
и клиент обновлений.

**Что сделать:** заменить на `network-security-config` с точечным исключением
для тех хостов, которым это действительно нужно (локальный admin API по IP и т.п.).

---

## 6. Самообновление не проверяет целостность APK

**Где:** `app/src/main/java/com/wdtt/client/update/AppUpdate.kt:450` — скачанный APK
отдаётся системному установщику через `FileProvider` без проверки хеша или подписи.
В связке с `REQUEST_INSTALL_PACKAGES` в манифесте.

**Смягчающее обстоятельство:** Android сам не даст установить обновление, подписанное
другим ключом, так что подмена APK на произвольный не проходит. Загрузка идёт по HTTPS
к `api.github.com`.

**Что сделать:** публиковать SHA-256 в теле релиза и сверять после загрузки —
это несколько строк, но закрывает порчу файла при скачивании и делает поведение явным.

---

## 7. Монолитные файлы

| Файл | Строк |
|---|---|
| `app/src/main/java/com/wdtt/client/ui/settings/SettingsTab.kt` | 3520 |
| `app/src/main/java/com/wdtt/client/ui/profiles/ProfilesTab.kt` | 2285 |
| `app/src/main/java/com/wdtt/client/tunnel/TunnelManager.kt` | 2160 |
| `app/src/main/java/com/wdtt/client/ui/deploy/DeployTab.kt` | 1801 |
| `app/src/main/java/com/wdtt/client/vk/VkAuthWebViewManager.kt` | 1556 |
| `server/database_bot.go` | 1496 |
| `app/src/main/java/com/wdtt/client/ui/exceptions/ExceptionsTab.kt` | 1094 |

Отдельно: `TunnelManager` — это `object`-синглтон (`TunnelManager.kt:38`) с большим
объёмом мутабельного состояния (флаги подключения, watchdog, пайплайн, статистика).
Именно из-за этого его невозможно покрыть тестами, не подняв половину приложения, —
п. 1 и п. 7 связаны.

**Что сделать:** для `TunnelManager` — выделить чистую логику состояния (машина состояний
подключения, решения watchdog) в тестируемые классы, оставив в синглтоне только
взаимодействие с Android. UI-файлы делить по экранам/секциям.

---

## 8. Устаревший target SDK

**Где:** `app/build.gradle.kts` — `compileSdk = 35`, `targetSdk = 35` при AGP 9.0.1.

Для раздачи через GitHub Releases не блокер (требования Google Play не применяются),
но это отставание примерно на год, и часть новых ограничений платформы
на фоновые сервисы и VPN не проверена.

---

## Что при проверке оказалось в порядке

Чтобы список выше читался в контексте:

- хардкод-секретов в коде нет; `local.properties`, keystore, `.pem`/`.jks` в историю git не попадали;
- admin API сравнивает токен constant-time по SHA-256 (`server/admin_api.go:60`),
  клиент проверяет pinned-сертификат (`app/.../servers/AdminApiClient.kt:50`);
- секреты передаются серверу через `--*-file`, а не в argv — не видны в `ps`;
- секреты в приложении шифруются AES/GCM в AndroidKeystore (`security/SecureStringStore.kt`);
- релизный workflow проверяет, что тег — потомок `master` и что `versionName` совпадает с тегом;
- обфускация написана вдумчиво: бюджет MTU расписан по байтам (`server/core.go:11-20`),
  keepalive сделан случайного размера 25–44 байта именно против DPI-профилирования
  (`go_client/session.go:34-39`), размеры буферов выведены из BDP (`go_client/dispatcher.go:34-40`).
