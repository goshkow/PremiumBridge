package goshkow.premlogin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class AuthMeMessageSuppressor {

    private final PremiumLoginPlugin plugin;
    private final Map<UUID, Long> mutedUntil = new ConcurrentHashMap<>();
    private ProtocolManager protocolManager;

    AuthMeMessageSuppressor(PremiumLoginPlugin plugin) {
        this.plugin = plugin;
    }

    boolean initialize() {
        if (plugin.getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            plugin.getLogger().warning("ProtocolLib is not installed. Auth-plugin message suppression will be disabled.");
            return false;
        }

        protocolManager = ProtocolLibrary.getProtocolManager();
        protocolManager.addPacketListener(new PacketAdapter(
            plugin,
            ListenerPriority.HIGHEST,
            PacketType.Play.Server.SYSTEM_CHAT,
            PacketType.Play.Server.CHAT,
            PacketType.Play.Server.DISGUISED_CHAT,
            PacketType.Play.Server.SET_ACTION_BAR_TEXT,
            PacketType.Play.Server.SET_TITLE_TEXT,
            PacketType.Play.Server.SET_SUBTITLE_TEXT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (shouldMute(event.getPlayer())) {
                    event.setCancelled(true);
                }
            }
        });
        return true;
    }

    void beginPremiumLogin(Player player) {
        if (!plugin.isVerifiedPremiumSession(player)) {
            return;
        }

        mutedUntil.put(player.getUniqueId(), Long.MAX_VALUE);
    }

    void finishPremiumLogin(Player player) {
        if (!plugin.isVerifiedPremiumSession(player)) {
            mutedUntil.remove(player.getUniqueId());
            return;
        }

        long graceMillis = Math.max(0L, plugin.getConfig().getLong(
            "message-suppression.after-auto-login-millis", 2000L
        ));
        mutedUntil.put(player.getUniqueId(), System.currentTimeMillis() + graceMillis);
    }

    void clear(Player player) {
        mutedUntil.remove(player.getUniqueId());
    }

    private boolean shouldMute(Player player) {
        if (!plugin.isVerifiedPremiumSession(player)) {
            mutedUntil.remove(player.getUniqueId());
            return false;
        }

        Long until = mutedUntil.get(player.getUniqueId());
        if (until == null) {
            return false;
        }

        if (until != Long.MAX_VALUE && until <= System.currentTimeMillis()) {
            mutedUntil.remove(player.getUniqueId());
            return false;
        }

        return true;
    }
}
