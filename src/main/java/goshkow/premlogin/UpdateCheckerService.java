package goshkow.premlogin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks public GitHub and Modrinth releases without blocking the server thread. */
public final class UpdateCheckerService {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(?i)(?:\\bv)?(\\d+(?:\\.\\d+)+)");
    private static final String GITHUB_RELEASES_URL = "https://api.github.com/repos/goshkow/PremiumBridge/releases?per_page=100";
    private static final String GITHUB_LATEST_URL = "https://github.com/goshkow/PremiumBridge/releases/latest";
    private static final String MODRINTH_VERSIONS_URL = "https://api.modrinth.com/v2/project/premiumbridge/version";
    private static final String MODRINTH_LATEST_URL = "https://modrinth.com/plugin/premiumbridge/versions";

    private final PremiumLoginPlugin plugin;
    private final HttpClient httpClient;
    private volatile BukkitTask task;
    private volatile UpdateState currentState;

    public UpdateCheckerService(PremiumLoginPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public synchronized void start() {
        stop();
        if (!isEnabled()) {
            return;
        }

        long period = Math.max(20L, plugin.getUpdateCheckIntervalTicks());
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkSafely, 40L, period);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::checkSafely);
    }

    public synchronized void restart() {
        start();
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        currentState = null;
    }

    public void notifyPlayerIfPending(Player player) {
        if (!isEnabled() || !plugin.shouldReceiveUpdateNotifications(player)) {
            return;
        }

        UpdateState state = currentState;
        if (state == null || !state.markNotified(player.getUniqueId())) {
            return;
        }

        Map<String, String> placeholders = Map.of(
            "current", state.currentVersion().display(),
            "latest", state.latestVersion().display()
        );
        player.sendMessage(plugin.getLanguageManager().component(player, "update.available", placeholders));

        Component sources = plugin.getLanguageManager().component(player, "update.sources");
        boolean hasSource = false;
        if (state.github() != null) {
            sources = sources.append(sourceMessage(player, "update.github", state.github()));
            hasSource = true;
        }
        if (state.modrinth() != null) {
            if (hasSource) {
                sources = sources.append(plugin.getLanguageManager().component(player, "update.source-separator"));
            }
            sources = sources.append(sourceMessage(player, "update.modrinth", state.modrinth()));
        }
        player.sendMessage(sources);
    }

    private Component sourceMessage(Player player, String key, UpdateCandidate candidate) {
        return plugin.getLanguageManager()
            .component(player, key, Map.of("version", candidate.version().display()))
            .clickEvent(ClickEvent.openUrl(candidate.url()))
            .hoverEvent(HoverEvent.showText(plugin.getLanguageManager().component(player, "update.open-link")));
    }

    private void checkSafely() {
        try {
            checkNow();
        } catch (Exception exception) {
            plugin.getLogger().fine("Update check failed: " + exception.getMessage());
        }
    }

    private synchronized void checkNow() throws IOException, InterruptedException {
        if (!isEnabled()) {
            return;
        }

        VersionNumber currentVersion = VersionNumber.parse(plugin.getDescription().getVersion()).orElse(null);
        if (currentVersion == null) {
            plugin.getLogger().warning("Could not parse PremiumBridge version: " + plugin.getDescription().getVersion());
            return;
        }

        UpdateCandidate github = fetchGitHubUpdate(currentVersion);
        UpdateCandidate modrinth = fetchModrinthUpdate(currentVersion);
        UpdateState discovered = new UpdateState(currentVersion, github, modrinth);

        if (!discovered.isActive()) {
            currentState = null;
            return;
        }

        UpdateState previous = currentState;
        if (previous != null && previous.signature().equals(discovered.signature())) {
            return;
        }

        currentState = discovered;
        Bukkit.getScheduler().runTask(plugin, () -> {
            UpdateState state = currentState;
            if (state == discovered) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    notifyPlayerIfPending(player);
                }
            }
        });
    }

    private UpdateCandidate fetchGitHubUpdate(VersionNumber currentVersion) throws IOException, InterruptedException {
        JsonElement root = getJson(GITHUB_RELEASES_URL, "application/vnd.github+json");
        if (root == null || !root.isJsonArray()) {
            return null;
        }

        UpdateCandidate best = null;
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject release = element.getAsJsonObject();
            if (booleanValue(release, "draft") || booleanValue(release, "prerelease")) {
                continue;
            }

            VersionNumber version = bestVersion(stringValue(release, "tag_name"), stringValue(release, "name")).orElse(null);
            if (version == null || version.compareTo(currentVersion) <= 0) {
                continue;
            }

            String url = stringValue(release, "html_url");
            if (url.isBlank()) {
                url = GITHUB_LATEST_URL;
            }

            UpdateCandidate candidate = new UpdateCandidate("GitHub", version, url);
            if (best == null || candidate.version().compareTo(best.version()) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    private UpdateCandidate fetchModrinthUpdate(VersionNumber currentVersion) throws IOException, InterruptedException {
        JsonElement root = getJson(MODRINTH_VERSIONS_URL, "application/json");
        if (root == null || !root.isJsonArray()) {
            return null;
        }

        UpdateCandidate best = null;
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject versionObject = element.getAsJsonObject();
            if (!"listed".equalsIgnoreCase(stringValue(versionObject, "status"))
                || !"release".equalsIgnoreCase(stringValue(versionObject, "version_type"))) {
                continue;
            }

            VersionNumber version = bestVersion(
                stringValue(versionObject, "version_number"),
                stringValue(versionObject, "name")
            ).orElse(null);
            if (version == null || version.compareTo(currentVersion) <= 0) {
                continue;
            }

            String versionNumber = stringValue(versionObject, "version_number");
            String url = versionNumber.isBlank()
                ? MODRINTH_LATEST_URL
                : "https://modrinth.com/plugin/premiumbridge/version/" + versionNumber;
            UpdateCandidate candidate = new UpdateCandidate("Modrinth", version, url);
            if (best == null || candidate.version().compareTo(best.version()) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    private JsonElement getJson(String url, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(7))
            .header("Accept", accept)
            .header("User-Agent", "PremiumBridge/" + plugin.getDescription().getVersion())
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }
        return JsonParser.parseString(response.body());
    }

    private boolean isEnabled() {
        return plugin.getConfig().getBoolean("update-check.enabled", true);
    }

    private boolean booleanValue(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }

    private String stringValue(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private java.util.Optional<VersionNumber> bestVersion(String... values) {
        List<VersionNumber> versions = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            Matcher matcher = VERSION_PATTERN.matcher(value);
            while (matcher.find()) {
                VersionNumber.parse(matcher.group(1)).ifPresent(versions::add);
            }
            VersionNumber.parse(value).ifPresent(versions::add);
        }
        return versions.stream().max(Comparator.naturalOrder());
    }

    private record UpdateCandidate(String source, VersionNumber version, String url) {
    }

    private static final class UpdateState {
        private final VersionNumber currentVersion;
        private final UpdateCandidate github;
        private final UpdateCandidate modrinth;
        private final Set<UUID> notifiedPlayers = ConcurrentHashMap.newKeySet();

        private UpdateState(VersionNumber currentVersion, UpdateCandidate github, UpdateCandidate modrinth) {
            this.currentVersion = currentVersion;
            this.github = github;
            this.modrinth = modrinth;
        }

        private boolean isActive() {
            return github != null || modrinth != null;
        }

        private VersionNumber currentVersion() {
            return currentVersion;
        }

        private UpdateCandidate github() {
            return github;
        }

        private UpdateCandidate modrinth() {
            return modrinth;
        }

        private VersionNumber latestVersion() {
            return StreamHelper.max(github == null ? null : github.version(), modrinth == null ? null : modrinth.version());
        }

        private boolean markNotified(UUID playerId) {
            return playerId != null && notifiedPlayers.add(playerId);
        }

        private String signature() {
            return candidateSignature(github) + "|" + candidateSignature(modrinth);
        }

        private String candidateSignature(UpdateCandidate candidate) {
            return candidate == null ? "" : candidate.source() + ":" + candidate.version().display() + ":" + candidate.url();
        }
    }

    private static final class StreamHelper {
        private static VersionNumber max(VersionNumber first, VersionNumber second) {
            if (first == null) {
                return second;
            }
            if (second == null) {
                return first;
            }
            return first.compareTo(second) >= 0 ? first : second;
        }
    }

    private record VersionNumber(List<Integer> parts) implements Comparable<VersionNumber> {

        private static java.util.Optional<VersionNumber> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return java.util.Optional.empty();
            }
            Matcher matcher = VERSION_PATTERN.matcher(raw.trim());
            if (!matcher.find()) {
                return java.util.Optional.empty();
            }

            String[] numbers = matcher.group(1).split("\\.");
            List<Integer> parts = new ArrayList<>();
            for (String number : numbers) {
                try {
                    parts.add(Integer.parseInt(number));
                } catch (NumberFormatException exception) {
                    return java.util.Optional.empty();
                }
            }
            while (parts.size() > 1 && parts.get(parts.size() - 1) == 0) {
                parts.remove(parts.size() - 1);
            }
            return java.util.Optional.of(new VersionNumber(List.copyOf(parts)));
        }

        private String display() {
            return parts.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("."));
        }

        @Override
        public int compareTo(VersionNumber other) {
            int length = Math.max(parts.size(), other.parts.size());
            for (int index = 0; index < length; index++) {
                int left = index < parts.size() ? parts.get(index) : 0;
                int right = index < other.parts.size() ? other.parts.get(index) : 0;
                int comparison = Integer.compare(left, right);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }
    }
}
