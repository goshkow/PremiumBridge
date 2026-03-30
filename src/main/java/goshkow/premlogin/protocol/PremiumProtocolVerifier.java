package goshkow.premlogin.protocol;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.injector.netty.channel.NettyChannelInjector;
import com.comphenix.protocol.injector.packet.PacketRegistry;
import com.comphenix.protocol.injector.temporary.TemporaryPlayerFactory;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.reflect.accessors.Accessors;
import com.comphenix.protocol.reflect.accessors.ConstructorAccessor;
import com.comphenix.protocol.reflect.accessors.FieldAccessor;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.utility.MinecraftVersion;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import goshkow.premlogin.PremiumLoginPlugin;
import goshkow.premlogin.crypto.EncryptionUtil;
import org.bukkit.entity.Player;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

public final class PremiumProtocolVerifier {

    private final PremiumLoginPlugin plugin;
    private final ProtocolManager protocolManager;
    private final MojangVerificationService mojangService;
    private final KeyPair serverKeyPair = EncryptionUtil.generateKeyPair();
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentMap<String, PendingPremiumSession> pendingSessions = new ConcurrentHashMap<>();

    private static java.lang.reflect.Method encryptMethod;
    private static java.lang.reflect.Method encryptKeyMethod;
    private static java.lang.reflect.Method cipherMethod;

    public PremiumProtocolVerifier(PremiumLoginPlugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        this.mojangService = new MojangVerificationService(plugin);
    }

