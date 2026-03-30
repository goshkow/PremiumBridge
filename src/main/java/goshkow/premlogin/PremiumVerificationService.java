package goshkow.premlogin;

import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

final class PremiumVerificationService {

    private static final String MOJANG_PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";

    private final PremiumLoginPlugin plugin;
    private final Logger logger;
    private final HttpClient httpClient;

    PremiumVerificationService(PremiumLoginPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(plugin.getConfig().getLong("premium-verification.timeout-seconds", 4L)))
            .build();
    }

    PremiumCheckResult check(Player player) {
        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + player.getName()).getBytes(StandardCharsets.UTF_8));
        if (!offlineUuid.equals(player.getUniqueId())) {
            return PremiumCheckResult.secure("joined with a non-offline UUID");
        }

        if (!plugin.getConfig().getBoolean("premium-verification.allow-insecure-mojang-lookup", false)) {
            return PremiumCheckResult.notPremium("kept offline UUID and insecure fallback is disabled");
        }

        return lookupMojangProfile(player.getName());
    }

    private PremiumCheckResult lookupMojangProfile(String playerName) {
        try {
            String encodedName = URLEncoder.encode(playerName, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MOJANG_PROFILE_URL + encodedName))
                .timeout(Duration.ofSeconds(plugin.getConfig().getLong("premium-verification.timeout-seconds", 4L)))
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body() != null && response.body().toLowerCase(Locale.ROOT).contains("\"id\"")) {
                return PremiumCheckResult.insecure("Mojang profile exists for that nickname");
            }

            if (response.statusCode() == 204 || response.statusCode() == 404) {
                return PremiumCheckResult.notPremium("nickname is not present in Mojang profile API");
            }

            logger.warning("Unexpected Mojang API status while checking premium name: " + response.statusCode());
            return PremiumCheckResult.notPremium("unexpected Mojang API response");
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.log(Level.WARNING, "Premium lookup failed for " + playerName, exception);
            return PremiumCheckResult.notPremium("premium lookup failed");
        }
    }
}
