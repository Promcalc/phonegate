# PhoneGate Backend — B0/B1

Реализация входящего брокера авторизации из [ТЗ](../docs/backend/tz.md).
Сервер **не отправляет SMS и не звонит**: он принимает события от телефонов-шлюзов,
сопоставляет их с заявками и отдаёт результат арендатору.

Текущий этап: **B0 + B1**. SQLite/PostgreSQL, вебхуки, retention, лимиты, HMAC
запросов, админ-API и метрики — следующие этапы (B2/B3). Для B1 используется
memory-store; данные теряются при рестарте.

## Что уже работает

- конфигурация YAML с `${ENV}` и `${ENV:-default}`, строгая проверка полей;
- структурированные JSON-логи в stdout, access log с маскированием токенов ссылок;
- `POST /exchange` с авторизацией шлюза, авторегистрацией SIM-номеров и дедупликацией;
- `POST /outgoing-report` — принимает отчёт и отвечает `204` (исходящих команд нет);
- заявки `phone_first` и `code_first`, методы `sms`, `call`, `any`;
- несколько одновременных SMS-заявок на одном номере шлюза; код уникален в пределах
  номера шлюза;
- не больше одной активной `call`/`any`-заявки на пару «телефон + номер шлюза»,
  `replace=true` отменяет конфликтующую;
- `Idempotency-Key`, TTL, отмена, повтор, список с cursor-пагинацией;
- публичный API `/api/v1/verifications`, `/api/v1/me`, `/healthz`, `/readyz`;
- ссылки `sms:`, `sms:` для iOS, `tel:`, публичная страница и PNG QR;
- HMAC-подписанный одноразовый токен страницы, `Cache-Control: no-store`,
  `Referrer-Policy: no-referrer`, CSP; токен не пишется в логи;
- фоновое истечение заявок и проверка TTL при polling.

Страница и QR не отправляют SMS и не звонят сами — пользователь нажимает кнопку
«Отправить SMS» или «Позвонить».

## Быстрый запуск

Нужен Go 1.25+.

```bash
cd backend
go run ./cmd/server -config config.example.yaml
```

Проверка конфигурации без запуска:

```bash
go run ./cmd/server -config config.example.yaml -check
```

В dev-конфиге:

- API-ключ арендатора: `test-tenant-key`;
- токен шлюза: `test-gateway-token`;
- номер шлюза: `+79005550001`;
- арендатор, пул и шлюз — `demo` / `demo` / `gw_demo_1`.

Токены в конфиге хранятся только как SHA-256. Хэш нового секрета:

```bash
printf '%s' 'my-secret' | sha256sum
```

### Регистрация номера шлюза

Приложение само присылает номера SIM в ключах верхнего уровня `/exchange`. Первый
пустой обмен регистрирует номер. Номера, перечисленные в `pools.*.numbers`, считаются
заранее одобренными (`active`). Незнакомые номера при `require_approval: true`
попадают в `pending`; админ-API для одобрения появится в B3. В dev-конфиге
`require_approval: false`.

```bash
curl -i -X POST http://localhost:8080/exchange \
  -H 'Authorization: Bearer test-gateway-token' \
  -H 'Content-Type: application/json' \
  -d '{"sync_id":"init","+79005550001":{"sms":[],"call":[]}}'
# HTTP/1.1 204 No Content
```

### Создание заявки

```bash
curl -s -X POST http://localhost:8080/api/v1/verifications \
  -H 'Authorization: Bearer test-tenant-key' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"phone":"+79001234567","method":"sms","purpose":"login"}'
```

В ответе будут `id`, `code`, `instructions`, `gateway_number` и `links`. Код и ссылки
возвращаются **только** при создании/повторе: в `GET` их нет, потому что они содержат
одноразовый секрет.

### Имитация входящего SMS

Подставьте `code` из ответа и текущее время:

