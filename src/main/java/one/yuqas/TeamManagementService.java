package one.yuqas;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

public final class TeamManagementService implements Listener {

    private static final String TEAM_PREFIX = "tlb_";
    private static final Pattern VALID_VISIBLE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final String PERMISSION = "teamlocatorbar.team";
    private static final String PREFIX = "§a[Teams] §f";

    private final TeamLocatorBar plugin;
    private final PlayerPrivacyService privacyService;
    private final TeamStatePersistence persistence;
    private final LongSupplier nanoTime;
    private final BooleanSupplier primaryThread;
    private final Map<String, TeamState> teamStates = new HashMap<>();
    private final Map<UUID, Map<String, Invitation>> invitations = new HashMap<>();
    private int maxMembers = 8;
    private int inviteTimeoutSeconds = 60;
    private boolean persistenceWritable = true;

    public TeamManagementService(TeamLocatorBar plugin, PlayerPrivacyService privacyService) {
        this(plugin, privacyService, null, System::nanoTime, Bukkit::isPrimaryThread);
    }

    public TeamManagementService(
            TeamLocatorBar plugin,
            PlayerPrivacyService privacyService,
            java.nio.file.Path dataDirectory
    ) {
        this(plugin, privacyService, new TeamStatePersistence(dataDirectory, plugin.getLogger()),
                System::nanoTime, Bukkit::isPrimaryThread);
    }

    TeamManagementService(
            TeamLocatorBar plugin,
            PlayerPrivacyService privacyService,
            LongSupplier nanoTime,
            BooleanSupplier primaryThread
    ) {
        this(plugin, privacyService, null, nanoTime, primaryThread);
    }

    TeamManagementService(
            TeamLocatorBar plugin,
            PlayerPrivacyService privacyService,
            TeamStatePersistence persistence,
            LongSupplier nanoTime,
            BooleanSupplier primaryThread
    ) {
        this.plugin = plugin;
        this.privacyService = privacyService;
        this.persistence = persistence;
        this.nanoTime = nanoTime;
        this.primaryThread = primaryThread;
        reloadSettings();
    }

    public void reloadSettings() {
        Object configuredMaxMembers = plugin.getConfig().get("teams.max-members");
        if (configuredMaxMembers instanceof Number value && value.intValue() > 0
                && value.doubleValue() == value.intValue()) {
            maxMembers = value.intValue();
        } else {
            maxMembers = 8;
            if (configuredMaxMembers != null) {
                plugin.getLogger().warning("Invalid teams.max-members; using 8.");
            }
        }

        Object configuredTimeout = plugin.getConfig().get("teams.invite-timeout-seconds");
        if (configuredTimeout instanceof Number value && value.intValue() > 0
                && value.doubleValue() == value.intValue()) {
            inviteTimeoutSeconds = value.intValue();
        } else {
            inviteTimeoutSeconds = 60;
            if (configuredTimeout != null) {
                plugin.getLogger().warning("Invalid teams.invite-timeout-seconds; using 60.");
            }
        }
    }

    public void loadAndReconcile() {
        requirePrimaryThread();
        TeamStatePersistence.LoadResult loaded = persistence == null
                ? new TeamStatePersistence.LoadResult(Map.of(), true)
                : privacyService.runPersistenceTask(persistence::load, "Could not load team leadership data.");
        reconcileLoadedTeams(loaded);
    }

    void reconcileLoadedTeams(TeamStatePersistence.LoadResult loaded) {
        requirePrimaryThread();
        persistenceWritable = loaded.writable();
        teamStates.clear();
        Scoreboard scoreboard = mainScoreboard();
        for (Team team : scoreboard.getTeams()) {
            if (!isPluginTeam(team)) {
                continue;
            }
            TeamStatePersistence.SavedTeam saved = loaded.teams().get(team.getName());
            UUID leaderId = null;
            String leaderName = null;
            if (saved != null && saved.leaderName() != null && team.getEntries().contains(saved.leaderName())) {
                Player onlineLeader = plugin.getServer().getPlayerExact(saved.leaderName());
                if (saved.leaderId() == null) {
                    leaderId = onlineLeader == null ? null : onlineLeader.getUniqueId();
                    leaderName = saved.leaderName();
                } else if (onlineLeader == null || onlineLeader.getUniqueId().equals(saved.leaderId())) {
                    leaderId = saved.leaderId();
                    leaderName = saved.leaderName();
                }
            }
            teamStates.put(team.getName(), new TeamState(leaderId, leaderName, team.getDisplayName()));
        }
        saveStateAsync();
    }

