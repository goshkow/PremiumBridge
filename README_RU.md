[English version](README.md)

# PremiumBridge

PremiumBridge — это плагин для Paper 1.21.x оффлайн-серверов, который распознаёт настоящие premium-входы, автоматически авторизует их через поддерживаемые auth-плагины и оставляет пиратские входы на обычном сценарии логина и регистрации.

## Возможности

- Проверка premium-сессии на оффлайн-сервере через ProtocolLib и Mojang session checks
- Автоматический логин для подтверждённых premium-игроков
- Опциональная авто-регистрация premium-игроков случайным паролем
- Опциональная защита ников, которые уже были привязаны к premium-аккаунтам
- Бесшовное скрытие служебных сообщений auth-плагина для premium-игроков
- Перенос данных из offline UUID в premium UUID
- Новые режимы миграции:
  - `disabled`
  - `automatic`
  - `ask-player`
- Публичный API для других плагинов
- Интеграция с AddHeads
- Встроенные файлы локализации

## Поддерживаемые auth-плагины

- AuthMe
- OpeNLogin

`xAuth` специально не заявлен как поддерживаемый для современного Paper 1.21.x.

## Требования

- Paper 1.21.x
- ProtocolLib
- Один из поддерживаемых auth-плагинов

## Установка

1. Установи `ProtocolLib`.
2. Установи `AuthMe` или `OpeNLogin`.
3. Положи `PremiumBridge.jar` в папку `plugins/`.
4. Один раз запусти сервер.
5. При необходимости отредактируй `plugins/PremiumBridge/config.yml`.
6. Перезапусти сервер или выполни `/premauthbridge reload`.

## Локализации

При первом запуске в `plugins/PremiumBridge/languages/` будут созданы файлы:

- `en_US`
- `ru_RU`
- `de_DE`
- `fr_FR`
- `pl_PL`
- `uk_UA`
- `es_ES`
- `it_IT`

Настройка языка:

```yml
language:
  default: "ru_RU"
  auto-detect-client-locale: true
```

## Режимы миграции

```yml
migration:
  offline-data:
    enabled: true
    mode: "ask-player"
```

- `disabled`: полностью отключает перенос offline-данных
- `automatic`: переносит данные автоматически до полного входа premium-игрока
- `ask-player`: после premium-автологина показывает в чате кнопки `YES/NO`

Если игрок вручную подтверждает перенос, PremiumBridge просит его один раз перезайти. На следующем входе перенос применяется ещё до полной загрузки игрока, поэтому инвентарь, позиция, статистика и достижения восстанавливаются надёжнее.

## API

PremiumBridge регистрирует Bukkit service и даёт статический helper:

```java
import goshkow.premlogin.api.PremiumBridge;
import goshkow.premlogin.api.PremiumBridgeApi;
import goshkow.premlogin.api.PremiumStatus;

PremiumBridgeApi api = PremiumBridge.getApi();
if (api != null) {
    PremiumStatus status = api.getPremiumStatus(player);
    if (status.isSecure()) {
        // подтверждённая premium-сессия
    }
}
```

API также позволяет:

- узнать активный auth-провайдер
- определить secure/insecure premium-статус
- получить список известных premium-ников
- получить связку offline UUID и premium UUID
- проверить состояние миграции
- узнать режим регистрации premium-игроков

## Интеграция с AddHeads

PremiumBridge умеет интегрироваться с [AddHeads](https://modrinth.com/plugin/addheads), чтобы у подтверждённых premium-игроков не дублировались головы в табе.

Как это работает:

- PremiumBridge автоматически выдаёт право `addhead.premium` подтверждённым premium-игрокам, если интеграция включена
- это нужно для режима, в котором AddHeads определяет premium-игроков по праву
- при первом создании конфига PremiumBridge автоматически включает эту интеграцию, если `AddHeads` уже установлен на сервере

Чтобы это работало правильно, в самом AddHeads нужно включить режим определения premium-игроков через permission и использовать там право `addhead.premium`.

## Важно

- Автоматический перенос покрывает ванильные playerdata, stats и advancements.
- Данные сторонних плагинов нельзя универсально перенести одним общим способом, потому что каждый плагин хранит их по-своему.
