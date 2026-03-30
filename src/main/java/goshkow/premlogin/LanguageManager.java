package goshkow.premlogin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
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
        return fallback == null ? null : fallback.getString(key);
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
