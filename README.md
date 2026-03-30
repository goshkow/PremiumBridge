[Русская версия](README_RU.md)

# PremiumBridge

PremiumBridge is a Paper 1.21.x plugin for offline-mode servers that detects real premium joins, auto-authenticates them with supported auth plugins, and keeps cracked players on the normal login/register flow.

## Features

- Premium session verification for offline-mode servers through ProtocolLib and Mojang session checks
- Automatic login for verified premium players
- Optional premium auto-registration with a random password
- Optional protection for nicknames that were already linked to premium accounts
- Seamless hiding of auth-plugin service messages for premium players, so auto-login does not spam chat
- Offline-to-premium data migration
- New migration modes:
  - `disabled`
  - `automatic`
  - `ask-player`
- Public API for other plugins
- AddHeads integration for premium players
- Public API for other plugins
- Built-in language files

## Supported Auth Plugins

- AuthMe
- OpeNLogin

`xAuth` is intentionally not listed as supported on modern Paper 1.21.x.

## Requirements

- Paper 1.21.x
- ProtocolLib
- One supported auth plugin

## Installation

1. Install `ProtocolLib`.
2. Install `AuthMe` or `OpeNLogin`.
3. Put `PremiumBridge.jar` into `plugins/`.
4. Start the server once.
5. Edit `plugins/PremiumBridge/config.yml` if needed.
6. Restart the server or run `/premauthbridge reload`.

## Languages

Bundled language files are created in `plugins/PremiumBridge/languages/`:

- `en_US`
- `ru_RU`
- `de_DE`
- `fr_FR`
- `pl_PL`
- `uk_UA`
- `es_ES`
- `it_IT`

Set the default language in `config.yml`:

```yml
language:
  default: "en_US"
  auto-detect-client-locale: true
```

## Migration Modes

```yml
migration:
  offline-data:
    enabled: true
    mode: "ask-player"
```

- `disabled`: do not migrate offline data
- `automatic`: migrate automatically before the premium player fully joins
- `ask-player`: show clickable `YES/NO` buttons in chat after premium auto-login

When a player accepts manual migration, PremiumBridge asks them to reconnect once. On the next join, the migration is applied before the player fully loads, which is the most reliable way to restore inventory, position, stats and advancements.

## API

PremiumBridge registers a Bukkit service and also provides a static helper:

```java
import goshkow.premlogin.api.PremiumBridge;
import goshkow.premlogin.api.PremiumBridgeApi;
import goshkow.premlogin.api.PremiumStatus;

PremiumBridgeApi api = PremiumBridge.getApi();
if (api != null) {
    PremiumStatus status = api.getPremiumStatus(player);
    if (status.isSecure()) {
        // verified premium session
    }
}
```

The API also exposes:

- active auth provider detection
- secure vs insecure premium status
- known premium nicknames
- linked offline/premium UUID lookups
- migration state checks
- premium registration mode checks

## AddHeads Integration

PremiumBridge can integrate with [AddHeads](https://modrinth.com/plugin/addheads) so verified premium players do not get duplicated tab heads.

How it works:

- PremiumBridge automatically grants `addhead.premium` to verified premium players when the integration is enabled
- this is useful when AddHeads is configured to treat premium players through permission mode
- on first config creation, PremiumBridge enables this integration automatically if `AddHeads` is already installed

For this to work correctly in AddHeads, enable the premium-player detection mode that uses permission checks, and make sure `addhead.premium` is the permission being used there.

## Notes

- Automatic data migration covers vanilla player data, stats and advancements.
- Third-party plugin data is not universally portable because each plugin stores it differently.
