package goshkow.premlogin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

final class AuthPluginMessagePatcher {

    private final PremiumLoginPlugin plugin;

    AuthPluginMessagePatcher(PremiumLoginPlugin plugin) {
        this.plugin = plugin;
    }

    boolean apply() {
        if (!plugin.getConfig().getBoolean("auth-plugin.hide-service-messages-for-premium", true)) {
            return false;
        }

        boolean changed = false;
        changed |= patchAuthMe();
        changed |= patchOpenLogin();
        return changed;
    }

    void reloadProvidersIfNeeded(boolean changed) {
        if (!changed || !plugin.getConfig().getBoolean("auth-plugin.reload-provider-after-message-patch", true)) {
            return;
        }

        if (plugin.getServer().getPluginManager().getPlugin("AuthMe") != null) {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "authme reload"));
        }

        if (plugin.getServer().getPluginManager().getPlugin("OpeNLogin") != null) {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "openlogin reload"));
        }
    }

    private boolean patchAuthMe() {
        Plugin authMe = plugin.getServer().getPluginManager().getPlugin("AuthMe");
        if (authMe == null) {
            return false;
        }

        File messagesFolder = new File(authMe.getDataFolder(), "messages");
        if (!messagesFolder.isDirectory()) {
            return false;
        }

        File[] files = messagesFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return false;
        }

        List<String> keys = List.of("login.success", "login.valid_session");
        boolean changed = false;
        for (File file : files) {
            changed |= patchFile(file, keys, "");
        }
        return changed;
    }

    private boolean patchOpenLogin() {
        Plugin openLogin = plugin.getServer().getPluginManager().getPlugin("OpeNLogin");
        if (openLogin == null) {
            return false;
        }

        List<File> ymlFiles = new ArrayList<>();
        collectYamlFiles(openLogin.getDataFolder(), ymlFiles);
        boolean changed = false;
        List<String> keys = List.of(
            "Messages.Title.after-login.subtitle",
            "Messages.Title.after-register.subtitle",
            "Messages.successful-operations.successful-login",
            "Messages.successful-operations.successful-register"
        );
        for (File file : ymlFiles) {
            changed |= patchFile(file, keys, "");
        }
        return changed;
    }

    private void collectYamlFiles(File directory, List<File> target) {
        if (directory == null || !directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                collectYamlFiles(file, target);
            } else if (file.getName().toLowerCase(Locale.ROOT).endsWith(".yml")) {
                target.add(file);
            }
        }
    }

    private boolean patchFile(File file, List<String> keys, String replacement) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            boolean changed = false;
            for (String key : keys) {
                if (!yaml.contains(key)) {
                    continue;
                }

                String current = yaml.getString(key);
                if (replacement.equals(current)) {
                    continue;
                }

                yaml.set(key, replacement);
                changed = true;
            }

            if (changed) {
                yaml.save(file);
            }

            return changed;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to patch auth plugin message file: " + file.getAbsolutePath(), exception);
            return false;
        }
    }
}