```bash
curl -i -X POST http://localhost:8080/exchange \
  -H 'Authorization: Bearer test-gateway-token' \
  -H 'Content-Type: application/json' \
  -d '{"sync_id":"sms-1","+79005550001":{"sms":[{"time":"2026-10-26T12:00:00Z","text":"Код: 482913","phone":"+79001234567"}]}}'
```

Затем проверьте статус:

```bash
curl -s http://localhost:8080/api/v1/verifications/vrf_... \
  -H 'Authorization: Bearer test-tenant-key'
```

`status` станет `verified`, `phone` заполнится (для `code_first` — номером отправителя).
Публичная страница из `links.page_url` обновляет статус polling-запросом раз в 3 секунды.

### Code-first для Telegram

```bash
curl -s -X POST http://localhost:8080/api/v1/verifications \
  -H 'Authorization: Bearer test-tenant-key' \
  -H 'Content-Type: application/json' \
  -d '{"mode":"code_first","method":"sms","purpose":"telegram_bind","metadata":{"telegram_user_id":123456789}}'
```

`phone` в запросе не передаётся. Пользователь отправляет показанный код на номер
шлюза; бэкенд возвращает номер отправителя в `phone`. Бот обязан дождаться
`verified` (вебхук или polling) и сверить `metadata.telegram_user_id`, а не верить
сообщению «я отправил».

## Конфигурация

Полный production-пример: [../docs/backend/config.example.yaml](../docs/backend/config.example.yaml).
Локальный пример: [config.example.yaml](config.example.yaml).

Важные поля:

| Поле | Смысл |
|---|---|
| `server.public_url` | внешний HTTPS-URL для `page_url` и QR |
| `storage.driver` | в B1 только `memory`; `sqlite`/`postgres` зарезервированы |
| `gateway.require_approval` | незнакомые номера: `pending` или сразу `active` |
| `gateway.offline_after` | шлюз считается offline, если не было обмена |
| `gateway.validate_peer_numbers` | проверять номера отправителей; `false` — сохранять невалидные в журнал |
| `logging.log_gateway_payload` | писать сырой JSON `/exchange` в лог (только для отладки) |
| `verification.sticky` | переиспользовать номер существующей заявки телефона |
| `verification.selection` | `least_loaded`, `round_robin`, `random` |
| `verification.max_active_sms_per_phone` | лимит одновременных SMS-заявок |
| `verification.incoming_codes_per_phone_per_minute` | SMS с кодами от одного отправителя за минуту, по умолчанию 10; положительное число |
| `verification.code_first.*` | TTL, формат и длина code-first кода |
| `verification.links.secret` | HMAC-секрет публичных ссылок, не меньше 32 байт |
| `storage.retention.*` | описано для B2, в memory-store не применяется |

`${VAR}` подставляется из окружения. `${VAR:-default}` использует значение по
умолчанию. Секреты не следует хранить в репозитории.

### Проверка входящего обмена

- заголовок авторизации задаётся `gateway.auth_headers` (по умолчанию
  `Authorization: Bearer <gateway_token>`);
- ключи верхнего уровня — номера SIM; `sync_id` обязателен;
- SMS-событие дедуплицируется по шлюзу, номеру, отправителю, времени, направлению и
  хэшу текста; повторная отправка того же окна не меняет статус;
- событие вне `verification.clock_skew` сохраняется, но не матчится;
- SMS с кодами ограничиваются по нормализованному номеру отправителя, суммарно
  для всех шлюзов и tenants. Считаются верные и неверные коды: одна уникальная SMS —
  одна попытка. Повторная доставка события не расходует лимит. Используется точное
  скользящее окно: не более заданного числа попыток за любые последние 60 секунд
  по времени получения на сервере. Превышение
  сохраняется в журнале, но не проверяется и не меняет заявку; отложенной обработки
  нет. Новые SMS принимаются, когда самые старые учтённые попытки выходят из окна;
  отклонённые попытки не продлевают блокировку. История попыток хранится в памяти
  процесса и сбрасывается при перезапуске; на звонки этот лимит не распространяется;
