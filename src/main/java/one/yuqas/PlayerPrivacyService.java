package one.yuqas;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class PlayerPrivacyService implements Listener {

    private final TeamLocatorBar plugin;
    private final PlayerPreferenceStore preferences;
    private final ScoreboardTeamResolver teamResolver;
    private final ConcurrentMap<UUID, Set<UUID>> visibleTransmitters = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> pendingPreLogins = new ConcurrentHashMap<>();
    private final ConcurrentMap<AsyncPlayerPreLoginEvent, Long> preLoginAttempts = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong preLoginSequence = new java.util.concurrent.atomic.AtomicLong();
    private final boolean persistenceEnabled;
    private final ExecutorService persistenceExecutor;
    private volatile WorldRestriction worldRestriction = WorldRestriction.defaults();

    public PlayerPrivacyService(TeamLocatorBar plugin) {
        this(plugin, null, false);
    }

    public PlayerPrivacyService(TeamLocatorBar plugin, java.nio.file.Path dataFolder, boolean persistenceEnabled) {
        this.plugin = plugin;
        this.persistenceEnabled = persistenceEnabled;
        PlayerPreferencePersistence persistence = persistenceEnabled
                ? new PlayerPreferencePersistence(dataFolder, plugin.getLogger())
                : null;
        this.preferences = new PlayerPreferenceStore(persistence);
        this.persistenceExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "TeamLocatorBar-persistence");
            thread.setDaemon(true);
            return thread;
        });
        this.teamResolver = new ScoreboardTeamResolver(plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void loadPreferencesOnPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID playerId = event.getUniqueId();
        loadPreferences(playerId);
        long attempt = preLoginSequence.incrementAndGet();
        pendingPreLogins.put(playerId, attempt);
        preLoginAttempts.put(event, attempt);
        CompletableFuture.delayedExecutor(1, TimeUnit.MINUTES)
                .execute(() -> discardUnjoinedPreLogin(playerId, attempt));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void discardRejectedPreLogin(AsyncPlayerPreLoginEvent event) {
        Long attempt = preLoginAttempts.remove(event);
        if (attempt != null && event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED
                && pendingPreLogins.remove(event.getUniqueId(), attempt)) {
            preferences.unloadWithoutSave(event.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        pendingPreLogins.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        releasePlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        reevaluatePlayerAfterWorldChange(event.getPlayer());
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null && !event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            reevaluatePlayerAfterWorldChange(event.getPlayer());
        }
    }

    public VisibilityMode getTransmissionMode(UUID playerId) {
        return preferences.get(playerId).transmission();
    }

    public VisibilityMode getLastTransmissionMode(UUID playerId) {
        return preferences.get(playerId).lastTransmissionMode();
    }

    public boolean isTransmissionEnabled(UUID playerId) {
        return preferences.get(playerId).transmissionEnabled();
    }

    public VisibilityMode getReceptionMode(UUID playerId) {
        return preferences.get(playerId).reception();
    }

    public void setTransmissionMode(UUID playerId, VisibilityMode mode) {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(mode);
        preferences.setTransmissionMode(playerId, mode);
        saveAsync(playerId);
        scheduleRevocation(() -> revokeUnauthorizedTransmitters(playerId));
    }

    public void setTransmissionEnabled(UUID playerId, boolean enabled) {
        Objects.requireNonNull(playerId);
        preferences.setTransmissionEnabled(playerId, enabled);
        saveAsync(playerId);
        if (!enabled) {
            scheduleRevocation(() -> revokeUnauthorizedTransmitters(playerId));
        }
    }

    public void setReceptionMode(UUID playerId, VisibilityMode mode) {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(mode);
        preferences.setReceptionMode(playerId, mode);
        saveAsync(playerId);
        scheduleRevocation(() -> revokeUnauthorizedForReceiver(playerId));
    }

    public void reloadWorldRestriction() {
        worldRestriction = WorldRestriction.fromConfig(
                plugin.getConfig().get("worlds.mode"),
                plugin.getConfig().get("worlds.list"),
                plugin.getLogger()
        );
    }

    public boolean isWorldRestricted(Player player) {
        return worldRestriction.isRestricted(player.getWorld().getName());
    }

    public void reevaluateAllVisibleMarkers() {
        if (Bukkit.isPrimaryThread()) {
            reconcileVisibleMarkers();
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, this::reconcileVisibleMarkers);
        }
    }

    private void loadPreferences(UUID playerId) {
        if (!persistenceEnabled) {
            return;
        }

        boolean interrupted = false;
        try {
            Future<?> load = persistenceExecutor.submit(() -> preferences.load(playerId));
            while (true) {
                try {
                    load.get();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                    plugin.getLogger().log(Level.WARNING,
                            "Interrupted while waiting for preferences for " + playerId + "; continuing the async load.",
                            exception);
                }
            }
        } catch (ExecutionException | java.util.concurrent.RejectedExecutionException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not load preferences for " + playerId
                    + "; defaults will be used.", exception);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void saveAllAsync() {
        if (persistenceEnabled) {
            submitPersistence(preferences::saveAll, "Could not save player preferences.");
        }
    }

    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    public <T> T runPersistenceTask(Callable<T> task, String failureMessage) {
        Future<T> queued;
        try {
            queued = persistenceExecutor.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            plugin.getLogger().log(Level.WARNING, failureMessage, exception);
            throw new IllegalStateException(failureMessage, exception);
        }

        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return queued.get();
                } catch (InterruptedException exception) {
                    interrupted = true;
                } catch (ExecutionException exception) {
                    plugin.getLogger().log(Level.WARNING, failureMessage, exception.getCause());
                    throw new IllegalStateException(failureMessage, exception.getCause());
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void submitPersistenceTask(Runnable task, String failureMessage) {
        submitPersistence(task, failureMessage);
    }

    public void releasePlayer(UUID playerId) {
        pendingPreLogins.remove(playerId);
        visibleTransmitters.forEach((receiverId, transmitters) -> {
            if (!receiverId.equals(playerId) && transmitters.remove(playerId) && transmitters.isEmpty()) {
                visibleTransmitters.remove(receiverId, transmitters);
            }
        });
        visibleTransmitters.remove(playerId);
        if (persistenceEnabled) {
            submitPersistence(() -> preferences.saveAndUnload(playerId),
                    "Could not save and release preferences for " + playerId + ".");
        } else {
            preferences.unloadWithoutSave(playerId);
        }
    }

    void discardUnjoinedPreLogin(UUID playerId) {
        Long attempt = pendingPreLogins.remove(playerId);
        if (attempt != null) {
            preferences.unloadWithoutSave(playerId);
        }
    }

    private void discardUnjoinedPreLogin(UUID playerId, long attempt) {
        if (pendingPreLogins.remove(playerId, attempt)) {
            preferences.unloadWithoutSave(playerId);
        }
    }

    public void shutdownAndSave() {
        persistenceExecutor.shutdown();
        try {
            if (!persistenceExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Persistence tasks did not finish before shutdown.");
                persistenceExecutor.shutdownNow();
                persistenceExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            persistenceExecutor.shutdownNow();
        }
        if (persistenceEnabled) {
            preferences.saveAll();
        }
    }

    public boolean canSee(Player transmitter, Player receiver) {
        if (!Bukkit.isPrimaryThread()) {
            return false;
        }

        VisibilityMode transmission = getTransmissionMode(transmitter.getUniqueId());
        VisibilityMode reception = getReceptionMode(receiver.getUniqueId());
        boolean transmitterWorldAllowed = worldRestriction.isAllowed(transmitter.getWorld().getName());
        boolean receiverWorldAllowed = worldRestriction.isAllowed(receiver.getWorld().getName());
        boolean sameTeam = false;

        if (transmitterWorldAllowed && receiverWorldAllowed
                && transmission != VisibilityMode.OFF
                && !transmitter.getUniqueId().equals(receiver.getUniqueId())
                && !(transmission == VisibilityMode.PUBLIC && reception == VisibilityMode.PUBLIC)) {
            sameTeam = teamResolver.areTeammates(transmitter, receiver);
        }

        return VisibilityPolicy.canSee(
                transmitter.getUniqueId(),
                receiver.getUniqueId(),
                transmission,
                reception,
                sameTeam,
                transmitterWorldAllowed,
                receiverWorldAllowed
        );
    }

    public void recordVisible(UUID receiverId, UUID transmitterId) {
        visibleTransmitters.computeIfAbsent(receiverId, ignored -> ConcurrentHashMap.newKeySet()).add(transmitterId);
    }

    public void forgetVisible(UUID receiverId, UUID transmitterId) {
        Set<UUID> transmitters = visibleTransmitters.get(receiverId);
        if (transmitters != null) {
            transmitters.remove(transmitterId);
            if (transmitters.isEmpty()) {
                visibleTransmitters.remove(receiverId, transmitters);
            }
        }
    }

    public void revoke(UUID receiverId, UUID transmitterId) {
        Runnable removal = () -> {
            Player receiver = Bukkit.getPlayer(receiverId);
            if (receiver != null && receiver.isOnline()) {
                sendRemoval(receiver, transmitterId);
            }
            forgetVisible(receiverId, transmitterId);
        };
        scheduleRevocation(removal);
    }

    public void reconcileVisibleMarkers() {
        for (var entry : visibleTransmitters.entrySet()) {
            UUID receiverId = entry.getKey();
            for (UUID transmitterId : Set.copyOf(entry.getValue())) {
                revokeIfUnauthorized(receiverId, transmitterId);
            }
        }
    }

    private void revokeUnauthorizedTransmitters(UUID transmitterId) {
        for (var entry : visibleTransmitters.entrySet()) {
            if (entry.getValue().contains(transmitterId)) {
                revokeIfUnauthorized(entry.getKey(), transmitterId);
            }
        }
    }

    private void revokeUnauthorizedForReceiver(UUID receiverId) {
        Set<UUID> transmitters = visibleTransmitters.get(receiverId);
        if (transmitters == null) {
            return;
        }

        for (UUID transmitterId : Set.copyOf(transmitters)) {
            revokeIfUnauthorized(receiverId, transmitterId);
        }
    }

    private void revokeIfUnauthorized(UUID receiverId, UUID transmitterId) {
        Player receiver = Bukkit.getPlayer(receiverId);
        Player transmitter = Bukkit.getPlayer(transmitterId);
        if (receiver == null || !receiver.isOnline()
                || transmitter == null || !transmitter.isOnline()
                || !canSee(transmitter, receiver)) {
            revoke(receiverId, transmitterId);
        }
    }

    private void sendRemoval(Player receiver, UUID transmitterId) {
        PacketContainer packet = new PacketContainer(
                PacketType.Play.Server.TRACKED_WAYPOINT,
                ClientboundTrackedWaypointPacket.removeWaypoint(transmitterId)
        );
        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(receiver, packet, false);
            plugin.logDebugDecision(VisibilityDebugLogger.Decision.MARKER_REMOVAL_SENT);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not remove a revoked Locator Bar marker for " + receiver.getUniqueId(), exception);
        }
    }

    private void scheduleRevocation(Runnable revocation) {
        if (visibleTransmitters.isEmpty()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            revocation.run();
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, revocation);
        }
    }

    private void saveAsync(UUID playerId) {
        if (persistenceEnabled) {
            submitPersistence(() -> preferences.save(playerId), "Could not save preferences for " + playerId + ".");
        }
    }

    private void submitPersistence(Runnable task, String failureMessage) {
        try {
            persistenceExecutor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, failureMessage, exception);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            plugin.getLogger().log(Level.WARNING, failureMessage, exception);
        }
    }

    private void reevaluatePlayerAfterWorldChange(Player player) {
        player.getScheduler().run(plugin, task -> {
            UUID playerId = player.getUniqueId();
            for (var entry : visibleTransmitters.entrySet()) {
                if (entry.getKey().equals(playerId)) {
                    for (UUID transmitterId : Set.copyOf(entry.getValue())) {
                        revokeIfUnauthorized(playerId, transmitterId);
                    }
                } else if (entry.getValue().contains(playerId)) {
                    revokeIfUnauthorized(entry.getKey(), playerId);
                }
            }
        }, null);
    }
}
