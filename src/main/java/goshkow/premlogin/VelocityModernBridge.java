package goshkow.premlogin;

import goshkow.premlogin.bridge.PremiumAssertion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Receives and validates assertions delivered by the trusted Velocity proxy. */
final class VelocityModernBridge implements PluginMessageListener {

    private final PremiumLoginPlugin plugin;
    private final Map<UUID, PremiumAssertion> pendingAssertions = new ConcurrentHashMap<>();
    private String channel;
    private String sharedSecret;
    private boolean initialized;

    VelocityModernBridge(PremiumLoginPlugin plugin) {
        this.plugin = plugin;
    }

    boolean initialize() {
        if (!plugin.isVelocityModernMode()) {
            return false;
        }

        channel = plugin.getConfig().getString("premium-verification.velocity-modern.channel", "premiumbridge:auth");
        sharedSecret = plugin.getConfig().getString("premium-verification.velocity-modern.shared-secret", "");
        if (channel == null || channel.isBlank() || sharedSecret == null || sharedSecret.isBlank()
            || sharedSecret.equalsIgnoreCase("CHANGE_ME")) {
            plugin.getLogger().severe("velocity-modern is enabled but its shared secret is missing. Premium auto-login is fail-closed.");
            return false;
        }

        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, channel, this);
        initialized = true;
        return true;
    }

    void close() {
        if (initialized) {
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, channel, this);
        }
        pendingAssertions.clear();
        initialized = false;
    }

    PremiumAssertion consume(Player player) {
        if (!initialized) {
            return null;
        }

        PremiumAssertion assertion = pendingAssertions.remove(player.getUniqueId());
        if (assertion == null || !assertion.isValid(sharedSecret, System.currentTimeMillis())) {
            return null;
        }

        if (!assertion.username().equalsIgnoreCase(player.getName())
            || !assertion.premiumUuid().equals(player.getUniqueId())) {
            plugin.getLogger().warning("Rejected a Velocity assertion with an identity mismatch for " + player.getName());
            return null;
        }

        return assertion;
    }

    @Override
    public void onPluginMessageReceived(String incomingChannel, Player player, byte[] message) {
        if (!initialized || !channel.equals(incomingChannel)) {
            return;
        }

        PremiumAssertion assertion = PremiumAssertion.decode(message);
        if (assertion == null || !assertion.isValid(sharedSecret, System.currentTimeMillis()) || !assertion.premium()) {
            plugin.debugMessage("Rejected an invalid Velocity assertion from " + player.getName());
            return;
        }

        if (!assertion.username().equalsIgnoreCase(player.getName())
            || !assertion.premiumUuid().equals(player.getUniqueId())) {
            plugin.getLogger().warning("Rejected a Velocity assertion with an identity mismatch for " + player.getName());
            return;
        }

        pendingAssertions.put(player.getUniqueId(), assertion);
    }
}
