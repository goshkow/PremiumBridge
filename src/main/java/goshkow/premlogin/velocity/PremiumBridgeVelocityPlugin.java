package goshkow.premlogin.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import goshkow.premlogin.bridge.PremiumAssertion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Velocity side of PremiumBridge. Velocity performs the actual Mojang login;
 * this module only forwards a signed result to the backend after that login succeeds.
 */
@Plugin(
    id = "premiumbridge",
    name = "PremiumBridge",
    version = "1.0.6"
)
public final class PremiumBridgeVelocityPlugin {

    private static final String DEFAULT_CHANNEL = "premiumbridge:auth";
    private static final String PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final Pattern PROFILE_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F-]{32,36})\\\"");
    private static final Pattern PROFILE_NAME = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private final HttpClient httpClient;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, CachedProfile> profileCache = new ConcurrentHashMap<>();
    private final Map<UUID, VerifiedSession> verifiedSessions = new ConcurrentHashMap<>();

    private MinecraftChannelIdentifier channel;
    private String channelName;
    private String sharedSecret;
    private boolean enabled;
    private long profileCacheMillis;
    private long assertionTtlMillis;
    private int timeoutSeconds;

    @Inject
    public PremiumBridgeVelocityPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfiguration();
        channel = MinecraftChannelIdentifier.from(channelName);
        proxyServer.getChannelRegistrar().register(channel);
        logger.info("PremiumBridge Velocity module enabled. Premium verification uses Velocity online-mode authentication.");
    }

    @Subscribe(priority = 100)
    public EventTask onPreLogin(PreLoginEvent event) {
        if (!enabled) {
            return null;
        }

        return EventTask.async(() -> {
            if (hasPremiumProfile(event.getUsername())) {
                // Velocity now performs the standard encryption/session-server flow.
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
            }
        });
    }

    @Subscribe(priority = 100)
    public void onLogin(LoginEvent event) {
        if (!enabled || event.getServerIdHash() == null) {
            return;
        }

        Player player = event.getPlayer();
        verifiedSessions.put(
            player.getUniqueId(),
            new VerifiedSession(player.getUsername(), System.currentTimeMillis() + assertionTtlMillis + 10000L)
        );
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (!enabled) {
            return;
        }

        sendAssertion(event.getPlayer());
        proxyServer.getScheduler().buildTask(this, () -> sendAssertion(event.getPlayer()))
            .delay(Duration.ofMillis(250L))
            .schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        verifiedSessions.remove(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (channel != null && channel.equals(event.getIdentifier())) {
            // Never allow clients to forward data on the assertion channel.
            event.setResult(PluginMessageEvent.ForwardResult.handled());
        }
    }

    private void sendAssertion(Player player) {
        VerifiedSession session = verifiedSessions.get(player.getUniqueId());
        if (session == null || session.expiresAt() < System.currentTimeMillis()) {
            return;
        }

        Optional<ServerConnection> connection = player.getCurrentServer();
        if (connection.isEmpty()) {
            return;
        }

        PremiumAssertion assertion = PremiumAssertion.create(
            session.username(),
            player.getUniqueId(),
            assertionTtlMillis,
            sharedSecret
        );
        connection.get().sendPluginMessage(channel, assertion.encode());
    }

    private boolean hasPremiumProfile(String username) {
        String normalized = username.toLowerCase(java.util.Locale.ROOT);
        CachedProfile cached = profileCache.get(normalized);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) {
            return true;
        }

        try {
            String encodedName = URLEncoder.encode(username, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(PROFILE_URL + encodedName))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return false;
            }

            Matcher idMatcher = PROFILE_ID.matcher(response.body());
            Matcher nameMatcher = PROFILE_NAME.matcher(response.body());
            if (!idMatcher.find() || !nameMatcher.find()) {
                return false;
            }

            UUID uuid = parseUuid(idMatcher.group(1));
            profileCache.put(normalized, new CachedProfile(uuid, now + profileCacheMillis));
            return true;
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warning("Premium profile lookup failed for " + username + ": " + exception.getMessage());
            return false;
        }
    }

    private void loadConfiguration() {
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("velocity.properties");
            Properties properties = new Properties();
            if (Files.isRegularFile(file)) {
                try (InputStream input = Files.newInputStream(file)) {
                    properties.load(input);
                }
            }

            if (!properties.containsKey("shared-secret") || properties.getProperty("shared-secret").isBlank()) {
                properties.setProperty("shared-secret", generateSecret());
            }
            properties.putIfAbsent("enabled", "true");
            properties.putIfAbsent("channel", DEFAULT_CHANNEL);
            properties.putIfAbsent("profile-cache-seconds", "300");
            properties.putIfAbsent("assertion-ttl-millis", "10000");
            properties.putIfAbsent("mojang-timeout-seconds", "4");

            try (OutputStream output = Files.newOutputStream(file)) {
                properties.store(output, "PremiumBridge Velocity configuration");
            }

            enabled = Boolean.parseBoolean(properties.getProperty("enabled", "true"));
            channelName = properties.getProperty("channel", DEFAULT_CHANNEL);
            sharedSecret = properties.getProperty("shared-secret", "");
            profileCacheMillis = Math.max(1000L, Long.parseLong(properties.getProperty("profile-cache-seconds", "300")) * 1000L);
            assertionTtlMillis = Math.max(1000L, Long.parseLong(properties.getProperty("assertion-ttl-millis", "10000")));
            timeoutSeconds = Math.max(1, Integer.parseInt(properties.getProperty("mojang-timeout-seconds", "4")));
        } catch (IOException | NumberFormatException exception) {
            enabled = false;
            throw new IllegalStateException("Unable to load PremiumBridge Velocity configuration", exception);
        }
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private UUID parseUuid(String raw) {
        String normalized = raw.replace("-", "");
        if (normalized.length() != 32) {
            throw new IllegalArgumentException("Invalid Mojang UUID");
        }
        return UUID.fromString(
            normalized.substring(0, 8) + "-" + normalized.substring(8, 12) + "-"
                + normalized.substring(12, 16) + "-" + normalized.substring(16, 20) + "-"
                + normalized.substring(20)
        );
    }

    private record CachedProfile(UUID uuid, long expiresAt) {
    }

    private record VerifiedSession(String username, long expiresAt) {
    }
}
