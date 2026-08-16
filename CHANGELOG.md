# Changelog

## 1.0.4

- fixed invalid `0,0,0` restore locations caused by the early login event
- captured the player location before AuthMe/OpeNLogin join processing at the correct event priority
- added validation to prevent teleporting to invalid restore coordinates
- added a one-tick delay before premium auto-login
- restored the original position after auth-plugin spawn teleports
- added delayed restoration for game mode, operator status, flight and movement state
- stopped location correction when the player begins moving normally
- fixed movement speed restoration for manual AuthMe/OpeNLogin login and premium auto-login
- added append-only language updates for missing notification fields
- combined GitHub and Modrinth update links into one notification
- kept the Velocity companion at its independent version 1.0.3

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
