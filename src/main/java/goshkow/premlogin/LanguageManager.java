package goshkow.premlogin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LanguageManager {

    private static final List<String> BUNDLED_LANGUAGES = List.of(
        "en_US",
        "ru_RU",
        "de_DE",
        "fr_FR",
        "pl_PL",
        "uk_UA",
        "es_ES",
        "it_IT"
    );

    private final PremiumLoginPlugin plugin;
    private final Map<String, YamlConfiguration> loadedLanguages = new ConcurrentHashMap<>();
    private YamlConfiguration bundledEnglish;

    LanguageManager(PremiumLoginPlugin plugin) {
        this.plugin = plugin;
    }

    void initialize() {
        File languagesFolder = new File(plugin.getDataFolder(), "languages");
        if (!languagesFolder.exists() && !languagesFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create languages folder.");
        }

        for (String language : BUNDLED_LANGUAGES) {
            plugin.saveResource("languages/" + language + ".yml", false);
        }

        bundledEnglish = loadBundledEnglish();
        appendMissingUpdateFields(languagesFolder);
        reload();
    }

    void reload() {
        loadedLanguages.clear();
        File languagesFolder = new File(plugin.getDataFolder(), "languages");
        File[] files = languagesFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            String name = file.getName();
            String key = name.substring(0, name.length() - 4);
            loadedLanguages.put(normalizeLocale(key), YamlConfiguration.loadConfiguration(file));
        }
    }

    public String text(String key) {
        return text((Player) null, key, Map.of());
    }

    public String text(CommandSender sender, String key, Map<String, String> placeholders) {
        if (sender instanceof Player player) {
            return text(player, key, placeholders);
        }

        return text((Player) null, key, placeholders);
    }

    public String text(Player player, String key, Map<String, String> placeholders) {
        YamlConfiguration language = resolveLanguage(player);
        String raw = getRaw(language, key);
        if (raw == null) {
            raw = key;
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("prefix", getRaw(language, "prefix") == null ? "" : getRaw(language, "prefix"));
        values.putAll(placeholders);

        String result = raw;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }

        return colorize(result);
    }

    public Component component(Player player, String key) {
        return component(player, key, Map.of());
    }

    public Component component(Player player, String key, Map<String, String> placeholders) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text(player, key, placeholders));
    }

    private YamlConfiguration resolveLanguage(Player player) {
        if (player != null && plugin.getConfig().getBoolean("language.auto-detect-client-locale", true)) {
            YamlConfiguration exact = loadedLanguages.get(normalizeLocale(player.getLocale()));
            if (exact != null) {
                return exact;
            }

            String lowered = normalizeLocale(player.getLocale()).toLowerCase(Locale.ROOT);
            for (Map.Entry<String, YamlConfiguration> entry : loadedLanguages.entrySet()) {
                if (entry.getKey().toLowerCase(Locale.ROOT).startsWith(lowered.split("_")[0] + "_")) {
                    return entry.getValue();
                }
            }
        }

        String defaultLocale = normalizeLocale(plugin.getConfig().getString("language.default", "en_US"));
        return loadedLanguages.getOrDefault(defaultLocale, loadedLanguages.getOrDefault("en_US", new YamlConfiguration()));
    }

    private String getRaw(YamlConfiguration language, String key) {
        String value = language.getString(key);
        if (value != null) {
            return value;
        }

        YamlConfiguration fallback = loadedLanguages.get("en_US");
        if (fallback != null) {
            value = fallback.getString(key);
            if (value != null) {
                return value;
            }
        }

        return bundledEnglish == null ? null : bundledEnglish.getString(key);
    }

    private YamlConfiguration loadBundledEnglish() {
        try (InputStream stream = plugin.getResource("languages/en_US.yml")) {
            if (stream == null) {
                return new YamlConfiguration();
            }

            return YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
            );
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not load bundled English language fallback: " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    /**
     * Adds new update keys without replacing any existing language text or comments.
     * Existing server files are intentionally never overwritten.
     */
    private void appendMissingUpdateFields(File languagesFolder) {
        if (bundledEnglish == null) {
            return;
        }

        var defaults = bundledEnglish.getConfigurationSection("update");
        if (defaults == null) {
            return;
        }

        File[] files = languagesFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            appendMissingUpdateFields(file.toPath(), defaults);
        }
    }

    private void appendMissingUpdateFields(Path file, org.bukkit.configuration.ConfigurationSection defaults) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(file.toFile());
            var existingUpdate = existing.getConfigurationSection("update");
            String newline = content.contains("\r\n") ? "\r\n" : "\n";
            StringBuilder additions = new StringBuilder();

            for (String key : defaults.getKeys(false)) {
                String path = "update." + key;
                if (existingUpdate != null && existingUpdate.contains(key)) {
                    continue;
                }

                String value = defaults.getString(key);
                if (value == null) {
                    continue;
                }
                additions.append("  ").append(key).append(": \"")
                    .append(escapeYaml(value)).append("\"")
                    .append(newline);
            }

            if (additions.isEmpty()) {
                return;
            }

            int sectionStart = findTopLevelSectionStart(content, "update");
            if (sectionStart < 0) {
                StringBuilder updated = new StringBuilder(content);
                if (!content.isEmpty() && !content.endsWith("\n") && !content.endsWith("\r")) {
                    updated.append(newline);
                }
                updated.append(newline).append("update:").append(newline).append(additions);
                Files.writeString(file, updated.toString(), StandardCharsets.UTF_8);
                return;
            }

            int sectionEnd = findNextTopLevelSection(content, sectionStart);
            if (sectionEnd < 0) {
                sectionEnd = content.length();
            }

            StringBuilder updated = new StringBuilder(content);
            if (sectionEnd > 0 && updated.charAt(sectionEnd - 1) != '\n' && updated.charAt(sectionEnd - 1) != '\r') {
                updated.insert(sectionEnd, newline);
                sectionEnd += newline.length();
            }
            updated.insert(sectionEnd, additions.toString());
            Files.writeString(file, updated.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not add missing language fields to " + file.getFileName() + ": " + exception.getMessage());
        }
    }

    private int findTopLevelSectionStart(String content, String section) {
        String marker = section + ":";
        int position = 0;
        while (position < content.length()) {
            int lineEnd = content.indexOf('\n', position);
            if (lineEnd < 0) {
                lineEnd = content.length();
            }
            String line = content.substring(position, lineEnd).replace("\r", "");
            if (line.startsWith(marker) && (line.length() == marker.length() || Character.isWhitespace(line.charAt(marker.length())))) {
                return position;
            }
            position = lineEnd + 1;
        }
        return -1;
    }

    private int findNextTopLevelSection(String content, int sectionStart) {
        int position = content.indexOf('\n', sectionStart);
        if (position < 0) {
            return -1;
        }
        position++;
        while (position < content.length()) {
            int lineEnd = content.indexOf('\n', position);
            if (lineEnd < 0) {
                lineEnd = content.length();
            }
            String line = content.substring(position, lineEnd).replace("\r", "");
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#") && line.contains(":")) {
                return position;
            }
            position = lineEnd + 1;
        }
        return -1;
    }

    private String escapeYaml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return "en_US";
        }

        String normalized = locale.replace('-', '_');
        String[] parts = normalized.split("_", 2);
        if (parts.length == 1) {
            return parts[0].toLowerCase(Locale.ROOT);
        }

        return parts[0].toLowerCase(Locale.ROOT) + "_" + parts[1].toUpperCase(Locale.ROOT);
    }

    private String colorize(String input) {
        return input == null ? "" : input.replace('&', '\u00A7');
    }
}
