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

public class LocationBarListener implements Listener {

    private final TeamLocatorBar instance;

    public LocationBarListener(TeamLocatorBar instance) {
        this.instance = instance;

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                instance,
                PacketType.Play.Server.TRACKED_WAYPOINT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (!instance.getConfig().getBoolean("enabled")) return;

                PacketContainer packetContainer = event.getPacket().deepClone();
                ClientboundTrackedWaypointPacket packet = (ClientboundTrackedWaypointPacket) packetContainer.getHandle();
                TrackedWaypoint tracked = packet.waypoint();

                tracked.id().ifLeft(uuid -> {
                    Player viewer = event.getPlayer();
                    if (uuid.equals(viewer.getUniqueId())) return;

                    Player target = Bukkit.getPlayer(uuid);
                    if (target == null || !target.isOnline()) return;

                    Team viewerTeam = viewer.getScoreboard().getEntryTeam(viewer.getName());
                    Team targetTeam = target.getScoreboard().getEntryTeam(target.getName());

                    String viewerTeamName = (viewerTeam != null) ? viewerTeam.getName() : null;
                    String targetTeamName = (targetTeam != null) ? targetTeam.getName() : null;

                    int color;

                    if (targetTeamName == null) {
                        if (!instance.getConfig().getBoolean("colors.neutral.enabled")) {
                            event.setCancelled(true);
                            return;
                        }
                        color = Color.decode(instance.getConfig().getString("colors.neutral.color")).getRGB() & 0xFFFFFF;

                    }
                    else if (viewerTeamName != null && viewerTeamName.equals(targetTeamName)) {
                        if (!instance.getConfig().getBoolean("colors.teammate.enabled")) {
                            event.setCancelled(true);
                            return;
                        }
                        color = Color.decode(instance.getConfig().getString("colors.teammate.color")).getRGB() & 0xFFFFFF;

                    }
                    else {
                        if (!instance.getConfig().getBoolean("colors.enemy.enabled")) {
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
                            net.minecraft.core.Vec3i vector = (net.minecraft.core.Vec3i) vectorField.get(tracked);
                            newTrackedWaypoint = TrackedWaypoint.setPosition(uuid, icon, vector);
                        } catch (Exception e) {
                            newTrackedWaypoint = TrackedWaypoint.empty(uuid);
                        }
                    } else if (className.equals("ChunkWaypoint")) {
                        try {
                            java.lang.reflect.Field chunkPosField = tracked.getClass().getDeclaredField("chunkPos");
                            chunkPosField.setAccessible(true);
                            net.minecraft.world.level.ChunkPos chunkPos = (net.minecraft.world.level.ChunkPos) chunkPosField.get(tracked);
                            newTrackedWaypoint = TrackedWaypoint.setChunk(uuid, icon, chunkPos);
                        } catch (Exception e) {
                            newTrackedWaypoint = TrackedWaypoint.empty(uuid);
                        }
                    } else if (className.equals("AzimuthWaypoint")) {
                        try {
                            java.lang.reflect.Field angleField = tracked.getClass().getDeclaredField("angle");
                            angleField.setAccessible(true);
                            float angle = angleField.getFloat(tracked);
                            newTrackedWaypoint = TrackedWaypoint.setAzimuth(uuid, icon, angle);
                        } catch (Exception e) {
                            newTrackedWaypoint = TrackedWaypoint.empty(uuid);
                        }
                    } else {
                        newTrackedWaypoint = TrackedWaypoint.empty(uuid);
                    }

                    if (newTrackedWaypoint != null) {
                        packetContainer.getSpecificModifier(TrackedWaypoint.class).write(0, newTrackedWaypoint);
                        event.setPacket(packetContainer);
                    }
                });
            }
        });
    }
}