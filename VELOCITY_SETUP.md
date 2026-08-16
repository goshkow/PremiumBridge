# PremiumBridge with Velocity

This mode keeps the backend in offline mode while Velocity performs Mojang authentication for premium players. PremiumBridge does not send a raw `EncryptionRequest` from the backend in this mode.

## Files

- Put `PremiumBridge.jar` in the backend `plugins` directory.
- Put `PremiumBridge-Velocity.jar` in the Velocity `plugins` directory.
- Keep AuthMe or OpeNLogin installed on the backend.

The two JARs are intentionally separate: Bukkit/Paper and Velocity use different plugin loaders. The backend JAR remains the single Minecraft-version-compatible JAR.

## Native modern forwarding

In `velocity.toml`:

```toml
online-mode = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
```

For each Paper backend, set `server.properties`:

```properties
online-mode=false
```

In `config/paper-global.yml`, configure the native Paper support:

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: false
    secret: "<the exact contents of forwarding.secret>"
```

If legacy Bungee forwarding was enabled, disable it in `spigot.yml`:

```yaml
settings:
  bungeecord: false
```

Restart both the proxy and backend after changing these settings. Keep backend ports protected so players cannot bypass the proxy.

## PremiumBridge settings

Set this in the backend `plugins/PremiumBridge/config.yml`:

```yaml
premium-verification:
  mode: "velocity-modern"
  velocity-modern:
    channel: "premiumbridge:auth"
    shared-secret: "<copy from Velocity plugins/PremiumBridge/velocity.properties>"
```

On the first proxy start, the Velocity companion creates `plugins/PremiumBridge/velocity.properties` and generates a private `shared-secret`. Copy that value to every backend behind the proxy. Do not publish it and do not reuse the Velocity `forwarding.secret` value unless you deliberately choose to do so.

## What the mode guarantees

- Velocity authenticates a premium client with Mojang before the backend connection.
- The backend accepts auto-login only after a short-lived HMAC-signed assertion from the trusted proxy.
- A UUID or IP address alone is never treated as proof of ownership.
- Paper receives the Mojang UUID and profile properties through modern forwarding, so premium skins and native tab profiles remain available.
- Cracked players are not forced through the premium auto-login flow and continue to use AuthMe or OpeNLogin.
- A cracked client using an existing Mojang nickname cannot bypass the proxy authentication: the proxy selects online mode for known Mojang profiles and Mojang rejects an unauthenticated claimant.

## Official configuration references

- [PaperMC: configuring player information forwarding](https://docs.papermc.io/velocity/player-information-forwarding/)
- [PaperMC: Velocity configuration](https://docs.papermc.io/velocity/configuration/)
- [PaperMC: securing Velocity backends](https://docs.papermc.io/velocity/security/)
