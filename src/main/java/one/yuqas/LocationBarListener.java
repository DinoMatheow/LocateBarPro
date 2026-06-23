package one.yuqas;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.minecraft.world.waypoints.Waypoint;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.awt.*;
import java.util.Optional;

public class LocationBarListener implements Listener {

    private final TeamLocatorBar instance;
    private final boolean isFolia;
    private Object currentTask = null;

    public LocationBarListener(TeamLocatorBar instance) {
        this.instance = instance;

        boolean foliaCheck = false;
        try {
            Class.forName("io.papermc.paper.threadedregionscheduling.RegionScheduler");
            foliaCheck = true;
        } catch (ClassNotFoundException e) {
            foliaCheck = false;
        }
        this.isFolia = foliaCheck;

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                instance,
                PacketType.Play.Server.TRACKED_WAYPOINT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (!instance.getConfig().getBoolean("enabled"))
                    return;

                ClientboundTrackedWaypointPacket packet =
                        (ClientboundTrackedWaypointPacket) event.getPacket().getHandle();

                TrackedWaypoint tracked = packet.waypoint();

                tracked.id().ifLeft(uuid -> {
                    Player target = Bukkit.getPlayer(uuid);
                    if (target == null) return;

                    Player viewer = event.getPlayer();
                    int color;

                    String viewerTeamName = instance.getTeam(viewer);
                    String targetTeamName = instance.getTeam(target);

                    if (viewerTeamName != null && viewerTeamName.equals(targetTeamName)) {
                        if (!instance.getConfig().getBoolean("colors.teammate.enabled")) {
                            event.setCancelled(true);
                            return;
                        }
                        color = Color.decode(instance.getConfig().getString("colors.teammate.color")).getRGB() & 0xFFFFFF;
                    } else if (viewerTeamName != null && targetTeamName != null) {
                        if (!instance.getConfig().getBoolean("colors.enemy.enabled")) {
                            event.setCancelled(true);
                            return;
                        }
                        color = Color.decode(instance.getConfig().getString("colors.enemy.color")).getRGB() & 0xFFFFFF;
                    } else {
                        if (!instance.getConfig().getBoolean("colors.neutral.enabled")) {
                            event.setCancelled(true);
                            return;
                        }
                        color = Color.decode(instance.getConfig().getString("colors.neutral.color")).getRGB() & 0xFFFFFF;
                    }

                    Waypoint.Icon icon = tracked.icon();
                    icon.color = Optional.of(color);
                });
            }
        });

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                instance,
                PacketType.Play.Server.SCOREBOARD_TEAM
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (currentTask != null) {
                    if (isFolia) {
                        ((io.papermc.paper.threadedregions.scheduler.ScheduledTask) currentTask).cancel();
                    } else {
                        ((org.bukkit.scheduler.BukkitTask) currentTask).cancel();
                    }
                }

                if (isFolia) {
                    currentTask = Bukkit.getGlobalRegionScheduler().runDelayed(instance, (task) -> {
                        Bukkit.getOnlinePlayers().forEach(instance::cachePlayer);
                        currentTask = null;
                    }, 10L);
                } else {
                    currentTask = Bukkit.getScheduler().runTaskLater(instance, () -> {
                        Bukkit.getOnlinePlayers().forEach(instance::cachePlayer);
                        currentTask = null;
                    }, 10L);
                }
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        instance.cachePlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        instance.removePlayer(event.getPlayer());
    }
}