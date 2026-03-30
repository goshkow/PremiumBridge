package goshkow.premlogin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class AuthMeMessageSuppressor {

    private final PremiumLoginPlugin plugin;
    private final Map<UUID, Long> suppressionUntil = new ConcurrentHashMap<>();
    private final Set<String> normalizedAuthMeMessages = new ConcurrentSkipListSet<>();
    private ProtocolManager protocolManager;
    private List<Pattern> patterns;

    AuthMeMessageSuppressor(PremiumLoginPlugin plugin) {
        this.plugin = plugin;
    }

    boolean initialize() {
        if (plugin.getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            plugin.getLogger().warning("ProtocolLib is not installed. AuthMe message suppression will be disabled.");
            return false;
        }

        reloadPatterns();
        reloadKnownPluginMessages();
        protocolManager = ProtocolLibrary.getProtocolManager();
        protocolManager.addPacketListener(new PacketAdapter(
            plugin,
            ListenerPriority.HIGHEST,
            PacketType.Play.Server.SYSTEM_CHAT,
            PacketType.Play.Server.CHAT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Player player = event.getPlayer();
                if (!shouldSuppress(player)) {
                    return;
                }

                List<String> candidates = extractMessageCandidates(event.getPacket());
                if (candidates.isEmpty()) {
                    return;
                }

                for (String candidate : candidates) {
                    String normalized = candidate.toLowerCase(Locale.ROOT);
                    if (matchesKnownAuthMeMessage(normalized) || matchesAny(normalized)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        });
        return true;
    }

    void reloadPatterns() {
        patterns = plugin.getConfig().getStringList("message-suppression.patterns").stream()
            .map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
            .collect(Collectors.toList());
    }

    void reloadKnownPluginMessages() {
        normalizedAuthMeMessages.clear();

        loadMessagesFromAuthMe();
        loadMessagesFromOpenLogin();
    }

    private void loadMessagesFromAuthMe() {
        Plugin authMe = plugin.getServer().getPluginManager().getPlugin("AuthMe");
        if (authMe == null) {
            return;
        }

        File messagesFolder = new File(authMe.getDataFolder(), "messages");
        if (!messagesFolder.isDirectory()) {
            return;
        }

        File[] files = messagesFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            collectStrings(yaml, "");
        }
    }

    private void loadMessagesFromOpenLogin() {
        Plugin openLogin = plugin.getServer().getPluginManager().getPlugin("OpeNLogin");
        if (openLogin == null) {
            return;
        }

        List<File> files = new ArrayList<>();
        collectYamlFiles(openLogin.getDataFolder(), files);
        for (File file : files) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            collectStrings(yaml, "");
        }
    }

    void mark(Player player) {
        long durationMillis = plugin.getConfig().getLong("message-suppression.window-millis", 30000L);
        suppressionUntil.put(player.getUniqueId(), System.currentTimeMillis() + durationMillis);
    }

    void clear(Player player) {
        suppressionUntil.remove(player.getUniqueId());
    }

    private boolean shouldSuppress(Player player) {
        Long until = suppressionUntil.get(player.getUniqueId());
        if (until == null) {
            return false;
        }

        if (until < System.currentTimeMillis()) {
            suppressionUntil.remove(player.getUniqueId());
            return false;
        }

        return true;
    }

    private boolean matchesAny(String message) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(message).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesKnownAuthMeMessage(String message) {
        String normalizedCandidate = normalizeMessage(message);
        if (normalizedCandidate.isBlank()) {
            return false;
        }

        for (String knownMessage : normalizedAuthMeMessages) {
            if (knownMessage.length() < 6) {
                continue;
            }

            if (normalizedCandidate.contains(knownMessage)) {
                return true;
            }
        }

        return false;
    }

    private List<String> extractMessageCandidates(PacketContainer packet) {
        List<String> messages = new ArrayList<>();

        List<WrappedChatComponent> components = packet.getChatComponents().getValues();
        for (WrappedChatComponent component : components) {
            if (component == null) {
                continue;
            }

            String json = component.getJson();
            if (json != null && !json.isBlank()) {
                messages.add(json);
                messages.add(stripJsonFormatting(json));
            }
        }

        List<String> strings = packet.getStrings().getValues();
        for (String value : strings) {
            if (value != null && !value.isBlank()) {
                messages.add(value);
            }
        }

        List<Object> rawValues = packet.getModifier().getValues();
        for (Object value : rawValues) {
            if (value == null) {
                continue;
            }

            String asText = value.toString();
            if (!asText.isBlank()) {
                messages.add(asText);
            }
        }

        return messages;
    }

    private String stripJsonFormatting(String value) {
        return value
            .replaceAll("\\\\u00A7.", " ")
            .replaceAll("\"(translate|color|bold|italic|underlined|strikethrough|obfuscated)\":\"[^\"]*\"", " ")
            .replaceAll("\"(with|extra)\":\\[[^\\]]*\\]", " ")
            .replaceAll("[\\{\\}\\[\\]\"]", " ")
            .replaceAll("\\\\n", " ")
            .replaceAll("\\\\", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private void collectStrings(ConfigurationSection section, String path) {
        for (String key : section.getKeys(false)) {
            String childPath = path.isEmpty() ? key : path + "." + key;
            Object value = section.get(key);
            if (value instanceof ConfigurationSection childSection) {
                collectStrings(childSection, childPath);
                continue;
            }

            if (!(value instanceof String text)) {
                continue;
            }

            if (!shouldTrackKey(childPath)) {
                continue;
            }

            String normalized = normalizeMessage(text);
            if (!normalized.isBlank()) {
                normalizedAuthMeMessages.add(normalized);
            }
        }
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

    private boolean shouldTrackKey(String path) {
        String lowered = path.toLowerCase(Locale.ROOT);
        return lowered.contains("login.success")
            || lowered.contains("login.login_request")
            || lowered.contains("login.logged_in")
            || lowered.contains("login.valid_session")
            || lowered.contains("registration.success")
            || lowered.contains("registration.register_request")
            || lowered.contains("registration.usage")
            || lowered.contains("login.command_usage")
            || lowered.contains("messages.title.after-login.subtitle")
            || lowered.contains("messages.title.after-register.subtitle")
            || lowered.contains("messages.successful-operations.successful-login")
            || lowered.contains("messages.successful-operations.successful-register")
            || lowered.contains("messages.other-messages.message-login")
            || lowered.contains("messages.other-messages.message-register");
    }

    private String normalizeMessage(String value) {
        return stripJsonFormatting(value)
            .replaceAll("&[0-9A-FK-ORa-fk-or]", " ")
            .replaceAll("\u00A7[0-9A-FK-ORa-fk-or]", " ")
            .replaceAll("\\{[^}]+}", " ")
            .replaceAll("[^\\p{L}\\p{N}/<> ]", " ")
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
    }
}
