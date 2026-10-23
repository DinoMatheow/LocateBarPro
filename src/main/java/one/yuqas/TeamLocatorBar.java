package one.yuqas;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

public final class TeamLocatorBar extends JavaPlugin {

    private static final String RELOAD_PERMISSION = "teamlocatorbar.reload";

    private PlayerPrivacyService privacyService;
    private VisibilityDebugLogger visibilityDebugLogger;
    private ScheduledTask autosaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        visibilityDebugLogger = new VisibilityDebugLogger(
                getLogger(),
                () -> getConfig().getBoolean("debug", false)
        );

        boolean persistenceEnabled = getConfig().getBoolean("persistence.enabled", true);
        privacyService = new PlayerPrivacyService(this, getDataFolder().toPath(), persistenceEnabled);
        privacyService.reloadWorldRestriction();
        getServer().getPluginManager().registerEvents(privacyService, this);
        LocationBarListener listener = new LocationBarListener(this, privacyService);
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getGlobalRegionScheduler().runAtFixedRate(
                this,
                task -> privacyService.reconcileVisibleMarkers(),
                20L,
                20L
        );

        if (getCommand("teamlocatorbar") != null) {
            getCommand("teamlocatorbar").setExecutor(this);
        }
        if (getCommand("locator") != null) {
            getCommand("locator").setExecutor(new LocatorCommand(privacyService));
        }
        scheduleAutosave();
    }

    @Override
    public void onDisable() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        if (privacyService != null) {
            privacyService.shutdownAndSave();
        }
    }

    public PlayerPrivacyService getPrivacyService() {
        return privacyService;
    }

    void logDebugDecision(VisibilityDebugLogger.Decision decision) {
        visibilityDebugLogger.log(decision);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            sender.sendMessage("§cYou do not have permission to use this command!");
            return true;
        }

        reloadConfig();
        privacyService.reloadWorldRestriction();
        privacyService.reevaluateAllVisibleMarkers();
        if (getConfig().getBoolean("persistence.enabled", true) != privacyService.isPersistenceEnabled()) {
            getLogger().warning("Changing persistence.enabled requires a server restart.");
        }
        scheduleAutosave();
        sender.sendMessage("§x§2§D§F§F§4§Fᴛ§x§3§9§F§B§5§8ᴇ§x§4§4§F§6§6§1ᴀ§x§5§C§E§D§7§3ʟ§x§6§7§E§9§7§Cᴏ§x§7§3§E§4§8§5ᴄ§x§7§F§E§0§8§Fᴀ§x§8§B§D§B§9§8ᴛ§x§9§6§D§7§A§1ᴏ§x§A§2§D§2§A§Aʀ§x§A§E§C§E§B§3ʙ§x§B§9§C§9§B§Cᴀ§x§C§5§C§5§C§5ʀ " +
                "§f| §x§B§6§F§C§C§1Configuration reloaded successfully!");
        return true;
    }

    private void scheduleAutosave() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        if (privacyService == null || !privacyService.isPersistenceEnabled()) {
            return;
        }

        int minutes = getConfig().getInt("persistence.autosave-minutes", 5);
        if (minutes <= 0) {
            return;
        }

        autosaveTask = getServer().getAsyncScheduler().runAtFixedRate(
                this,
                task -> privacyService.saveAllAsync(),
                minutes,
                minutes,
                TimeUnit.MINUTES
        );
    }
}
