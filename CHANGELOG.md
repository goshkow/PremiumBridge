# Changelog

## 1.0.3

- added an optional Velocity companion for modern forwarding
- removed the backend raw encryption handshake from Velocity mode
- added signed, short-lived proxy assertions for secure backend auto-login
- preserved native Mojang UUID, skin, and tab profile properties through Paper modern forwarding
- split the release into one backend JAR and one Velocity companion JAR

## 1.0.2

- added one-JAR compatibility release for Minecraft Java 1.21.9-1.21.11, 26.1.x, and 26.2
- changed the build target to the oldest common Bukkit/Paper API for forward compatibility
- kept ProtocolLib external and shaded only the library bundled by PremiumBridge

## 1.0.1

- updated support for the latest Minecraft release
- removed global auth-plugin message patching so only premium players hide service messages

## 1.0.0

- initial PremiumBridge release
- premium auto-login flow for verified premium players
- premium auto-registration option for supported auth plugins
- auth-plugin bridging for AuthMe and OpeNLogin
- seamless hiding of auth-plugin service messages for premium players
- offline-to-premium data migration with `disabled`, `automatic`, and `ask-player` modes
- reconnect-based manual migration flow for reliable inventory, position, stats, and advancements transfer
- public PremiumBridge API for plugin integrations
- bundled language system with multiple default locales
- AddHeads integration through `addhead.premium`
- GitHub README files in English and Russian
- Modrinth description files
