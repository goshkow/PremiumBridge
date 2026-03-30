package goshkow.premlogin.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import goshkow.premlogin.PremiumLoginPlugin;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

final class MojangVerificationService {

    private static final String PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String HAS_JOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined";

    private final PremiumLoginPlugin plugin;
    private final HttpClient httpClient;

    MojangVerificationService(PremiumLoginPlugin plugin) {
        this.plugin = plugin;
        long timeoutSeconds = plugin.getConfig().getLong("premium-verification.timeout-seconds", 4L);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build();
    }

    boolean nicknameHasPremiumProfile(String username) {
        try {
            HttpResponse<String> response = httpClient.send(profileRequest(username), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body() != null) {
                return response.body().toLowerCase(Locale.ROOT).contains("\"id\"");
            }
            return false;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().log(Level.WARNING, "Failed premium nickname lookup for " + username, exception);
            return false;
        }
    }

    Optional<MojangVerifiedProfile> hasJoined(String username, String serverId, InetAddress address) {
        try {
            String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8);
            String encodedServerId = URLEncoder.encode(serverId, StandardCharsets.UTF_8);
            String encodedIp = URLEncoder.encode(address.getHostAddress(), StandardCharsets.UTF_8);

            URI uri = URI.create(HAS_JOINED_URL
                + "?username=" + encodedUsername
                + "&serverId=" + encodedServerId
                + "&ip=" + encodedIp);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(plugin.getConfig().getLong("premium-verification.timeout-seconds", 4L)))
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return Optional.empty();
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("id") || !root.has("name")) {
                return Optional.empty();
            }

            UUID uuid = parseUndashedUuid(root.get("id").getAsString());
            String verifiedName = root.get("name").getAsString();
            return Optional.of(new MojangVerifiedProfile(verifiedName, uuid));
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().log(Level.WARNING, "Failed hasJoined verification for " + username, exception);
            return Optional.empty();
        }
    }

    private HttpRequest profileRequest(String username) {
        String encodedName = URLEncoder.encode(username, StandardCharsets.UTF_8);
        return HttpRequest.newBuilder()
            .uri(URI.create(PROFILE_URL + encodedName))
            .timeout(Duration.ofSeconds(plugin.getConfig().getLong("premium-verification.timeout-seconds", 4L)))
            .header("Accept", "application/json")
            .GET()
            .build();
    }

    private UUID parseUndashedUuid(String raw) {
        String normalized = raw.replace("-", "");
        String dashed = normalized.substring(0, 8)
            + "-" + normalized.substring(8, 12)
            + "-" + normalized.substring(12, 16)
            + "-" + normalized.substring(16, 20)
            + "-" + normalized.substring(20, 32);
        return UUID.fromString(dashed);
    }
}
