package goshkow.premlogin.api;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class PremiumBridge {

    private PremiumBridge() {
    }

    public static PremiumBridgeApi getApi() {
        return getApi(Bukkit.getServer());
    }

    public static PremiumBridgeApi getApi(Server server) {
        if (server == null) {
            return null;
        }

        RegisteredServiceProvider<PremiumBridgeApi> registration =
            server.getServicesManager().getRegistration(PremiumBridgeApi.class);
        return registration == null ? null : registration.getProvider();
    }
}
