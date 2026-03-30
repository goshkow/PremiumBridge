package goshkow.premlogin;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

final class OpenLoginBridge implements AuthPluginBridge {

    private final Logger logger;
    private Plugin plugin;
    private Plugin openLoginPlugin;
    private Object apiInstance;
    private Object loginManagement;
    private Method isRegisteredMethod;
    private Method updateMethod;
    private Method isAuthenticatedMethod;
    private Method setAuthenticatedMethod;

    OpenLoginBridge(Logger logger) {
        this.logger = logger;
    }

    @Override
    public String getProviderId() {
        return "OPENLOGIN";
    }

    @Override
    public boolean initialize(Plugin plugin) {
        this.plugin = plugin;
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        openLoginPlugin = pluginManager.getPlugin("OpeNLogin");
        if (openLoginPlugin == null || !openLoginPlugin.isEnabled()) {
            return false;
        }

        try {
            Class<?> apiClass = Class.forName("com.nickuc.openlogin.common.api.OpenLoginAPI");
            Method getApiMethod = apiClass.getMethod("getApi");
            apiInstance = getApiMethod.invoke(null);
            isRegisteredMethod = apiClass.getMethod("isRegistered", String.class);
            updateMethod = apiClass.getMethod("update", String.class, String.class, String.class, boolean.class);

            Method getLoginManagementMethod = openLoginPlugin.getClass().getMethod("getLoginManagement");
            loginManagement = getLoginManagementMethod.invoke(openLoginPlugin);
            Class<?> loginManagementClass = loginManagement.getClass();
            isAuthenticatedMethod = loginManagementClass.getMethod("isAuthenticated", String.class);
            setAuthenticatedMethod = loginManagementClass.getMethod("setAuthenticated", String.class);
            return true;
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.WARNING, "Failed to hook into OpeNLogin API.", exception);
            return false;
        }
    }

    @Override
    public boolean isAuthenticated(Player player) {
        try {
            return (boolean) isAuthenticatedMethod.invoke(loginManagement, player.getName());
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.WARNING, "Failed to check OpeNLogin authentication state for " + player.getName(), exception);
            return false;
        }
    }

    @Override
    public boolean isRegistered(Player player) {
        try {
            return (boolean) isRegisteredMethod.invoke(apiInstance, player.getName());
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.WARNING, "Failed to check OpeNLogin registration state for " + player.getName(), exception);
            return false;
        }
    }

    @Override
    public boolean forceLogin(Player player) {
        try {
            setAuthenticatedMethod.invoke(loginManagement, player.getName());
            return true;
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.WARNING, "Failed to auto-authenticate " + player.getName() + " in OpeNLogin.", exception);
            return false;
        }
    }

    @Override
    public boolean register(Player player, String password, String email) {
        try {
            String address = player.getAddress() == null || player.getAddress().getAddress() == null
                ? null
                : player.getAddress().getAddress().getHostAddress();
            return (boolean) updateMethod.invoke(apiInstance, player.getName(), password, address, true);
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.WARNING, "Failed to auto-register " + player.getName() + " in OpeNLogin.", exception);
            return false;
        }
    }
}