    public void execute(Player player, String[] args) {
        requirePrimaryThread();
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage("§cYou do not have permission to manage teams.");
            return;
        }

        if (args.length == 0) {
            sendHelp(player);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(player, args);
            case "invite" -> invite(player, args);
            case "accept" -> accept(player, args);
            case "deny" -> deny(player, args);
            case "leave" -> leave(player, args);
            case "disband" -> disband(player, args);
            case "kick" -> kick(player, args);
            case "transfer" -> transfer(player, args);
            case "info" -> info(player, args);
            case "help" -> {
                if (args.length != 1) {
                    usage(player);
                } else {
                    sendHelp(player);
                }
            }
            default -> usage(player);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        requirePrimaryThread();
        Player player = event.getPlayer();
        Team team = mainScoreboard().getEntryTeam(player.getName());
        TeamState state = team == null ? null : teamStates.get(team.getName());
        if (state != null && state.leaderId() == null
                && player.getName().equalsIgnoreCase(state.leaderName())) {
            teamStates.put(team.getName(), new TeamState(player.getUniqueId(), player.getName(), state.visibleName()));
            saveStateAsync();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        requirePrimaryThread();
        UUID playerId = event.getPlayer().getUniqueId();
        invitations.remove(playerId);
        invitations.values().forEach(pending ->
                pending.values().removeIf(invitation -> invitation.leaderId().equals(playerId)));
        invitations.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private void create(Player player, String[] args) {
        if (args.length != 2 || !VALID_VISIBLE_NAME.matcher(args[1]).matches()) {
            player.sendMessage(PREFIX + "Team names must be 1-16 characters: letters, numbers, _ or -.");
            return;
        }

        Scoreboard scoreboard = mainScoreboard();
        Team existing = scoreboard.getEntryTeam(player.getName());
        if (existing != null) {
            tellAlreadyOnTeam(player, existing);
            return;
        }

        String visibleName = args[1];
        if (findPluginTeam(scoreboard, visibleName) != null) {
            player.sendMessage(PREFIX + "A team with that name already exists.");
            return;
        }

        String internalName = nextInternalName(scoreboard);
        Team team = scoreboard.registerNewTeam(internalName);
        team.setDisplayName(visibleName);
        team.addEntry(player.getName());
        teamStates.put(internalName, new TeamState(player.getUniqueId(), player.getName(), visibleName));
        saveStateAsync();
        privacyService.reevaluateAllVisibleMarkers();
        player.sendMessage(PREFIX + "Created team " + visibleName + ". You are its leader.");
    }

    private void invite(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(PREFIX + "Usage: /locator team invite <player>");
            return;
        }

        Scoreboard scoreboard = mainScoreboard();
        Team team = scoreboard.getEntryTeam(player.getName());
        TeamState state = team == null ? null : teamStates.get(team.getName());
        if (!isLeader(state, player)) {
            player.sendMessage(PREFIX + "Only your team's leader can invite players.");
            return;
        }

        Player invitee = plugin.getServer().getPlayerExact(args[1]);
        if (invitee == null || !invitee.isOnline()) {
            player.sendMessage(PREFIX + "That player must be online to receive an invitation.");
            return;
        }
        if (invitee.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(PREFIX + "You cannot invite yourself.");
            return;
        }
        if (scoreboard.getEntryTeam(invitee.getName()) != null) {
            player.sendMessage(PREFIX + invitee.getName() + " is already on a scoreboard team.");
            return;
        }
        if (team.getEntries().size() >= maxMembers) {
            player.sendMessage(PREFIX + "Your team has reached its member limit (" + maxMembers + ").");
            return;
        }

        String teamKey = normalized(state.visibleName());
        Map<String, Invitation> pending = invitations.computeIfAbsent(
                invitee.getUniqueId(), ignored -> new LinkedHashMap<>());
        if (pending.containsKey(teamKey)) {
            player.sendMessage(PREFIX + "That player already has a pending invitation to your team.");
            return;
        }

        long expiresAt = nanoTime.getAsLong() + inviteTimeoutSeconds * 1_000_000_000L;
        Invitation invitation = new Invitation(
                invitee.getUniqueId(), player.getUniqueId(), team.getName(), state.visibleName(), expiresAt);
        pending.put(teamKey, invitation);
        long delayTicks = inviteTimeoutSeconds * 20L;
        plugin.getServer().getGlobalRegionScheduler().runDelayed(
                plugin, task -> expireInvitation(invitation), delayTicks);

        player.sendMessage(PREFIX + "Invited " + invitee.getName() + " to " + state.visibleName() + ".");
        invitee.sendMessage(Component.text("You were invited to team " + state.visibleName() + ". "));
        invitee.sendMessage(Component.text("[Aceptar]")
                .clickEvent(ClickEvent.runCommand("/locator team accept " + state.visibleName()))
                .append(Component.text(" "))
                .append(Component.text("[Rechazar]")
                        .clickEvent(ClickEvent.runCommand("/locator team deny " + state.visibleName())))
                .append(Component.text(" Manual commands: /locator team accept "
                        + state.visibleName() + " or /locator team deny " + state.visibleName() + ".")));
    }

    private void accept(Player player, String[] args) {
        if (args.length > 2) {
            player.sendMessage(PREFIX + "Usage: /locator team accept [team-name]");
            return;
        }

        Map<String, Invitation> pending = activeInvitations(player.getUniqueId());
        if (pending.isEmpty()) {
            player.sendMessage(PREFIX + "You have no pending team invitations.");
            return;
        }

        Invitation invitation;
        if (args.length == 2) {
            invitation = pending.get(normalized(args[1]));
            if (invitation == null) {
                player.sendMessage(PREFIX + "You have no pending invitation to that team.");
                return;
            }
        } else if (pending.size() == 1) {
            invitation = pending.values().iterator().next();
        } else {
            player.sendMessage(PREFIX + "You have multiple invitations. Specify a team name:");
            pending.values().forEach(value -> player.sendMessage(PREFIX + value.visibleName()));
            return;
        }

        Scoreboard scoreboard = mainScoreboard();
        Team team = scoreboard.getTeam(invitation.internalName());
        if (team == null || !isPluginTeam(team) || !teamStates.containsKey(team.getName())) {
            removeInvitation(invitation);
            player.sendMessage(PREFIX + "That team no longer exists.");
            return;
        }
        TeamState state = teamStates.get(team.getName());
        if (state == null || state.leaderId() == null || state.leaderName() == null
                || !team.getEntries().contains(state.leaderName())) {
            removeInvitation(invitation);
            player.sendMessage(PREFIX + "That team's leadership is no longer available.");
            return;
        }
        if (scoreboard.getEntryTeam(player.getName()) != null) {
            removeInvitation(invitation);
            tellAlreadyOnTeam(player, scoreboard.getEntryTeam(player.getName()));
            return;
        }
        if (team.getEntries().size() >= maxMembers) {
            removeInvitation(invitation);
            player.sendMessage(PREFIX + "That team has reached its member limit (" + maxMembers + ").");
            return;
        }

        team.addEntry(player.getName());
        removeInvitation(invitation);
        privacyService.reevaluateAllVisibleMarkers();
        player.sendMessage(PREFIX + "You joined team " + state.visibleName() + ".");
        Player leader = state.leaderId() == null ? null : plugin.getServer().getPlayer(state.leaderId());
        if (leader != null && leader.isOnline()) {
            leader.sendMessage(PREFIX + player.getName() + " joined your team.");
        }
    }

    private void deny(Player player, String[] args) {
        if (args.length > 2) {
            player.sendMessage(PREFIX + "Usage: /locator team deny [team-name]");
            return;
        }

        Map<String, Invitation> pending = activeInvitations(player.getUniqueId());
        Invitation invitation = selectInvitation(player, args, pending, "reject");
        if (invitation == null) {
            return;
        }
        removeInvitation(invitation);
        player.sendMessage(PREFIX + "Invitation to " + invitation.visibleName() + " rejected.");
        Player leader = plugin.getServer().getPlayer(invitation.leaderId());
        if (leader != null && leader.isOnline()) {
            leader.sendMessage(PREFIX + player.getName() + " rejected your invitation to "
                    + invitation.visibleName() + ".");
        }
    }

    private void leave(Player player, String[] args) {
        if (args.length != 1) {
            player.sendMessage(PREFIX + "Usage: /locator team leave");
            return;
        }
        Scoreboard scoreboard = mainScoreboard();
        Team team = scoreboard.getEntryTeam(player.getName());
        TeamState state = team == null ? null : teamStates.get(team.getName());
        if (team == null || state == null) {
            player.sendMessage(PREFIX + "You are not a member of a managed team.");
            return;
        }
        if (isLeader(state, player) && team.getEntries().size() == 1) {
            closeTeam(team);
            player.sendMessage(PREFIX + "Your team " + state.visibleName() + " has been closed.");
            return;
        }
        if (isLeader(state, player)) {
            player.sendMessage(PREFIX + "Transfer leadership before leaving your team.");
            return;
        }

        team.removeEntry(player.getName());
        privacyService.reevaluateAllVisibleMarkers();
        player.sendMessage(PREFIX + "You left team " + state.visibleName() + ".");
    }

    private void disband(Player player, String[] args) {
        if (args.length == 2) {
            disbandAsAdministrator(player, args[1]);
            return;
        }
        if (args.length != 1) {
            player.sendMessage(PREFIX + "Usage: /locator team disband");
            return;
        }
        Scoreboard scoreboard = mainScoreboard();
        Team team = scoreboard.getEntryTeam(player.getName());
        TeamState state = team == null ? null : teamStates.get(team.getName());
        if (!isLeader(state, player)) {
            player.sendMessage(PREFIX + "Only your team's leader can disband it.");
            return;
        }
        String visibleName = state.visibleName();
        closeTeam(team);
        player.sendMessage(PREFIX + "Team " + visibleName + " was disbanded.");
    }

    private void disbandAsAdministrator(Player player, String teamName) {
        if (!player.hasPermission("teamlocatorbar.team.admin")) {
            player.sendMessage("§cYou do not have permission to administratively disband teams.");
            return;
        }
        Team team = findPluginTeam(mainScoreboard(), teamName);
        if (team == null || !teamStates.containsKey(team.getName())) {
            player.sendMessage(PREFIX + "No managed team with that name exists.");
            return;
        }
        String visibleName = team.getDisplayName();
        closeTeam(team);
        player.sendMessage(PREFIX + "Managed team " + visibleName + " was administratively disbanded.");
    }

    private void kick(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(PREFIX + "Usage: /locator team kick <player>");
            return;
        }
        Scoreboard scoreboard = mainScoreboard();
        Team team = scoreboard.getEntryTeam(player.getName());
        TeamState state = team == null ? null : teamStates.get(team.getName());
        if (!isLeader(state, player)) {
            player.sendMessage(PREFIX + "Only your team's leader can remove members.");
            return;
        }
        String memberName = team.getEntries().stream()
                .filter(entry -> entry.equalsIgnoreCase(args[1]))
                .findFirst()
                .orElse(null);
        if (memberName == null) {
            player.sendMessage(PREFIX + "That player is not a member of your team.");
            return;
        }
        if (memberName.equalsIgnoreCase(player.getName())) {
            player.sendMessage(PREFIX + "You cannot remove yourself. Transfer leadership or disband the team.");
            return;
        }

        team.removeEntry(memberName);
        Player removed = plugin.getServer().getPlayerExact(memberName);
        if (removed != null) {
            removed.sendMessage(PREFIX + "You were removed from team " + state.visibleName() + ".");
        }
        player.sendMessage(PREFIX + "Removed " + memberName + " from team " + state.visibleName() + ".");
        privacyService.reevaluateAllVisibleMarkers();
    }

