package goshkow.premlogin;

import goshkow.premlogin.api.PremiumBridgeApi;
import goshkow.premlogin.bridge.PremiumAssertion;
import goshkow.premlogin.protocol.PremiumProtocolVerifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PremiumLoginPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final char[] PASSWORD_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private PremiumVerificationService premiumVerificationService;
    private PremiumProtocolVerifier premiumProtocolVerifier;
    private AuthMeMessageSuppressor messageSuppressor;
    private AuthPluginBridge authPluginBridge;
    private LanguageManager languageManager;
    private PersistentPlayerStore playerStore;
    private OfflineDataMigrationService offlineDataMigrationService;
    private AddHeadPermissionService addHeadPermissionService;
    private VelocityModernBridge velocityModernBridge;
    private UpdateCheckerService updateCheckerService;
    private PremiumBridgeApi api;
    private BukkitTask authenticatedMovementRepairTask;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<UUID, PlayerRestoreState> restoreStates = new ConcurrentHashMap<>();
    private final Map<UUID, MigrationPreview> pendingMigrationChoices = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> recentMigrationApplied = new ConcurrentHashMap<>();
    private final Map<UUID, Long> movementRepairUntil = new ConcurrentHashMap<>();
    private final Set<UUID> verifiedPremiumSessions = ConcurrentHashMap.newKeySet();
    private boolean authPluginAvailable;
    private boolean suppressionAvailable;

    @Override
    public void onEnable() {
        boolean firstConfigCreation = !new java.io.File(getDataFolder(), "config.yml").isFile();
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        if (firstConfigCreation && getServer().getPluginManager().getPlugin("AddHeads") != null) {
            getConfig().set("integrations.addhead-premium.enabled", true);
        }
        saveConfig();

        languageManager = new LanguageManager(this);
        languageManager.initialize();

        playerStore = new PersistentPlayerStore(this);
        playerStore.load();
        authPluginBridge = initializeAuthProvider();
        authPluginAvailable = authPluginBridge != null;
        premiumVerificationService = new PremiumVerificationService(this);
        offlineDataMigrationService = new OfflineDataMigrationService(this, playerStore);
        addHeadPermissionService = new AddHeadPermissionService(this);
        velocityModernBridge = new VelocityModernBridge(this);
        velocityModernBridge.initialize();
        premiumProtocolVerifier = new PremiumProtocolVerifier(this);
        premiumProtocolVerifier.initialize();
        messageSuppressor = new AuthMeMessageSuppressor(this);
        suppressionAvailable = messageSuppressor.initialize();
        api = new PremiumBridgeApiImpl(this);
        getServer().getServicesManager().register(PremiumBridgeApi.class, api, this, ServicePriority.Normal);
        updateCheckerService = new UpdateCheckerService(this);
        updateCheckerService.start();
        authenticatedMovementRepairTask = Bukkit.getScheduler().runTaskTimer(
            this,
            this::repairAuthenticatedPlayersMovement,
            20L,
            20L
        );

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("premauthbridge") != null) {
            getCommand("premauthbridge").setExecutor(this);
            getCommand("premauthbridge").setTabCompleter(this);
        }

        getLogger().info("PremiumBridge enabled.");
    }

    @Override
    public void onDisable() {
        verifiedPremiumSessions.clear();
        if (velocityModernBridge != null) {
            velocityModernBridge.close();
        }
        if (updateCheckerService != null) {
            updateCheckerService.stop();
        }
        if (authenticatedMovementRepairTask != null) {
            authenticatedMovementRepairTask.cancel();
            authenticatedMovementRepairTask = null;
        }
        if (api != null) {
            getServer().getServicesManager().unregister(PremiumBridgeApi.class, api);
        }
        getLogger().info("PremiumBridge disabled.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (updateCheckerService != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (event.getPlayer().isOnline()) {
                    updateCheckerService.notifyPlayerIfPending(event.getPlayer());
                }
            }, 40L);
        }

        if (!authPluginAvailable) {
            return;
        }

        Player player = event.getPlayer();
        recentMigrationApplied.put(player.getUniqueId(), playerStore.consumeRecentMigrationApplied(player.getName()));
        restoreStates.put(player.getUniqueId(), new PlayerRestoreState(
            player.getLocation().clone(),
            player.getWalkSpeed(),
            player.getFlySpeed(),
            player.getAllowFlight(),
            player.isFlying(),
            player.getGameMode()
        ));
        movementRepairUntil.put(player.getUniqueId(), System.currentTimeMillis() + 5000L);
        int delayTicks = getConfig().getInt("authme.auto-login-delay-ticks", 10);

        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> handleJoin(player, 0), delayTicks);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        boolean pendingManualMigration = playerStore.hasPendingManualMigration(event.getName());
        if (getMigrationMode() != MigrationMode.AUTOMATIC && !pendingManualMigration) {
            return;
        }

        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + event.getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (offlineUuid.equals(event.getUniqueId())) {
            return;
        }

        offlineDataMigrationService.migrateIfNeeded(event.getName(), event.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (suppressionAvailable) {
            messageSuppressor.clear(event.getPlayer());
        }
        addHeadPermissionService.clear(event.getPlayer());
        restoreStates.remove(event.getPlayer().getUniqueId());
        pendingMigrationChoices.remove(event.getPlayer().getUniqueId());
        recentMigrationApplied.remove(event.getPlayer().getUniqueId());
        movementRepairUntil.remove(event.getPlayer().getUniqueId());
        verifiedPremiumSessions.remove(event.getPlayer().getUniqueId());
    }

    private void handleJoin(Player player, int velocityAttempt) {
        if (!player.isOnline()) {
            return;
        }

        if (!getConfig().getBoolean("premium-verification.enabled", true)) {
            return;
        }

        PremiumCheckResult premiumCheck;
        if (isVelocityModernMode()) {
            PremiumAssertion assertion = velocityModernBridge == null ? null : velocityModernBridge.consume(player);
            if (assertion == null) {
                int maxAttempts = Math.max(0, getConfig().getInt("premium-verification.velocity-modern.wait-attempts", 20));
                if (velocityAttempt < maxAttempts) {
                    long intervalTicks = Math.max(1L, getConfig().getLong("premium-verification.velocity-modern.wait-interval-ticks", 2L));
                    Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> handleJoin(player, velocityAttempt + 1), intervalTicks);
                } else {
                    debugMessage(player.getName() + " has no valid Velocity assertion; standalone premium detection is disabled in velocity-modern mode.");
                }
                return;
            }

            premiumCheck = PremiumCheckResult.secure("verified by Velocity modern forwarding and HMAC assertion");
        } else {
            premiumCheck = premiumVerificationService.check(player);
        }

        if (!premiumCheck.premium()) {
            debugMessage(player.getName() + " is not treated as premium: " + premiumCheck.reason());
            return;
        }

        if (!premiumCheck.secure() && !getConfig().getBoolean("premium-verification.allow-insecure-auto-login", false)) {
            debugMessage(player.getName() + " matched only insecure premium verification. Auto-login skipped.");
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> processVerifiedPremiumJoin(player, premiumCheck));
    }

    private void processVerifiedPremiumJoin(Player player, PremiumCheckResult premiumCheck) {
        if (!player.isOnline()) {
            return;
        }

        if (premiumCheck.secure()) {
            verifiedPremiumSessions.add(player.getUniqueId());
            playerStore.rememberPremiumProfile(player.getName(), player.getUniqueId());
        } else {
            verifiedPremiumSessions.remove(player.getUniqueId());
        }

        addHeadPermissionService.apply(player);

        boolean registered = authPluginBridge.isRegistered(player);
        if (!registered) {
            if (!shouldSkipPremiumRegistration()) {
                debugMessage(player.getName() + " is premium but still must register manually.");
                return;
            }

            if (suppressionAvailable && getConfig().getBoolean("auth-plugin.hide-service-messages-for-premium", true)) {
                messageSuppressor.mark(player);
            }

            String generatedPassword = generatePassword();
            String generatedEmail = buildSyntheticEmail(player);
            boolean registerResult = authPluginBridge.register(player, generatedPassword, generatedEmail);
            if (!registerResult) {
                debugMessage(player.getName() + " auto-registration failed.");
                return;
            }

            registered = true;
            debugMessage(player.getName() + " was auto-registered for premium-only access.");
        }

        prepareMigrationPrompt(player, premiumCheck);

        if (registered && suppressionAvailable && getConfig().getBoolean("auth-plugin.hide-service-messages-for-premium", true)) {
            messageSuppressor.mark(player);
        }

        if (authPluginBridge.isAuthenticated(player)) {
            debugMessage(player.getName() + " is already authenticated in " + authPluginBridge.getProviderId() + ". No forced login needed.");
            restorePostAuthState(player);
            scheduleMigrationPromptIfNeeded(player);
            return;
        }

        boolean loginResult = authPluginBridge.forceLogin(player);
        if (loginResult) {
            restorePostAuthState(player);
            scheduleMigrationPromptIfNeeded(player);
        }
        debugMessage(player.getName() + " forceLogin result=" + loginResult + ", provider=" + authPluginBridge.getProviderId() + ", secure=" + premiumCheck.secure() + ", reason=" + premiumCheck.reason());
    }

    void debugMessage(String message) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[debug] " + message);
        }
    }

    private String generatePassword() {
        int length = Math.max(16, getConfig().getInt("registration.auto-register-password-length", 32));
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append(PASSWORD_ALPHABET[secureRandom.nextInt(PASSWORD_ALPHABET.length)]);
        }
        return builder.toString();
    }

    private String buildSyntheticEmail(Player player) {
        String domain = getConfig().getString("registration.synthetic-email-domain", "premium.local");
        String localPart = player.getUniqueId().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        return player.getName().toLowerCase(Locale.ROOT) + "+" + localPart + "@" + domain;
    }

    private boolean shouldSkipPremiumRegistration() {
        if (getConfig().contains("registration.skip-for-premium")) {
            return getConfig().getBoolean("registration.skip-for-premium", true);
        }

        return !getConfig().getBoolean("registration.require-premium-registration", false);
    }

    private void prepareMigrationPrompt(Player player, PremiumCheckResult premiumCheck) {
        pendingMigrationChoices.remove(player.getUniqueId());

        if (!premiumCheck.secure() || getMigrationMode() != MigrationMode.ASK_PLAYER) {
            return;
        }

        if (playerStore.isMigrationProcessed(player.getName())) {
            return;
        }

        MigrationPreview preview = offlineDataMigrationService.preview(player.getName(), player.getUniqueId());
        if (!preview.hasOfflineData()) {
            if (getConfig().getBoolean("migration.offline-data.mark-even-if-empty", false)) {
                playerStore.markMigrationProcessed(player.getName());
            }
            return;
        }

        pendingMigrationChoices.put(player.getUniqueId(), preview);
    }

    private void scheduleMigrationPromptIfNeeded(Player player) {
        if (getMigrationMode() != MigrationMode.ASK_PLAYER) {
            return;
        }

        MigrationPreview preview = pendingMigrationChoices.get(player.getUniqueId());
        if (preview == null || !player.isOnline()) {
            return;
        }

        long delay = Math.max(20L, getConfig().getLong("migration.offline-data.prompt-delay-ticks", 40L));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }

            MigrationPreview current = pendingMigrationChoices.get(player.getUniqueId());
            if (current == null) {
                return;
            }

            sendMigrationPrompt(player, current);
        }, delay);
    }

    private void sendMigrationPrompt(Player player, MigrationPreview preview) {
        Map<String, String> placeholders = Map.of("player", player.getName());
        player.sendMessage(languageManager.component(player, "migration.prompt.line-1", placeholders));
        player.sendMessage(languageManager.component(player, "migration.prompt.line-2", placeholders));
        player.sendMessage(languageManager.component(player, "migration.prompt.line-3", placeholders));

        Component yes = languageManager.component(player, "migration.prompt.button-yes")
            .clickEvent(ClickEvent.runCommand("/premauthbridge migrate accept"))
            .hoverEvent(HoverEvent.showText(languageManager.component(player, "migration.prompt.button-yes-hover")));
        Component separator = languageManager.component(player, "migration.prompt.button-separator");
        Component no = languageManager.component(player, "migration.prompt.button-no")
            .clickEvent(ClickEvent.runCommand("/premauthbridge migrate decline"))
            .hoverEvent(HoverEvent.showText(languageManager.component(player, "migration.prompt.button-no-hover")));

        player.sendMessage(yes.append(separator).append(no));
    }

    private void acceptMigration(Player player) {
        MigrationPreview preview = pendingMigrationChoices.remove(player.getUniqueId());
        if (preview == null) {
            player.sendMessage(languageManager.text(player, "migration.no-pending-choice", Map.of()));
            return;
        }

        if (!preview.hasOfflineData()) {
            player.sendMessage(languageManager.text(player, "migration.no-data", Map.of()));
            return;
        }

        playerStore.markPendingManualMigration(preview.nickname());
        player.sendMessage(languageManager.text(player, "migration.completed", Map.of()));
        player.sendMessage(languageManager.text(player, "migration.reconnect-required", Map.of()));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                player.kickPlayer(languageManager.text(player, "migration.reconnect-kick", Map.of()));
            }
        }, 20L);
    }

    private void declineMigration(Player player) {
        MigrationPreview preview = pendingMigrationChoices.remove(player.getUniqueId());
        if (preview == null) {
            player.sendMessage(languageManager.text(player, "migration.no-pending-choice", Map.of()));
            return;
        }

        playerStore.clearPendingManualMigration(preview.nickname());
        offlineDataMigrationService.markSkipped(preview.nickname());
        player.sendMessage(languageManager.text(player, "migration.skipped", Map.of()));
    }

    private void restorePostAuthState(Player player) {
        movementRepairUntil.put(player.getUniqueId(), System.currentTimeMillis() + 5000L);
        if (suppressionAvailable && getConfig().getBoolean("auth-plugin.hide-service-messages-for-premium", true)) {
            messageSuppressor.mark(player);
        }

        long delayTicks = Math.max(1L, getConfig().getLong("authme.restore-location-delay-ticks", 2L));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }

            PlayerRestoreState restoreState = restoreStates.get(player.getUniqueId());
            if (restoreState != null) {
                boolean migratedThisJoin = recentMigrationApplied.getOrDefault(player.getUniqueId(), false);
                if (migratedThisJoin) {
                    player.setWalkSpeed((float) getConfig().getDouble("authme.restore-walk-speed", 0.2D));
                    player.setFlySpeed((float) getConfig().getDouble("authme.restore-fly-speed", 0.1D));
                } else if (getConfig().getBoolean("migration.offline-data.restore-join-location", true)) {
                    player.teleport(restoreState.location());
                }

                restoreMovementState(player, restoreState);
            }
            restoreBrokenMovementSpeed(player);
        }, delayTicks);

        long safetyRepeats = Math.max(10L, getConfig().getLong("authme.restore-movement-retries", 10L));
        for (long attempt = 1; attempt <= safetyRepeats; attempt++) {
            long retryDelay = delayTicks + attempt * Math.max(1L, getConfig().getLong("authme.restore-movement-interval-ticks", 10L));
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!player.isOnline()) {
                    return;
                }

                PlayerRestoreState restoreState = restoreStates.get(player.getUniqueId());
                if (restoreState != null) {
                    restoreMovementState(player, restoreState);
                }
                restoreBrokenMovementSpeed(player);
            }, retryDelay);
        }
    }

    private void restoreMovementState(Player player, PlayerRestoreState restoreState) {
        boolean migratedThisJoin = recentMigrationApplied.getOrDefault(player.getUniqueId(), false);
        float walkSpeed;
        float flySpeed;

        if (migratedThisJoin) {
            walkSpeed = (float) getConfig().getDouble("authme.restore-walk-speed", 0.2D);
            flySpeed = (float) getConfig().getDouble("authme.restore-fly-speed", 0.1D);
        } else {
            walkSpeed = getConfig().getDouble("authme.restore-walk-speed", restoreState.walkSpeed()) <= 0.0D
                ? restoreState.walkSpeed()
                : (float) getConfig().getDouble("authme.restore-walk-speed", restoreState.walkSpeed());
            flySpeed = getConfig().getDouble("authme.restore-fly-speed", restoreState.flySpeed()) <= 0.0D
                ? restoreState.flySpeed()
                : (float) getConfig().getDouble("authme.restore-fly-speed", restoreState.flySpeed());
        }

        player.setWalkSpeed(sanitizeSpeed(walkSpeed, 0.2F));
        player.setFlySpeed(sanitizeSpeed(flySpeed, 0.1F));
        player.setAllowFlight(restoreState.allowFlight() || restoreState.gameMode() == org.bukkit.GameMode.CREATIVE || restoreState.gameMode() == org.bukkit.GameMode.SPECTATOR);

        if (player.getAllowFlight()) {
            player.setFlying(restoreState.flying());
        }
    }

    private void restoreBrokenMovementSpeed(Player player) {
        float minimumWalkSpeed = sanitizeThreshold(getConfig().getDouble("authme.minimum-walk-speed", 0.01D), 0.01F);
        float minimumFlySpeed = sanitizeThreshold(getConfig().getDouble("authme.minimum-fly-speed", 0.01D), 0.01F);

        if (!Float.isFinite(player.getWalkSpeed()) || player.getWalkSpeed() <= minimumWalkSpeed) {
            player.setWalkSpeed(sanitizeSpeed((float) getConfig().getDouble("authme.restore-walk-speed", 0.2D), 0.2F));
        }
        if (!Float.isFinite(player.getFlySpeed()) || player.getFlySpeed() <= minimumFlySpeed) {
            player.setFlySpeed(sanitizeSpeed((float) getConfig().getDouble("authme.restore-fly-speed", 0.1D), 0.1F));
        }
    }

    private void repairAuthenticatedPlayersMovement() {
        if (!authPluginAvailable || authPluginBridge == null) {
            return;
        }

        long now = System.currentTimeMillis();
        movementRepairUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        for (Player player : Bukkit.getOnlinePlayers()) {
            Long repairUntil = movementRepairUntil.get(player.getUniqueId());
            if (repairUntil == null || repairUntil <= now) {
                continue;
            }

            try {
                if (authPluginBridge.isAuthenticated(player)) {
                    restoreBrokenMovementSpeed(player);
                }
            } catch (RuntimeException exception) {
                debugMessage("Could not repair movement speed for " + player.getName() + ": " + exception.getMessage());
            }
        }
    }

    private float sanitizeSpeed(float value, float fallback) {
        if (!Float.isFinite(value) || value <= 0.0F) {
            return fallback;
        }
        return Math.min(1.0F, value);
    }

    private float sanitizeThreshold(double value, float fallback) {
        if (!Double.isFinite(value) || value < 0.0D) {
            return fallback;
        }
        return Math.min(1.0F, (float) value);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            getConfig().options().copyDefaults(true);
            saveConfig();
            languageManager.reload();
            authPluginBridge = initializeAuthProvider();
            authPluginAvailable = authPluginBridge != null;
            premiumVerificationService = new PremiumVerificationService(this);
            playerStore.load();
            if (velocityModernBridge != null) {
                velocityModernBridge.close();
            }
            velocityModernBridge = new VelocityModernBridge(this);
            velocityModernBridge.initialize();
            if (suppressionAvailable) {
                messageSuppressor.reloadPatterns();
                messageSuppressor.reloadKnownPluginMessages();
            }
            if (updateCheckerService != null) {
                updateCheckerService.restart();
            }
            sender.sendMessage(languageManager.text(sender, "command.reload-success", Map.of("label", label)));
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("migrate") && sender instanceof Player player) {
            if (args[1].equalsIgnoreCase("accept") || args[1].equalsIgnoreCase("yes")) {
                acceptMigration(player);
                return true;
            }

            if (args[1].equalsIgnoreCase("decline") || args[1].equalsIgnoreCase("no")) {
                declineMigration(player);
                return true;
            }

            return true;
        }

        sender.sendMessage(languageManager.text(sender, "command.usage", Map.of("label", label)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String current = args[0].toLowerCase(Locale.ROOT);
            if ("reload".startsWith(current)) {
                return Collections.singletonList("reload");
            }
            if ("migrate".startsWith(current)) {
                return Collections.singletonList("migrate");
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) {
            String current = args[1].toLowerCase(Locale.ROOT);
            List<String> options = List.of("accept", "decline");
            return options.stream().filter(option -> option.startsWith(current)).toList();
        }
        return Collections.emptyList();
    }

    public PersistentPlayerStore getPlayerStore() {
        return playerStore;
    }

    public PremiumBridgeApi getApi() {
        return api;
    }

    PremiumCheckResult checkPremiumStatus(Player player) {
        return premiumVerificationService.check(player);
    }

    boolean hasActiveAuthProvider() {
        return authPluginAvailable && authPluginBridge != null;
    }

    public boolean isVelocityModernMode() {
        return "velocity-modern".equalsIgnoreCase(
            getConfig().getString("premium-verification.mode", "standalone")
        );
    }

    String getActiveAuthProviderId() {
        return authPluginBridge == null ? null : authPluginBridge.getProviderId();
    }

    boolean isPremiumRegistrationSkipped() {
        return shouldSkipPremiumRegistration();
    }

    boolean hidesAuthMessagesForPremium() {
        return getConfig().getBoolean("auth-plugin.hide-service-messages-for-premium", true);
    }

    boolean isVerifiedPremiumSession(Player player) {
        return player != null && verifiedPremiumSessions.contains(player.getUniqueId());
    }

    boolean isPremiumNameProtectionEnabled() {
        return getConfig().getBoolean("premium-verification.protect-known-premium-names.enabled", false);
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public long getUpdateCheckIntervalTicks() {
        long hours = Math.max(1L, getConfig().getLong("update-check.interval-hours", 6L));
        return hours * 60L * 60L * 20L;
    }

    boolean shouldReceiveUpdateNotifications(Player player) {
        return player != null && (player.isOp() || player.hasPermission("premauthbridge.reload"));
    }

    MigrationMode getMigrationMode() {
        if (!getConfig().getBoolean("migration.offline-data.enabled", true)) {
            return MigrationMode.DISABLED;
        }

        return MigrationMode.fromConfigValue(getConfig().getString("migration.offline-data.mode", "automatic"));
    }

    private AuthPluginBridge initializeAuthProvider() {
        String preferred = getConfig().getString("auth-plugin.preferred", "AUTO");
        if (preferred == null) {
            preferred = "AUTO";
        }

        AuthPluginBridge selected = selectBridge(preferred);
        if (selected != null) {
            getLogger().info("Using auth provider: " + selected.getProviderId());
            return selected;
        }

        if (getServer().getPluginManager().getPlugin("xAuth") != null) {
            getLogger().warning("xAuth was detected, but it is a legacy plugin from the CraftBukkit 1.7.x era and is not supported by this project on 1.21.x.");
        }

        getLogger().warning("No supported authentication plugin was detected. Supported providers: AuthMe, OpeNLogin.");
        return null;
    }

    private AuthPluginBridge selectBridge(String preferred) {
        if (!"AUTO".equalsIgnoreCase(preferred)) {
            AuthPluginBridge forced = createBridge(preferred.toUpperCase(Locale.ROOT));
            if (forced != null && forced.initialize(this)) {
                return forced;
            }
        }

        AuthPluginBridge authMe = createBridge("AUTHME");
        if (authMe != null && authMe.initialize(this)) {
            return authMe;
        }

        AuthPluginBridge openLogin = createBridge("OPENLOGIN");
        if (openLogin != null && openLogin.initialize(this)) {
            return openLogin;
        }

        return null;
    }

    private AuthPluginBridge createBridge(String providerId) {
        return switch (providerId) {
            case "AUTHME" -> new AuthMeBridge(getLogger());
            case "OPENLOGIN" -> new OpenLoginBridge(getLogger());
            default -> null;
        };
    }
}
