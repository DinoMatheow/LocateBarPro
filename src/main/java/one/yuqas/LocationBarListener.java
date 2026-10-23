package one.yuqas;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.minecraft.world.waypoints.Waypoint;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scoreboard.Team;

import java.awt.*;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public class LocationBarListener implements Listener {

    private final TeamLocatorBar instance;
    private final PlayerPrivacyService privacyService;

    public LocationBarListener(TeamLocatorBar instance, PlayerPrivacyService privacyService) {
        this.instance = instance;
        this.privacyService = privacyService;

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                instance,
                PacketType.Play.Server.TRACKED_WAYPOINT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    LocationBarListener.this.handlePacketSending(event);
                } catch (RuntimeException exception) {
                    event.setCancelled(true);
                    instance.getLogger().log(Level.WARNING,
                            "Could not safely evaluate a Locator Bar packet; denying it.", exception);
                }
            }
        });
    }

    private void handlePacketSending(PacketEvent event) {
        PacketContainer packetContainer = event.getPacket().deepClone();
        ClientboundTrackedWaypointPacket packet = (ClientboundTrackedWaypointPacket) packetContainer.getHandle();
        TrackedWaypoint tracked = packet.waypoint();

        tracked.id().ifLeft(uuid -> {
            // Paper 26.3's operation enum is package-private, so read its enum field through ProtocolLib.
            Object operationValue = packetContainer.getModifier().withType(Enum.class).read(0);
            Player viewer = event.getPlayer();
            UUID viewerId = viewer.getUniqueId();

            if (!(operationValue instanceof Enum<?> operation)) {
                instance.logDebugDecision(VisibilityDebugLogger.Decision.DENIED_INVALID_OPERATION);
                deny(event, viewerId, uuid);
                return;
            }

            if (operation.name().equals("UNTRACK")) {
                privacyService.forgetVisible(viewerId, uuid);
                return;
            }

            if (!operation.name().equals("TRACK") && !operation.name().equals("UPDATE")) {
                instance.logDebugDecision(VisibilityDebugLogger.Decision.DENIED_UNSUPPORTED_OPERATION);
                deny(event, viewerId, uuid);
                return;
            }

            if (!Bukkit.isPrimaryThread()) {
                instance.logDebugDecision(VisibilityDebugLogger.Decision.DENIED_NON_PRIMARY_THREAD);
                deny(event, viewerId, uuid);
                return;
            }

            Player target = Bukkit.getPlayer(uuid);
            if (target == null || !target.isOnline()) {
                instance.logDebugDecision(VisibilityDebugLogger.Decision.DENIED_TRANSMITTER_UNAVAILABLE);
                deny(event, viewerId, uuid);
                return;
            }
            if (!privacyService.canSee(target, viewer)) {
                instance.logDebugDecision(VisibilityDebugLogger.Decision.DENIED_PRIVACY_POLICY);
                deny(event, viewerId, uuid);
                return;
            }
            instance.logDebugDecision(VisibilityDebugLogger.Decision.ALLOWED);

            if (!instance.getConfig().getBoolean("enabled")) {
                privacyService.recordVisible(viewerId, uuid);
                return;
            }

            Team viewerTeam = viewer.getScoreboard().getEntryTeam(viewer.getName());
            Team targetTeam = target.getScoreboard().getEntryTeam(target.getName());

            String viewerTeamName = viewerTeam != null ? viewerTeam.getName() : null;
            String targetTeamName = targetTeam != null ? targetTeam.getName() : null;

            int color;

            if (targetTeamName == null) {
                if (!instance.getConfig().getBoolean("colors.neutral.enabled")) {
                    instance.logDebugDecision(VisibilityDebugLogger.Decision.DENIED_COLOR_DISABLED);
                    event.setCancelled(true);
                    return;
                }
                color = Color.decode(instance.getConfig().getString("colors.neutral.color")).getRGB() & 0xFFFFFF;

            } else if (viewerTeamName != null && viewerTeamName.equals(targetTeamName)) {
                if (!instance.getConfig().getBoolean("colors.teammate.enabled")) {
                    instance.logDebugDecision(VisibilityDebugLogger.Decision.DENIED_COLOR_DISABLED);
                    event.setCancelled(true);
                    return;
                }
                color = Color.decode(instance.getConfig().getString("colors.teammate.color")).getRGB() & 0xFFFFFF;

            } else {
                if (!instance.getConfig().getBoolean("colors.enemy.enabled")) {
                    instance.logDebugDecision(VisibilityDebugLogger.Decision.DENIED_COLOR_DISABLED);
                    event.setCancelled(true);
                    return;
                }
                color = Color.decode(instance.getConfig().getString("colors.enemy.color")).getRGB() & 0xFFFFFF;
            }

            Waypoint.Icon icon = tracked.icon();
            icon.color = Optional.of(color);

            TrackedWaypoint newTrackedWaypoint = null;
            String className = tracked.getClass().getSimpleName();

            if (className.equals("Vec3iWaypoint")) {
                try {
                    java.lang.reflect.Field vectorField = tracked.getClass().getDeclaredField("vector");
                    vectorField.setAccessible(true);
                    net.minecraft.core.Vec3i vector =
                            (net.minecraft.core.Vec3i) vectorField.get(tracked);
                    newTrackedWaypoint = TrackedWaypoint.setPosition(uuid, icon, vector);
                } catch (Exception exception) {
                    newTrackedWaypoint = TrackedWaypoint.empty(uuid);
                }
            } else if (className.equals("ChunkWaypoint")) {
                try {
                    java.lang.reflect.Field chunkPosField = tracked.getClass().getDeclaredField("chunkPos");
                    chunkPosField.setAccessible(true);
                    net.minecraft.world.level.ChunkPos chunkPos =
                            (net.minecraft.world.level.ChunkPos) chunkPosField.get(tracked);
                    newTrackedWaypoint = TrackedWaypoint.setChunk(uuid, icon, chunkPos);
                } catch (Exception exception) {
                    newTrackedWaypoint = TrackedWaypoint.empty(uuid);
                }
            } else if (className.equals("AzimuthWaypoint")) {
                try {
                    java.lang.reflect.Field angleField = tracked.getClass().getDeclaredField("angle");
                    angleField.setAccessible(true);
                    float angle = angleField.getFloat(tracked);
                    newTrackedWaypoint = TrackedWaypoint.setAzimuth(uuid, icon, angle);
                } catch (Exception exception) {
                    newTrackedWaypoint = TrackedWaypoint.empty(uuid);
                }
            } else {
                newTrackedWaypoint = TrackedWaypoint.empty(uuid);
            }

            if (newTrackedWaypoint != null) {
                packetContainer.getSpecificModifier(TrackedWaypoint.class).write(0, newTrackedWaypoint);
                event.setPacket(packetContainer);
                privacyService.recordVisible(viewerId, uuid);
            }
        });
    }

    private void deny(PacketEvent event, UUID receiverId, UUID transmitterId) {
        event.setCancelled(true);
        privacyService.revoke(receiverId, transmitterId);
    }
}
