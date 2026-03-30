package goshkow.premlogin;

import goshkow.premlogin.api.PremiumBridgeApi;
import goshkow.premlogin.api.PremiumStatus;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

final class PremiumBridgeApiImpl implements PremiumBridgeApi {

    private final PremiumLoginPlugin plugin;

    PremiumBridgeApiImpl(PremiumLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getPluginVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean hasAuthProvider() {
        return plugin.hasActiveAuthProvider();
    }

    @Override
    public String getActiveAuthProvider() {
        return plugin.getActiveAuthProviderId();
    }

    @Override
    public PremiumStatus getPremiumStatus(Player player) {
        if (player == null) {
            return PremiumStatus.NOT_PREMIUM;
        }

        PremiumCheckResult result = plugin.checkPremiumStatus(player);
        if (!result.premium()) {
            return PremiumStatus.NOT_PREMIUM;
        }

        return result.secure() ? PremiumStatus.PREMIUM_SECURE : PremiumStatus.PREMIUM_INSECURE;
    }

    @Override
    public boolean isPremium(Player player) {
        return getPremiumStatus(player).isPremium();
    }

    @Override
    public boolean isSecurePremium(Player player) {
        return getPremiumStatus(player).isSecure();
    }

    @Override
    public boolean isKnownPremiumNickname(String nickname) {
        return plugin.getPlayerStore().isProtectedPremiumName(nickname);
    }

    @Override
    public Set<String> getKnownPremiumNicknames() {
        return new LinkedHashSet<>(plugin.getPlayerStore().getKnownPremiumNames());
    }

    @Override
    public boolean isMigrationProcessed(String nickname) {
        return plugin.getPlayerStore().isMigrationProcessed(nickname);
    }

    @Override
    public UUID getLinkedPremiumUuid(String nickname) {
        return plugin.getPlayerStore().getLinkedPremiumUuid(nickname);
    }

    @Override
    public UUID getLinkedOfflineUuid(String nickname) {
        return plugin.getPlayerStore().getLinkedOfflineUuid(nickname);
    }

    @Override
    public boolean shouldSkipRegistrationForPremium() {
        return plugin.isPremiumRegistrationSkipped();
    }

    @Override
    public boolean hidesAuthMessagesForPremium() {
        return plugin.hidesAuthMessagesForPremium();
    }

    @Override
    public boolean isPremiumNameProtectionEnabled() {
        return plugin.isPremiumNameProtectionEnabled();
    }
}
