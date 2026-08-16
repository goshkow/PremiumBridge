# PremiumBridge API

PremiumBridge provides a public API for other plugins that want to interact with premium session state, auth integration metadata, and offline/premium UUID links.

## Getting the API

You can get the API through the static helper:

```java
import goshkow.premlogin.api.PremiumBridge;
import goshkow.premlogin.api.PremiumBridgeApi;

PremiumBridgeApi api = PremiumBridge.getApi();
if (api == null) {
    return;
}
```

Or through Bukkit `ServicesManager`:

```java
import goshkow.premlogin.api.PremiumBridgeApi;
import org.bukkit.plugin.RegisteredServiceProvider;

RegisteredServiceProvider<PremiumBridgeApi> registration =
    Bukkit.getServicesManager().getRegistration(PremiumBridgeApi.class);

PremiumBridgeApi api = registration == null ? null : registration.getProvider();
```

## Main Types

### `PremiumBridgeApi`

The main service interface exposed by PremiumBridge.

### `PremiumStatus`

Possible premium states:

- `NOT_PREMIUM`
- `PREMIUM_INSECURE`
- `PREMIUM_SECURE`

Helper methods:

- `isPremium()`
- `isSecure()`

## API Methods

### General

```java
String getPluginVersion();
boolean hasAuthProvider();
String getActiveAuthProvider();
```

- `getPluginVersion()` returns the plugin version
- `hasAuthProvider()` tells you whether PremiumBridge is currently hooked into a supported auth plugin
- `getActiveAuthProvider()` returns the current provider id, for example `AUTHME` or `OPENLOGIN`

### Premium Status

```java
PremiumStatus getPremiumStatus(Player player);
boolean isPremium(Player player);
boolean isSecurePremium(Player player);
```

- `getPremiumStatus(player)` returns the full premium state
- `isPremium(player)` is a quick boolean check
- `isSecurePremium(player)` tells you whether the player is considered securely premium

## Known Premium Identities

```java
boolean isKnownPremiumNickname(String nickname);
Set<String> getKnownPremiumNicknames();
boolean isPremiumNameProtectionEnabled();
```

- `isKnownPremiumNickname(nickname)` checks whether the nickname is already linked to a known premium identity
- `getKnownPremiumNicknames()` returns the stored nickname set
- `isPremiumNameProtectionEnabled()` tells you whether protected premium names are enforced in config

## Migration State

```java
boolean isMigrationProcessed(String nickname);
```

- `isMigrationProcessed(nickname)` tells you whether PremiumBridge already finished or skipped migration handling for that nickname

## UUID Linking

PremiumBridge keeps track of offline and premium UUID relationships.

```java
UUID getOfflineUuid(Player player);
UUID getOfflineUuid(UUID premiumUuid);
UUID getPremiumUuid(UUID offlineUuid);
UUID calculateOfflineUuid(String nickname);
UUID getLinkedPremiumUuid(String nickname);
UUID getLinkedOfflineUuid(String nickname);
```

### What these methods mean

- `getOfflineUuid(player)` returns the linked offline UUID for the player, or calculates it from the current nickname if no stored link exists yet
- `getOfflineUuid(premiumUuid)` returns the stored offline UUID linked to a premium UUID
- `getPremiumUuid(offlineUuid)` returns the stored premium UUID linked to an offline UUID
- `calculateOfflineUuid(nickname)` calculates the deterministic offline-mode UUID for a nickname
- `getLinkedPremiumUuid(nickname)` returns the premium UUID stored for that nickname
- `getLinkedOfflineUuid(nickname)` returns the offline UUID stored for that nickname

### Example: load old plugin data

```java
UUID oldOfflineUuid = api.getOfflineUuid(player);
if (oldOfflineUuid != null) {
    // Use oldOfflineUuid to load data that another plugin stored for offline mode
}
```

### Example: convert an offline UUID to the new premium UUID

```java
UUID premiumUuid = api.getPremiumUuid(oldOfflineUuid);
if (premiumUuid != null) {
    // Migrate external plugin data from offline UUID to premium UUID
}
```

## Registration and Seamless Join State

```java
boolean shouldSkipRegistrationForPremium();
boolean hidesAuthMessagesForPremium();
```

- `shouldSkipRegistrationForPremium()` returns whether premium players are auto-registered instead of being forced to register manually
- `hidesAuthMessagesForPremium()` returns whether PremiumBridge is configured to hide auth-plugin service messages for premium players

## Notes

- PremiumBridge can expose both stored UUID links and calculated offline UUIDs
- The calculated offline UUID is deterministic for a nickname and is not random
- External plugin data migration is still plugin-specific: PremiumBridge can help you locate the right UUIDs, but it cannot automatically rewrite every third-party data format
