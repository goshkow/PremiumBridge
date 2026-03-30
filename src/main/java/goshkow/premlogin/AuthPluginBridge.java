package goshkow.premlogin;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

interface AuthPluginBridge {

    String getProviderId();

    boolean initialize(Plugin plugin);

    boolean isAuthenticated(Player player);

    boolean isRegistered(Player player);

    boolean forceLogin(Player player);

    boolean register(Player player, String password, String email);
}
