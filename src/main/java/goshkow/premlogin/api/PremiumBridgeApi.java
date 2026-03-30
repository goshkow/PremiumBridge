package goshkow.premlogin.api;

import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

public interface PremiumBridgeApi {

    String getPluginVersion();

    boolean hasAuthProvider();

    String getActiveAuthProvider();

    PremiumStatus getPremiumStatus(Player player);

    boolean isPremium(Player player);

    boolean isSecurePremium(Player player);

    boolean isKnownPremiumNickname(String nickname);

    Set<String> getKnownPremiumNicknames();

    boolean isMigrationProcessed(String nickname);

    UUID getLinkedPremiumUuid(String nickname);

    UUID getLinkedOfflineUuid(String nickname);

    boolean shouldSkipRegistrationForPremium();

    boolean hidesAuthMessagesForPremium();

    boolean isPremiumNameProtectionEnabled();
}