    private void transfer(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(PREFIX + "Usage: /locator team transfer <player>");
            return;
        }
        Scoreboard scoreboard = mainScoreboard();
        Team team = scoreboard.getEntryTeam(player.getName());
        TeamState state = team == null ? null : teamStates.get(team.getName());
        if (!isLeader(state, player)) {
            player.sendMessage(PREFIX + "Only your team's leader can transfer leadership.");
            return;
        }
        String memberName = team.getEntries().stream()
                .filter(entry -> entry.equalsIgnoreCase(args[1]))
                .findFirst()
                .orElse(null);
        if (memberName == null) {
            player.sendMessage(PREFIX + "Leadership can only be transferred to a member of your team.");
            return;
        }
        if (memberName.equalsIgnoreCase(player.getName())) {
            player.sendMessage(PREFIX + "You are already the leader.");
            return;
        }

        Player newLeader = plugin.getServer().getPlayerExact(memberName);
        teamStates.put(team.getName(), new TeamState(
                newLeader == null ? null : newLeader.getUniqueId(), memberName, state.visibleName()));
        saveStateAsync();
        privacyService.reevaluateAllVisibleMarkers();
        player.sendMessage(PREFIX + "Leadership of " + state.visibleName() + " was transferred to "
                + memberName + ".");
        if (newLeader != null) {
            newLeader.sendMessage(PREFIX + "You are now the leader of " + state.visibleName() + ".");
        }
    }

    private void info(Player player, String[] args) {
        if (args.length != 1) {
            player.sendMessage(PREFIX + "Usage: /locator team info");
            return;
        }
        Team team = mainScoreboard().getEntryTeam(player.getName());
        TeamState state = team == null ? null : teamStates.get(team.getName());
        if (state == null) {
            player.sendMessage(PREFIX + "You are not a member of a managed team.");
            return;
        }
        player.sendMessage("§6Team: §f" + state.visibleName());
        player.sendMessage("§6Leader: §f"
                + (state.leaderName() == null ? "None (administrator closure required)" : state.leaderName()));
        player.sendMessage("§6Members (" + team.getEntries().size() + "/" + maxMembers + "): §f"
                + String.join(", ", team.getEntries().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()));
    }

    private Invitation selectInvitation(
            Player player,
            String[] args,
            Map<String, Invitation> pending,
            String action
    ) {
        if (pending.isEmpty()) {
            player.sendMessage(PREFIX + "You have no pending team invitations to " + action + ".");
            return null;
        }
        if (args.length == 2) {
            Invitation invitation = pending.get(normalized(args[1]));
            if (invitation == null) {
                player.sendMessage(PREFIX + "You have no pending invitation to that team.");
            }
            return invitation;
        }
        if (pending.size() == 1) {
            return pending.values().iterator().next();
        }
        player.sendMessage(PREFIX + "You have multiple invitations. Specify a team name:");
        pending.values().forEach(value -> player.sendMessage(PREFIX + value.visibleName()));
        return null;
    }

    private Map<String, Invitation> activeInvitations(UUID inviteeId) {
        Map<String, Invitation> pending = invitations.get(inviteeId);
        if (pending == null) {
            return Map.of();
        }
        long now = nanoTime.getAsLong();
        pending.values().removeIf(invitation -> invitation.expiresAtNanos() <= now);
        if (pending.isEmpty()) {
            invitations.remove(inviteeId);
            return Map.of();
        }
        return pending;
    }

    private void expireInvitation(Invitation invitation) {
        if (invitation.expiresAtNanos() > nanoTime.getAsLong()) {
            long remaining = invitation.expiresAtNanos() - nanoTime.getAsLong();
            plugin.getServer().getGlobalRegionScheduler().runDelayed(
                    plugin, task -> expireInvitation(invitation), Math.max(1, remaining / 50_000_000L));
            return;
        }
        removeInvitation(invitation);
    }

    private void removeInvitation(Invitation invitation) {
        Map<String, Invitation> pending = invitations.get(invitation.inviteeId());
        if (pending == null) {
            return;
        }
        pending.remove(normalized(invitation.visibleName()), invitation);
        if (pending.isEmpty()) {
            invitations.remove(invitation.inviteeId());
        }
    }

    private void closeTeam(Team team) {
        String teamName = team.getName();
        invitations.values().forEach(pending ->
                pending.values().removeIf(invitation -> invitation.internalName().equals(teamName)));
        invitations.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        teamStates.remove(teamName);
        team.unregister();
        privacyService.reevaluateAllVisibleMarkers();
        saveStateAsync();
    }

    private Team findPluginTeam(Scoreboard scoreboard, String visibleName) {
        String key = normalized(visibleName);
        return scoreboard.getTeams().stream()
                .filter(this::isPluginTeam)
                .filter(team -> normalized(team.getDisplayName()).equals(key))
                .findFirst()
                .orElse(null);
    }

    private String nextInternalName(Scoreboard scoreboard) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String suffix = Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
            String internalName = TEAM_PREFIX + suffix.substring(0, Math.min(12, suffix.length()));
            if (scoreboard.getTeam(internalName) == null) {
                return internalName;
            }
        }
        throw new IllegalStateException("Could not allocate a unique internal scoreboard team name.");
    }

    private boolean isPluginTeam(Team team) {
        return team.getName().startsWith(TEAM_PREFIX);
    }

    private Scoreboard mainScoreboard() {
        return plugin.getServer().getScoreboardManager().getMainScoreboard();
    }

    private void tellAlreadyOnTeam(Player player, Team team) {
        if (isPluginTeam(team)) {
            player.sendMessage(PREFIX + "You are already in a managed team.");
        } else {
            player.sendMessage(PREFIX + "You are already on a vanilla or administrator scoreboard team. "
                    + "Leave it before joining a managed team.");
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage("§6Team commands:");
        player.sendMessage("§f/locator team create <name> §7- Create a managed team.");
        player.sendMessage("§f/locator team invite <player> §7- Invite an online player (leader only).");
        player.sendMessage("§f/locator team accept [name] §7- Accept a pending invitation.");
        player.sendMessage("§f/locator team deny [name] §7- Reject a pending invitation.");
        player.sendMessage("§f/locator team leave §7- Leave your team.");
        player.sendMessage("§f/locator team kick <player> §7- Remove a member (leader only).");
        player.sendMessage("§f/locator team transfer <player> §7- Transfer leadership to a member.");
        player.sendMessage("§f/locator team disband [team] §7- Close your team or, with admin permission, a managed team.");
        player.sendMessage("§f/locator team info §7- Show your own team.");
        player.sendMessage("§f/locator team help §7- Show this help.");
        player.sendMessage("§7Alias: /lteam <subcommand>");
    }

    public void saveAtShutdown() {
        if (persistence == null || !persistenceWritable) {
            return;
        }
        Map<String, TeamStatePersistence.SavedTeam> snapshot = snapshotState();
        boolean saved = privacyService.runPersistenceTask(
                () -> persistence.save(snapshot), "Could not save team leadership data at shutdown.");
        if (!saved) {
            plugin.getLogger().warning("Team leadership data could not be saved before shutdown.");
        }
    }

    private void saveStateAsync() {
        if (persistence == null || !persistenceWritable) {
            return;
        }
        Map<String, TeamStatePersistence.SavedTeam> snapshot = snapshotState();
        privacyService.submitPersistenceTask(
                () -> persistence.save(snapshot),
                "Could not save team leadership data.");
    }

    private Map<String, TeamStatePersistence.SavedTeam> snapshotState() {
        Map<String, TeamStatePersistence.SavedTeam> snapshot = new LinkedHashMap<>();
        Scoreboard scoreboard = mainScoreboard();
        for (Map.Entry<String, TeamState> entry : teamStates.entrySet()) {
            Team team = scoreboard.getTeam(entry.getKey());
            if (team == null || !isPluginTeam(team)) {
                continue;
            }
            TeamState state = entry.getValue();
            snapshot.put(entry.getKey(),
                    new TeamStatePersistence.SavedTeam(state.leaderId(), state.leaderName()));
        }
        return Map.copyOf(snapshot);
    }

    private boolean isLeader(TeamState state, Player player) {
        return state != null && state.leaderId() != null
                && state.leaderId().equals(player.getUniqueId());
    }

    private void usage(Player player) {
        player.sendMessage(PREFIX + "Unknown or invalid team command. Use /locator team help.");
    }

    private String normalized(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private void requirePrimaryThread() {
        if (!primaryThread.getAsBoolean()) {
            throw new IllegalStateException("Team scoreboard operations must run on the primary server thread.");
        }
    }

    private record TeamState(UUID leaderId, String leaderName, String visibleName) {
    }

    private record Invitation(
            UUID inviteeId,
            UUID leaderId,
            String internalName,
            String visibleName,
            long expiresAtNanos
    ) {
    }
}