    public boolean initialize() {
        if (!plugin.getConfig().getBoolean("premium-verification.enabled", true)) {
            plugin.getLogger().info("Protocol premium verification is disabled in config.");
            return false;
        }

        if (plugin.getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            plugin.getLogger().warning("ProtocolLib is required for premium verification but is not installed.");
            return false;
        }

        protocolManager.getAsynchronousManager()
            .registerAsyncHandler(new PacketAdapter(
                PacketAdapter.params()
                    .plugin(plugin)
                    .listenerPriority(ListenerPriority.HIGHEST)
                    .types(PacketType.Login.Client.START, PacketType.Login.Client.ENCRYPTION_BEGIN)
                    .optionAsync()
            ) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    if (event.isCancelled()) {
                        return;
                    }

                    PacketType type = normalizeType(event.getPacketType());
                    if (type == PacketType.Login.Client.START) {
                        onHandshakeStart(event);
                    } else if (type == PacketType.Login.Client.ENCRYPTION_BEGIN) {
                        onHandshakeResponse(event);
                    }
                }
            }).start();

        return true;
    }

    private void onHandshakeStart(PacketEvent event) {
        Player player = event.getPlayer();
        String username = extractUsername(event.getPacket());
        if (username == null || username.isBlank()) {
            return;
        }

        if (shouldProtectNickname(username)) {
            byte[] verifyToken = EncryptionUtil.generateVerifyToken(secureRandom);
            UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
            pendingSessions.put(player.getAddress().toString(), new PendingPremiumSession(username, player.getAddress(), verifyToken, offlineUuid));

            synchronized (event.getAsyncMarker().getProcessingLock()) {
                event.setCancelled(true);
            }

            requestPremiumHandshake(player, verifyToken);
            return;
        }

        if (!mojangService.nicknameHasPremiumProfile(username)) {
            return;
        }

        byte[] verifyToken = EncryptionUtil.generateVerifyToken(secureRandom);
        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        pendingSessions.put(player.getAddress().toString(), new PendingPremiumSession(username, player.getAddress(), verifyToken, offlineUuid));

        synchronized (event.getAsyncMarker().getProcessingLock()) {
            event.setCancelled(true);
        }

        requestPremiumHandshake(player, verifyToken);
    }

    private void onHandshakeResponse(PacketEvent event) {
        Player player = event.getPlayer();
        PendingPremiumSession session = pendingSessions.remove(player.getAddress().toString());
        if (session == null) {
            return;
        }

        synchronized (event.getAsyncMarker().getProcessingLock()) {
            event.setCancelled(true);
        }

        try {
            HandshakePayload payload = readHandshakePayload(event.getPacket());
            if (!EncryptionUtil.verifyNonce(session.verifyToken(), serverKeyPair.getPrivate(), payload.encryptedNonce())) {
                kickAtLogin(player, plugin.getLanguageManager().text("premium.kick.invalid-token"));
                return;
            }

            SecretKey loginKey = EncryptionUtil.decryptSharedKey(serverKeyPair.getPrivate(), payload.encryptedSecret());
            if (!activateEncryptedChannel(player, loginKey)) {
                kickAtLogin(player, plugin.getLanguageManager().text("premium.kick.encryption-failed"));
                return;
            }

            Optional<MojangVerifiedProfile> verifiedProfile = verifySessionOwnership(
                session.requestedUsername(),
                loginKey,
                session.address().getAddress()
            );

            if (verifiedProfile.isPresent()) {
                MojangVerifiedProfile profile = verifiedProfile.get();
                applyVerifiedIdentity(player, profile);
                return;
            }

            if (shouldKickUnverifiedPremiumName(session.requestedUsername())) {
                kickAtLogin(player, plugin.getLanguageManager().text("premium.kick.protected-name"));
                return;
            }

            continueAsOfflinePlayer(player, session);
        } catch (GeneralSecurityException | ReflectiveOperationException exception) {
            plugin.getLogger().log(Level.WARNING, "Premium verification failed for " + session.requestedUsername(), exception);
            kickAtLogin(player, plugin.getLanguageManager().text("premium.kick.error"));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Unexpected premium verification error for " + session.requestedUsername(), exception);
            kickAtLogin(player, plugin.getLanguageManager().text("premium.kick.error"));
        }
    }

    private HandshakePayload readHandshakePayload(PacketContainer packet) {
        return new HandshakePayload(
            packet.getByteArrays().read(0),
            packet.getByteArrays().read(1)
        );
    }

    private Optional<MojangVerifiedProfile> verifySessionOwnership(String username, SecretKey loginKey, java.net.InetAddress address) {
        String sessionHash = EncryptionUtil.getServerIdHashString("", loginKey, serverKeyPair.getPublic());
        return mojangService.hasJoined(username, sessionHash, address);
    }

    private void applyVerifiedIdentity(Player player, MojangVerifiedProfile profile) {
        setSpoofedUuid(player, profile.uuid());
        resendLoginStart(player, profile.name(), profile.uuid());
    }

    private void continueAsOfflinePlayer(Player player, PendingPremiumSession session) {
        resendLoginStart(player, session.requestedUsername(), session.offlineUuid());
    }

    private void requestPremiumHandshake(Player player, byte[] verifyToken) {
        PacketContainer packet = new PacketContainer(PacketType.Login.Server.ENCRYPTION_BEGIN);
        packet.getStrings().write(0, "");

        if (packet.getSpecificModifier(PublicKey.class).getFields().isEmpty()) {
            packet.getByteArrays().write(0, serverKeyPair.getPublic().getEncoded());
            packet.getByteArrays().write(1, verifyToken);
        } else {
            packet.getSpecificModifier(PublicKey.class).write(0, serverKeyPair.getPublic());
            packet.getByteArrays().write(0, verifyToken);
        }

        packet.getBooleans().writeSafely(0, true);
        try {
            protocolManager.sendServerPacket(player, packet);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to send encryption request", exception);
        }
    }

    private boolean activateEncryptedChannel(Player player, SecretKey loginKey) throws ReflectiveOperationException {
        if (encryptKeyMethod == null && encryptMethod == null) {
            Class<?> networkManagerClass = MinecraftReflection.getNetworkManagerClass();
            try {
                encryptKeyMethod = FuzzyReflection.fromClass(networkManagerClass).getMethodByParameters("a", SecretKey.class);
            } catch (IllegalArgumentException ignored) {
                encryptMethod = FuzzyReflection.fromClass(networkManagerClass).getMethodByParameters("a", Cipher.class, Cipher.class);
                Class<?> encryptionClass = MinecraftReflection.getMinecraftClass("util.MinecraftEncryption", "MinecraftEncryption");
                cipherMethod = FuzzyReflection.fromClass(encryptionClass).getMethodByParameters("a", int.class, Key.class);
            }
        }

        Object networkManager = getNetworkManager(player);
        if (encryptKeyMethod != null) {
            encryptKeyMethod.invoke(networkManager, loginKey);
            return true;
        }

        Object decryptionCipher = cipherMethod.invoke(null, Cipher.DECRYPT_MODE, loginKey);
        Object encryptionCipher = cipherMethod.invoke(null, Cipher.ENCRYPT_MODE, loginKey);
        encryptMethod.invoke(networkManager, decryptionCipher, encryptionCipher);
        return true;
    }

    private Object getNetworkManager(Player player) {
        NettyChannelInjector injector = (NettyChannelInjector) Accessors
            .getMethodAccessorOrNull(TemporaryPlayerFactory.class, "getInjectorFromPlayer", Player.class)
            .invoke(null, player);
        FieldAccessor accessor = Accessors.getFieldAccessorOrNull(NettyChannelInjector.class, "networkManager", Object.class);
        return accessor.get(injector);
    }

    private void setSpoofedUuid(Player player, UUID uuid) {
        try {
            Object networkManager = getNetworkManager(player);
            FieldAccessor accessor = Accessors.getFieldAccessorOrNull(networkManager.getClass(), "spoofedUUID", UUID.class);
            if (accessor != null) {
                accessor.set(networkManager, uuid);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Unable to set spoofed UUID for premium player", exception);
        }
    }

    private void resendLoginStart(Player player, String username, UUID uuid) {
        try {
            PacketContainer packet;
            if (new MinecraftVersion(1, 20, 2).atOrAbove()) {
                packet = new PacketContainer(PacketType.Login.Client.START);
                packet.getStrings().write(0, username);
                packet.getUUIDs().write(0, uuid);
            } else {
                WrappedGameProfile profile = new WrappedGameProfile(uuid, username);
                Class<?> packetHandleType = PacketRegistry.getPacketClassFromType(PacketType.Login.Client.START);
                ConstructorAccessor constructor = Accessors.getConstructorAccessorOrNull(packetHandleType, profile.getHandleType());
                packet = new PacketContainer(PacketType.Login.Client.START, constructor.invoke(profile.getHandle()));
            }

            protocolManager.receiveClientPacket(player, packet, false);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to inject replacement login packet", exception);
        }
    }

    private void kickAtLogin(Player player, String reason) {
        PacketContainer kickPacket = new PacketContainer(PacketType.Login.Server.DISCONNECT);
        kickPacket.getChatComponents().write(0, WrappedChatComponent.fromText(reason));
        try {
            protocolManager.sendServerPacket(player, kickPacket);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.FINE, "Failed to send login disconnect packet", exception);
        } finally {
            player.kickPlayer(reason);
        }
    }

    private String extractUsername(PacketContainer packet) {
        WrappedGameProfile profile = packet.getGameProfiles().readSafely(0);
        if (profile != null) {
            return profile.getName();
        }

        if (packet.getStrings().size() > 0) {
            return packet.getStrings().read(0);
        }

        return null;
    }

    private PacketType normalizeType(PacketType type) {
        if (!type.isDynamic()) {
            return type;
        }

        String className = type.getPacketClass().getName();
        if (className.endsWith("ServerboundHelloPacket")) {
            return PacketType.Login.Client.START;
        }
        if (className.endsWith("ServerboundKeyPacket")) {
            return PacketType.Login.Client.ENCRYPTION_BEGIN;
        }
        return type;
    }

    private boolean shouldProtectNickname(String username) {
        return shouldKickUnverifiedPremiumName(username) || mojangService.nicknameHasPremiumProfile(username);
    }

    private boolean shouldKickUnverifiedPremiumName(String username) {
        return plugin.getConfig().getBoolean("premium-verification.protect-known-premium-names.enabled", false)
            && plugin.getPlayerStore().isProtectedPremiumName(username);
    }

    private record HandshakePayload(byte[] encryptedSecret, byte[] encryptedNonce) {
    }
}
