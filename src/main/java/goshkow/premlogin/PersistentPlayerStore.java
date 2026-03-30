package goshkow.premlogin;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

public final class PersistentPlayerStore {

    private final PremiumLoginPlugin plugin;
    private final File file;
    private YamlConfiguration configuration;

    PersistentPlayerStore(PremiumLoginPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder.");
        }

        configuration = YamlConfiguration.loadConfiguration(file);
    }

    void save() {
        try {
            configuration.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to save persistent player data.", exception);
        }
    }

    public boolean isProtectedPremiumName(String nickname) {
        return configuration.getConfigurationSection("premium-profiles") != null
            && configuration.getConfigurationSection("premium-profiles").contains(normalize(nickname));
    }

    public void rememberPremiumProfile(String nickname, UUID premiumUuid) {
        String key = "premium-profiles." + normalize(nickname);
        configuration.set(key + ".last-known-name", nickname);
        configuration.set(key + ".uuid", premiumUuid.toString());
        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nickname).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        configuration.set("uuid-links.by-name." + normalize(nickname) + ".offline", offlineUuid.toString());
        configuration.set("uuid-links.by-name." + normalize(nickname) + ".premium", premiumUuid.toString());
        configuration.set("uuid-links.by-offline." + offlineUuid, premiumUuid.toString());
        configuration.set("uuid-links.by-premium." + premiumUuid, offlineUuid.toString());
        save();
    }

    public boolean isMigrationProcessed(String nickname) {
        return configuration.getBoolean("migration.processed." + normalize(nickname), false);
    }

    public void markMigrationProcessed(String nickname) {
        configuration.set("migration.processed." + normalize(nickname), true);
        configuration.set("migration.pending-manual." + normalize(nickname), false);
        save();
    }

    public void markRecentMigrationApplied(String nickname) {
        configuration.set("migration.recently-applied." + normalize(nickname), true);
        save();
    }

    public boolean consumeRecentMigrationApplied(String nickname) {
        String path = "migration.recently-applied." + normalize(nickname);
        boolean result = configuration.getBoolean(path, false);
        if (result) {
            configuration.set(path, false);
            save();
        }
        return result;
    }

    public boolean hasPendingManualMigration(String nickname) {
        return configuration.getBoolean("migration.pending-manual." + normalize(nickname), false);
    }

    public void markPendingManualMigration(String nickname) {
        configuration.set("migration.pending-manual." + normalize(nickname), true);
        save();
    }

    public void clearPendingManualMigration(String nickname) {
        configuration.set("migration.pending-manual." + normalize(nickname), false);
        save();
    }

    public List<String> getKnownPremiumNames() {
        if (configuration.getConfigurationSection("premium-profiles") == null) {
            return List.of();
        }

        return new ArrayList<>(configuration.getConfigurationSection("premium-profiles").getKeys(false));
    }

    public UUID getLinkedPremiumUuid(String nickname) {
        String raw = configuration.getString("uuid-links.by-name." + normalize(nickname) + ".premium");
        if (raw == null || raw.isBlank()) {
            return null;
        }

        return UUID.fromString(raw);
    }

    public UUID getLinkedOfflineUuid(String nickname) {
        String raw = configuration.getString("uuid-links.by-name." + normalize(nickname) + ".offline");
        if (raw == null || raw.isBlank()) {
            return null;
        }

        return UUID.fromString(raw);
    }

    private String normalize(String nickname) {
        return nickname.toLowerCase(Locale.ROOT);
    }
}
