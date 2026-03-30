package goshkow.premlogin;

import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.logging.Level;

final class OfflineDataMigrationService {

    private static final DateTimeFormatter BACKUP_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final PremiumLoginPlugin plugin;
    private final PersistentPlayerStore playerStore;

    OfflineDataMigrationService(PremiumLoginPlugin plugin, PersistentPlayerStore playerStore) {
        this.plugin = plugin;
        this.playerStore = playerStore;
    }

    MigrationPreview preview(String nickname, UUID premiumUuid) {
        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nickname).getBytes(StandardCharsets.UTF_8));
        boolean hasOfflineData = false;

        if (!offlineUuid.equals(premiumUuid)) {
            for (World world : plugin.getServer().getWorlds()) {
                if (hasData(new File(world.getWorldFolder(), "playerdata"), offlineUuid, ".dat")
                    || hasData(new File(world.getWorldFolder(), "stats"), offlineUuid, ".json")
                    || hasData(new File(world.getWorldFolder(), "advancements"), offlineUuid, ".json")) {
                    hasOfflineData = true;
                    break;
                }
            }
        }

        return new MigrationPreview(nickname, offlineUuid, premiumUuid, hasOfflineData);
    }

    void migrateIfNeeded(String nickname, UUID premiumUuid) {
        boolean pendingManual = playerStore.hasPendingManualMigration(nickname);
        if (playerStore.isMigrationProcessed(nickname)) {
            playerStore.clearPendingManualMigration(nickname);
            return;
        }

        MigrationPreview preview = preview(nickname, premiumUuid);
        if (!preview.hasOfflineData()) {
            if (plugin.getConfig().getBoolean("migration.offline-data.mark-even-if-empty", false)
                || preview.offlineUuid().equals(preview.premiumUuid())) {
                playerStore.markMigrationProcessed(nickname);
            }
            return;
        }

        MigrationResult result = migrate(preview, true);
        if (result.copiedAnything() || pendingManual) {
            playerStore.markRecentMigrationApplied(nickname);
        }
        if (!result.copiedAnything() && plugin.getConfig().getBoolean("migration.offline-data.mark-even-if-empty", false)) {
            playerStore.markMigrationProcessed(nickname);
        }
        playerStore.clearPendingManualMigration(nickname);
    }

    MigrationResult migrate(MigrationPreview preview, boolean markProcessed) {
        if (preview.offlineUuid().equals(preview.premiumUuid())) {
            if (markProcessed) {
                playerStore.markMigrationProcessed(preview.nickname());
            }
            return new MigrationResult(false, false);
        }

        boolean copiedAnything = false;
        for (World world : plugin.getServer().getWorlds()) {
            copiedAnything |= copyIfPresent(new File(world.getWorldFolder(), "playerdata"), preview.offlineUuid(), preview.premiumUuid(), ".dat");
            copiedAnything |= copyIfPresent(new File(world.getWorldFolder(), "stats"), preview.offlineUuid(), preview.premiumUuid(), ".json");
            copiedAnything |= copyIfPresent(new File(world.getWorldFolder(), "advancements"), preview.offlineUuid(), preview.premiumUuid(), ".json");
        }

        if (markProcessed && (copiedAnything || plugin.getConfig().getBoolean("migration.offline-data.mark-even-if-empty", false))) {
            playerStore.markMigrationProcessed(preview.nickname());
        }

        return new MigrationResult(preview.hasOfflineData(), copiedAnything);
    }

    void markSkipped(String nickname) {
        playerStore.markMigrationProcessed(nickname);
    }

    private boolean hasData(File directory, UUID sourceUuid, String extension) {
        return directory.isDirectory() && new File(directory, sourceUuid + extension).isFile();
    }

    private boolean copyIfPresent(File directory, UUID sourceUuid, UUID targetUuid, String extension) {
        if (!directory.isDirectory()) {
            return false;
        }

        File source = new File(directory, sourceUuid + extension);
        if (!source.isFile()) {
            return false;
        }

        File target = new File(directory, targetUuid + extension);

        try {
            if (target.isFile()) {
                if (!plugin.getConfig().getBoolean("migration.offline-data.replace-existing-target-before-first-success", true)) {
                    return false;
                }

                backupExistingTarget(target);
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return true;
            }

            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to migrate " + source.getAbsolutePath() + " to " + target.getAbsolutePath(), exception);
            return false;
        }
    }

    private void backupExistingTarget(File target) throws IOException {
        File backupDirectory = new File(plugin.getDataFolder(), "migration-backups");
        if (!backupDirectory.exists() && !backupDirectory.mkdirs()) {
            throw new IOException("Could not create backup directory: " + backupDirectory.getAbsolutePath());
        }

        String timestamp = LocalDateTime.now().format(BACKUP_TIME_FORMAT);
        File backup = new File(backupDirectory, target.getName() + "." + timestamp + ".bak");
        Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }
}
