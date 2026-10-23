package one.yuqas;

import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamManagementServiceTest {

    private static final String TEAM_PERMISSION = "teamlocatorbar.team";

    @Test
    void createUsesPrefixedMainScoreboardTeamAndRejectsInvalidOrDuplicateNames() {
        Fixture fixture = new Fixture(8);
        Player leader = fixture.player("Leader");

        fixture.execute(leader, "create", "Alpha_1");
        Team created = fixture.teamFor(leader);

        assertTrue(created.getName().startsWith("tlb_"));
        assertEquals("Alpha_1", created.getDisplayName());
        assertEquals(Set.of("Leader"), created.getEntries());
        verify(fixture.privacy, times(1)).reevaluateAllVisibleMarkers();

        fixture.execute(leader, "create", "bad name");
        fixture.execute(leader, "create", "this-name-is-over-16");
        fixture.execute(leader, "create", "Alpha_1");
        fixture.execute(leader, "create", "aLPHa_1");
        assertEquals(1, fixture.teams.size());
        verify(fixture.privacy, times(1)).reevaluateAllVisibleMarkers();
    }

    @Test
    void vanillaTeamMembershipBlocksCreateAndAcceptWithoutChangingTheTeam() {
        Fixture fixture = new Fixture(8);
        Player leader = fixture.player("Leader");
        Player invitee = fixture.player("Invitee");
        Team vanilla = fixture.newTeam("admins", "Admins");
        fixture.addEntry(vanilla, leader);
        fixture.execute(leader, "create", "NewTeam");
        assertEquals(vanilla, fixture.teamFor(leader));

        fixture.removeEntry(vanilla, leader);
        fixture.execute(leader, "create", "AllowedTeam");
        fixture.execute(leader, "invite", invitee.getName());
        Team anotherVanilla = fixture.newTeam("staff", "Staff");
        fixture.addEntry(anotherVanilla, invitee);

        fixture.execute(invitee, "accept");
        assertEquals(anotherVanilla, fixture.teamFor(invitee));
        assertTrue(fixture.messages(invitee).stream().anyMatch(message ->
                message.contains("already on a vanilla or administrator scoreboard team")),
                fixture.messages(invitee).toString());
        verify(fixture.privacy, times(1)).reevaluateAllVisibleMarkers();
    }

    @Test
    void inviteIsClickableAndAcceptingItAddsOneMemberAndReevaluatesPrivacy() {
        Fixture fixture = new Fixture(3);
        Player leader = fixture.player("Leader");
        Player invitee = fixture.player("Invitee");
        fixture.execute(leader, "create", "Raiders");

        fixture.execute(leader, "invite", "Invitee");
        ArgumentCaptor<Component> component = ArgumentCaptor.forClass(Component.class);
        verify(invitee, times(2)).sendMessage(component.capture());
        assertTrue(component.getAllValues().stream().anyMatch(value ->
                value.toString().contains("/locator team accept Raiders")
                        && value.toString().contains("/locator team deny Raiders")));

        fixture.execute(invitee, "accept");

        assertEquals(fixture.teamFor(leader), fixture.teamFor(invitee));
        assertEquals(Set.of("Leader", "Invitee"), fixture.teamFor(leader).getEntries());
        verify(fixture.privacy, times(2)).reevaluateAllVisibleMarkers();
        verify(leader).sendMessage(org.mockito.ArgumentMatchers.argThat((String message) ->
                message.contains("joined your team")));
    }

    @Test
    void invitationsRejectSelfDuplicateOfflineAlreadyGroupedAndFullTargets() {
        Fixture fixture = new Fixture(2);
        Player leader = fixture.player("Leader");
        Player teammate = fixture.player("Teammate");
        Player invitee = fixture.player("Invitee");
        fixture.execute(leader, "create", "Pair");
        fixture.execute(leader, "invite", "Leader");
        fixture.execute(leader, "invite", "Missing");
        fixture.execute(leader, "invite", "Invitee");
        fixture.execute(leader, "invite", "Invitee");
        fixture.execute(leader, "accept");
        fixture.execute(leader, "invite", "Teammate");
        fixture.execute(teammate, "accept");
        fixture.execute(leader, "invite", "Invitee");

        assertEquals(2, fixture.teamFor(leader).getEntries().size());
        assertTrue(fixture.messages(leader).stream().anyMatch(message -> message.contains("member limit")));
    }

    @Test
    void inviteExpiresAndDisconnectingEitherPartyRemovesPendingInvitations() {
        Fixture fixture = new Fixture(8);
        Player leader = fixture.player("Leader");
        Player invitee = fixture.player("Invitee");
        fixture.execute(leader, "create", "Timed");
        fixture.execute(leader, "invite", "Invitee");

        fixture.now.addAndGet(61_000_000_000L);
        fixture.execute(invitee, "accept");
        assertEquals(null, fixture.teamFor(invitee));
        assertTrue(fixture.messages(invitee).stream().anyMatch(message -> message.contains("no pending")));

        fixture.execute(leader, "invite", "Invitee");
        fixture.teamsService.onPlayerQuit(fixture.quit(leader));
        fixture.execute(invitee, "accept");
        assertEquals(null, fixture.teamFor(invitee));
    }

    @Test
    void acceptanceRequiresNameWhenSeveralInvitationsArePendingAndCanSelectByCaseInsensitiveName() {
        Fixture fixture = new Fixture(8);
        Player firstLeader = fixture.player("First");
        Player secondLeader = fixture.player("Second");
        Player invitee = fixture.player("Invitee");
        fixture.execute(firstLeader, "create", "One");
        fixture.execute(secondLeader, "create", "Two");
        fixture.execute(firstLeader, "invite", "Invitee");
        fixture.execute(secondLeader, "invite", "Invitee");

        fixture.execute(invitee, "accept");
        assertEquals(null, fixture.teamFor(invitee));
        fixture.execute(invitee, "accept", "tWO");

        assertEquals(fixture.teamFor(secondLeader), fixture.teamFor(invitee));
        verify(fixture.privacy, times(3)).reevaluateAllVisibleMarkers();
    }

    @Test
    void acceptRechecksMemberCapacityAndRejectsPlayersWhoJoinedAnotherTeam() {
        Fixture fullFixture = new Fixture(2);
        Player leader = fullFixture.player("Leader");
        Player first = fullFixture.player("First");
        Player second = fullFixture.player("Second");
        fullFixture.execute(leader, "create", "Full");
        fullFixture.execute(leader, "invite", "First");
        fullFixture.execute(leader, "invite", "Second");
        fullFixture.execute(first, "accept");
        fullFixture.execute(second, "accept");
        assertEquals(null, fullFixture.teamFor(second));

        Fixture singleTeamFixture = new Fixture(8);
        Player firstLeader = singleTeamFixture.player("FirstLeader");
        Player secondLeader = singleTeamFixture.player("SecondLeader");
        Player invitee = singleTeamFixture.player("Invitee");
        singleTeamFixture.execute(firstLeader, "create", "FirstTeam");
        singleTeamFixture.execute(secondLeader, "create", "SecondTeam");
        singleTeamFixture.execute(firstLeader, "invite", "Invitee");
        singleTeamFixture.execute(secondLeader, "invite", "Invitee");
        singleTeamFixture.execute(invitee, "accept", "SecondTeam");
        singleTeamFixture.execute(invitee, "accept", "FirstTeam");

        assertEquals(singleTeamFixture.teamFor(secondLeader), singleTeamFixture.teamFor(invitee));
        verify(singleTeamFixture.privacy, times(3)).reevaluateAllVisibleMarkers();
    }

    @Test
    void denyNotifiesConnectedLeaderAndDisconnectCleanupWorksForInvitee() {
        Fixture fixture = new Fixture(8);
        Player leader = fixture.player("Leader");
        Player invitee = fixture.player("Invitee");
        fixture.execute(leader, "create", "Team");
        fixture.execute(leader, "invite", "Invitee");
        fixture.execute(invitee, "deny");
        verify(leader).sendMessage(org.mockito.ArgumentMatchers.argThat((String message) ->
                message.contains("rejected your invitation")));

        fixture.execute(leader, "invite", "Invitee");
        fixture.teamsService.onPlayerQuit(fixture.quit(invitee));
        fixture.execute(invitee, "accept");
        assertEquals(null, fixture.teamFor(invitee));
    }

    @Test
    void membershipRemainsOnTheScoreboardWhenPlayerDisconnects() {
        Fixture fixture = new Fixture(8);
        Player leader = fixture.player("Leader");
        Player member = fixture.player("Member");
        fixture.execute(leader, "create", "Persist");
        fixture.execute(leader, "invite", "Member");
        fixture.execute(member, "accept");

        fixture.teamsService.onPlayerQuit(fixture.quit(member));

        assertEquals(fixture.teamFor(leader), fixture.teamFor(member));
        assertEquals(Set.of("Leader", "Member"), fixture.teamFor(leader).getEntries());
    }

    @Test
    void membersCanLeaveButLeaderMustDisbandUnlessAlone() {
        Fixture fixture = new Fixture(8);
        Player leader = fixture.player("Leader");
        Player member = fixture.player("Member");
        fixture.execute(leader, "create", "Crew");
        fixture.execute(leader, "invite", "Member");
        fixture.execute(member, "accept");

        fixture.execute(leader, "leave");
        assertEquals(fixture.teamFor(member), fixture.teamFor(leader));
        fixture.execute(member, "leave");
        assertEquals(null, fixture.teamFor(member));
        verify(fixture.privacy, times(3)).reevaluateAllVisibleMarkers();

        fixture.execute(leader, "leave");
        assertEquals(null, fixture.teamFor(leader));
        assertEquals(0, fixture.teams.size());
        verify(fixture.privacy, times(4)).reevaluateAllVisibleMarkers();
    }

    @Test
    void disbandRemovesTeamAndInvitationsAndIsLeaderOnly() {
        Fixture fixture = new Fixture(8);
        Player leader = fixture.player("Leader");
        Player member = fixture.player("Member");
        Player invitee = fixture.player("Invitee");
        fixture.execute(leader, "create", "Crew");
        fixture.execute(leader, "invite", "Member");
        fixture.execute(member, "accept");
        fixture.execute(leader, "invite", "Invitee");
        fixture.execute(member, "disband");
        assertEquals(1, fixture.teams.size());
        fixture.execute(leader, "disband");

        assertEquals(0, fixture.teams.size());
        assertEquals(null, fixture.teamFor(leader));
        assertEquals(null, fixture.teamFor(member));
        fixture.execute(invitee, "accept");
        assertEquals(null, fixture.teamFor(invitee));
        verify(fixture.privacy, times(3)).reevaluateAllVisibleMarkers();
    }

    @Test
    void infoOnlyShowsTheExecutingPlayersManagedTeam() {
        Fixture fixture = new Fixture(8);
        Player leader = fixture.player("Leader");
        Player outsider = fixture.player("Outsider");
        fixture.execute(leader, "create", "Private");
        fixture.execute(leader, "info");
        fixture.execute(outsider, "info");

        assertTrue(fixture.messages(leader).stream().anyMatch(message ->
                        message.contains("Team:") && message.contains("Private")),
                fixture.messages(leader).toString());
        assertTrue(fixture.messages(outsider).stream().anyMatch(message -> message.contains("not a member")));
        assertTrue(fixture.messages(outsider).stream().noneMatch(message -> message.contains("Private")));
    }

    @Test
    void everyMembershipMutationReevaluatesPrivacyImmediately() {
        Fixture fixture = new Fixture(8);
        Player leader = fixture.player("Leader");
        Player member = fixture.player("Member");

        fixture.execute(leader, "create", "Immediate");
        fixture.execute(leader, "invite", "Member");
        fixture.execute(member, "accept");
        fixture.execute(member, "leave");
        fixture.execute(leader, "disband");

        verify(fixture.privacy, times(4)).reevaluateAllVisibleMarkers();
    }

    @Test
    void permissionIsCheckedForEverySubcommandAndConsoleCannotUseTeamCommands() {
        Fixture fixture = new Fixture(8);
        Player player = fixture.player("NoPermission");
        when(player.hasPermission(TEAM_PERMISSION)).thenReturn(false);
        for (String[] args : new String[][]{
                {"create", "X"}, {"invite", "Someone"}, {"accept"}, {"deny"},
                {"leave"}, {"disband"}, {"info"}, {"help"}
        }) {
            fixture.execute(player, args);
        }
        assertTrue(fixture.teams.isEmpty());
        verify(fixture.privacy, never()).reevaluateAllVisibleMarkers();
        verify(player, times(8)).sendMessage(org.mockito.ArgumentMatchers.argThat((String message) ->
                message.contains("permission")));

        TeamCommand command = new TeamCommand(fixture.teamsService);
        CommandSender console = mock(CommandSender.class);
        command.onCommand(console, mock(Command.class), "lteam", new String[]{"help"});
        verify(console).sendMessage(org.mockito.ArgumentMatchers.argThat((String message) ->
                message.contains("only be used by a player")));
    }

    @Test
    void aliasExecutesTeamSubcommandsAndScoreboardAccessRequiresPrimaryThread() {
        Fixture fixture = new Fixture(8);
        Player leader = fixture.player("Leader");
        TeamCommand command = new TeamCommand(fixture.teamsService);
        command.onCommand(leader, mock(Command.class), "lteam", new String[]{"create", "Alias"});
        assertEquals("Alias", fixture.teamFor(leader).getDisplayName());

        TeamManagementService offThread = new TeamManagementService(
                fixture.plugin, fixture.privacy, fixture.now::get, () -> false);
        assertThrows(IllegalStateException.class, () -> offThread.execute(leader, new String[]{"info"}));
    }

    private final class Fixture {
        private final TeamLocatorBar plugin = mock(TeamLocatorBar.class);
        private final PlayerPrivacyService privacy = mock(PlayerPrivacyService.class);
        private final FileConfiguration config = mock(FileConfiguration.class);
        private final Server server = mock(Server.class);
        private final GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        private final ScoreboardManager scoreboardManager = mock(ScoreboardManager.class);
        private final Scoreboard scoreboard = mock(Scoreboard.class);
        private final Map<String, Team> teams = new LinkedHashMap<>();
        private final Map<String, Team> entries = new HashMap<>();
        private final Map<String, Player> online = new HashMap<>();
        private final Map<Player, java.util.List<String>> messages = new HashMap<>();
        private final AtomicLong now = new AtomicLong();
        private final TeamManagementService teamsService;

        private Fixture(int maxMembers) {
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("team-management-test"));
            when(plugin.getServer()).thenReturn(server);
            when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
            when(server.getScoreboardManager()).thenReturn(scoreboardManager);
            when(scoreboardManager.getMainScoreboard()).thenReturn(scoreboard);
            when(config.get("teams.max-members")).thenReturn(maxMembers);
            when(config.get("teams.invite-timeout-seconds")).thenReturn(60);
            when(scoreboard.getTeams()).thenAnswer(invocation -> new HashSet<>(teams.values()));
            when(scoreboard.getTeam(anyString())).thenAnswer(invocation ->
                    teams.get(invocation.getArgument(0)));
            when(scoreboard.getEntryTeam(anyString())).thenAnswer(invocation ->
                    entries.get(invocation.getArgument(0)));
            doAnswer(invocation -> {
                String teamName = invocation.getArgument(0);
                Team team = newTeam(teamName, teamName);
                return team;
            }).when(scoreboard).registerNewTeam(anyString());
            when(server.getPlayerExact(anyString())).thenAnswer(invocation ->
                    online.get(((String) invocation.getArgument(0)).toLowerCase()));
            when(server.getPlayer(any(UUID.class))).thenAnswer(invocation ->
                    online.values().stream()
                            .filter(player -> player.getUniqueId().equals(invocation.getArgument(0)))
                            .findFirst().orElse(null));
            teamsService = new TeamManagementService(plugin, privacy, now::get, () -> true);
        }

        private Player player(String name) {
            Player player = mock(Player.class);
            when(player.getName()).thenReturn(name);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.hasPermission(TEAM_PERMISSION)).thenReturn(true);
            when(player.isOnline()).thenReturn(true);
            doAnswer(invocation -> {
                messages.computeIfAbsent(player, ignored -> new java.util.ArrayList<>())
                        .add(invocation.getArgument(0));
                return null;
            }).when(player).sendMessage(anyString());
            doAnswer(invocation -> {
                messages.computeIfAbsent(player, ignored -> new java.util.ArrayList<>())
                        .add(invocation.getArgument(0).toString());
                return null;
            }).when(player).sendMessage(any(Component.class));
            online.put(name.toLowerCase(), player);
            return player;
        }

        private Team newTeam(String internalName, String displayName) {
            Team team = mock(Team.class);
            Set<String> members = new HashSet<>();
            String[] currentDisplayName = {displayName};
            when(team.getName()).thenReturn(internalName);
            when(team.getDisplayName()).thenAnswer(invocation -> currentDisplayName[0]);
            when(team.getEntries()).thenAnswer(invocation -> Set.copyOf(members));
            doAnswer(invocation -> {
                currentDisplayName[0] = invocation.getArgument(0);
                return null;
            }).when(team).setDisplayName(anyString());
            doAnswer(invocation -> {
                String name = invocation.getArgument(0);
                Team previous = entries.put(name, team);
                if (previous != null && previous != team) {
                    previous.removeEntry(name);
                }
                members.add(name);
                return null;
            }).when(team).addEntry(anyString());
            doAnswer(invocation -> {
                String name = invocation.getArgument(0);
                members.remove(name);
                entries.remove(name, team);
                return null;
            }).when(team).removeEntry(anyString());
            doAnswer(invocation -> {
                teams.remove(internalName);
                members.forEach(name -> entries.remove(name, team));
                members.clear();
                return null;
            }).when(team).unregister();
            teams.put(internalName, team);
            return team;
        }

        private void addEntry(Team team, Player player) {
            team.addEntry(player.getName());
        }

        private void removeEntry(Team team, Player player) {
            team.removeEntry(player.getName());
        }

        private Team teamFor(Player player) {
            return entries.get(player.getName());
        }

        private void execute(Player player, String... args) {
            teamsService.execute(player, args);
        }

        private PlayerQuitEvent quit(Player player) {
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);
            return event;
        }

        private java.util.List<String> messages(Player player) {
            return messages.getOrDefault(player, java.util.List.of());
        }

    }
}
