package goshkow.premlogin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

final class AuthMeBridge implements AuthPluginBridge {

    private final Logger logger;
    private Plugin plugin;
    private Object apiInstance;
    private Method isAuthenticatedMethod;
    private Method isRegisteredMethod;
    private Method forceLoginMethod;
    private Method registerPlayerMethod;
    private boolean registerMethodWithEmail;

    AuthMeBridge(Logger logger) {
        this.logger = logger;
    }

    @Override
    public String getProviderId() {
        return "AUTHME";
    }

    @Override
    public boolean initialize(Plugin plugin) {
        this.plugin = plugin;
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        Plugin authMePlugin = pluginManager.getPlugin("AuthMe");
        if (authMePlugin == null || !authMePlugin.isEnabled()) {
            logger.warning("AuthMe is not installed or not enabled. PremiumBridge will stay idle for AuthMe integration.");
            return false;
        }

        try {
            Class<?> apiClass = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Method getInstanceMethod = apiClass.getMethod("getInstance");
            apiInstance = getInstanceMethod.invoke(null);

            isAuthenticatedMethod = findMethod(apiClass, "isAuthenticated", Player.class);
            isRegisteredMethod = findMethod(apiClass, "isRegistered", String.class);
            forceLoginMethod = findMethod(apiClass, "forceLogin", Player.class);
            registerPlayerMethod = findMethod(apiClass, "registerPlayer", String.class, String.class);
            if (registerPlayerMethod == null) {
                registerPlayerMethod = findMethod(apiClass, "registerPlayer", String.class, String.class, String.class);
                registerMethodWithEmail = registerPlayerMethod != null;
            }

            if (apiInstance == null || isAuthenticatedMethod == null || isRegisteredMethod == null || forceLoginMethod == null) {
                logger.warning("AuthMe API methods were not found. Check your AuthMe version.");
                return false;
            }

            return true;
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.WARNING, "Failed to hook into AuthMe API.", exception);
            return false;
        }
    }

    @Override
    public boolean isAuthenticated(Player player) {
        try {
            return (boolean) isAuthenticatedMethod.invoke(apiInstance, player);
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.WARNING, "Failed to check AuthMe authentication state for " + player.getName(), exception);
            return false;
        }
    }

    @Override
    public boolean isRegistered(Player player) {
        try {
            return (boolean) isRegisteredMethod.invoke(apiInstance, player.getName());
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.WARNING, "Failed to check AuthMe registration state for " + player.getName(), exception);
            return false;
        }
    }

    @Override
    public boolean forceLogin(Player player) {
        if (plugin != null && plugin.getConfig().getBoolean("authme.use-forcelogin-command", true)) {
            return forceLoginViaConsole(player);
        }

        try {
            Object result = forceLoginMethod.invoke(apiInstance, player);
            return !(result instanceof Boolean booleanResult) || booleanResult;
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.WARNING, "Failed to force AuthMe login for " + player.getName(), exception);
            return forceLoginViaConsole(player);
        }
    }

    @Override
    public boolean register(Player player, String password, String email) {
        if (tryRegisterViaApi(player, password, email)) {
            return true;
        }

        return registerViaConsole(player, password);
    }

    private boolean tryRegisterViaApi(Player player, String password, String email) {
        if (registerPlayerMethod == null) {
            return false;
        }

        try {
            Object result;
            if (registerMethodWithEmail) {
                result = registerPlayerMethod.invoke(apiInstance, player.getName(), password, email);
            } else {
                result = registerPlayerMethod.invoke(apiInstance, player.getName(), password);
            }

            return !(result instanceof Boolean booleanResult) || booleanResult;
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.WARNING, "Failed to auto-register " + player.getName() + " through AuthMe API.", exception);
            return false;
        }
    }

    private boolean registerViaConsole(Player player, String password) {
        if (plugin == null) {
            return false;
        }

        String command = "authme forceregister " + player.getName() + " " + password;
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Failed to auto-register " + player.getName() + " through AuthMe command fallback.", exception);
            return false;
        }
    }

    private boolean forceLoginViaConsole(Player player) {
        if (plugin == null) {
            return false;
        }

        String command = "authme forcelogin " + player.getName();
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Failed to force AuthMe login for " + player.getName() + " through command fallback.", exception);
            return false;
        }
    }

    private Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }
}