- SMS от чужого номера не подтверждает `phone_first`; после `max_attempts` заявка
  переходит в `failed`;
- для `call`/`any` звонок сопоставляется по номеру звонящего; `missed` и `in`
  обрабатываются согласно `verification.call.*`.

`ratelimit.Limiter` содержит настройки и общий алгоритм скользящего окна.
Хранилище внедряется через интерфейс `ratelimit.Storage` с атомарным `Update`.
Сейчас используется мапа под mutex; хранилище на Redis можно подключить при сборке
сервиса в `cmd/server/main.go`, сохранив тот же алгоритм. `Update` может повторять
callback при конфликте транзакции, например через Redis WATCH/MULTI.
Лимит и размер окна задаются в конструкторе limiter; `Check` принимает только
контекст, ключ и время попытки. При каждом запросе история номера очищается от
попыток старше окна: граница ищется шагами 1, 2, 4, 8, затем бинарным поиском
в найденном диапазоне. Активные попытки копируются в новый массив при удалении
устаревших, чтобы старый массив мог освободить GC.

### Диагностика обмена

Если в логах есть `event.invalid_peer` или `gateway.invalid_number`, включите
`logging.log_gateway_payload: true` — перед разбором в лог попадёт поле `exchange.payload`
с сырым JSON от Android. Запись содержит номера и тексты SMS, поэтому используйте её
только при отладке.

Предупреждения `event.invalid_peer` и `gateway.invalid_number` теперь содержат
`raw_phone`/`raw_number`: видно, что именно прислало приложение (например, буквенное
имя отправителя). Если такие события нужно сохранять в журнале, а не отбрасывать,
поставьте `gateway.validate_peer_numbers: false`. Они не будут подтверждать заявки:
для матчинга по-прежнему нужен E.164.

`event.outside_window` содержит `event_time` (время из отчёта) и `server_time`
(время сервера): по ним видно расхождение часов шлюза и `verification.clock_skew`.

В Android-приложении есть настройка «Не отправлять номера не в формате E.164»:
она отбрасывает такие записи ещё на телефоне (курсор при этом сдвигается).

## Безопасность B1

- Коды и токены не логируются; телефоны в логах маскируются.
- Код хранится только как SHA-256, сравнение constant-time.
- Публичный токен — HMAC-SHA256, TTL равен TTL заявки; отменённая/истёкшая ссылка
  отдаёт `410`.
- `Idempotency-Key` привязан к арендатору; повтор с другим телом — `409`.
- Ответы API и страницы идут с `no-store`; страница — с CSP и `no-referrer`.
- `X-Forwarded-For` учитывается только от `server.trusted_proxies`.
- Ссылки действительны до TTL, поэтому их нельзя пересылать; бот/сервис обязан
  сверять пользователя перед привязкой номера.

Ограничения B1: нет распределённых лимитов и anti-enumeration, HMAC-подписи
входящих API-запросов, SSRF-проверки вебхуков и метрик — это B3. Нет вебхуков и
истории — это B2; результат доступен polling-запросом.

## Тесты

```bash
cd backend
go test ./...
go vet ./...
```

Покрыты: нормализация телефонов, генерация/извлечение кодов, HMAC-ссылки, SMS,
несколько SMS на одном номере, code-first, звонок и конфликт `call`, идемпотентность,
истечение, дедупликация, отбрасывание/сохранение невалидных номеров, публичная
страница/QR и HTTP-авторизация.

## Сборка

```bash
cd backend
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o phonegate-server ./cmd/server
```

Один статический бинарник; конфигурация передаётся через `-config`. Для Linux
рекомендуется systemd или контейнер с reverse proxy, который завершает TLS и
передаёт `X-Forwarded-Proto: https`.
