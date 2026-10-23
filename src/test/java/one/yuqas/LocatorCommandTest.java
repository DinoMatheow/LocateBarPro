package one.yuqas;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocatorCommandTest {

    private static final String LOCATOR_PERMISSION = "teamlocatorbar.locator";

    private PlayerPrivacyService privacyService;
    private LocatorCommand locatorCommand;
    private Player player;
    private UUID playerId;
    private Command command;

    @BeforeEach
    void setUp() {
        privacyService = new PlayerPrivacyService(mock(TeamLocatorBar.class));
        locatorCommand = new LocatorCommand(privacyService);
        player = mock(Player.class);
        playerId = UUID.randomUUID();
        command = mock(Command.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission(LOCATOR_PERMISSION)).thenReturn(true);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
    }

    @Test
    void statusShowsCurrentPersonalPreferences() {
        privacyService.setTransmissionMode(playerId, VisibilityMode.PUBLIC);
        privacyService.setReceptionMode(playerId, VisibilityMode.OFF);
        locatorCommand.onCommand(player, command, "locator", new String[]{"status"});

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(player, times(6)).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().stream().anyMatch(message ->
                message.contains("Transmission: §aactive") && message.contains("PUBLIC")));
        assertTrue(messages.getAllValues().stream().anyMatch(message ->
                message.contains("Reception: §eOFF")));
        assertTrue(messages.getAllValues().stream().anyMatch(message ->
                message.contains("World restriction: §aallowed")));
    }

    @Test
    void offThenOnRestoresTheLastSelectedTransmissionMode() {
        locatorCommand.onCommand(player, command, "locator", new String[]{"mode", "public"});
        locatorCommand.onCommand(player, command, "locator", new String[]{"off"});

        assertFalse(privacyService.isTransmissionEnabled(playerId));
        assertEquals(VisibilityMode.OFF, privacyService.getTransmissionMode(playerId));
        assertEquals(VisibilityMode.PUBLIC, privacyService.getLastTransmissionMode(playerId));

        locatorCommand.onCommand(player, command, "locator", new String[]{"on"});

        assertTrue(privacyService.isTransmissionEnabled(playerId));
        assertEquals(VisibilityMode.PUBLIC, privacyService.getTransmissionMode(playerId));
    }

    @Test
    void modeCommandsSelectAndEnableTheirModesCaseInsensitively() {
        locatorCommand.onCommand(player, command, "locator", new String[]{"mode", "TEAM"});
        assertEquals(VisibilityMode.TEAM, privacyService.getTransmissionMode(playerId));

        locatorCommand.onCommand(player, command, "locator", new String[]{"mode", "PUBLIC"});
        assertEquals(VisibilityMode.PUBLIC, privacyService.getTransmissionMode(playerId));
        assertTrue(privacyService.isTransmissionEnabled(playerId));
    }

    @Test
    void receiveCommandsDoNotChangeTransmission() {
        locatorCommand.onCommand(player, command, "locator", new String[]{"mode", "public"});

        for (String mode : new String[]{"off", "team", "public"}) {
            locatorCommand.onCommand(player, command, "locator", new String[]{"receive", mode});
            assertEquals(VisibilityMode.PUBLIC, privacyService.getTransmissionMode(playerId));
            assertEquals(VisibilityMode.valueOf(mode.toUpperCase()), privacyService.getReceptionMode(playerId));
        }
    }

    @Test
    void transmissionDefaultsToTeamAndCanBeRestoredAfterOff() {
        assertEquals(VisibilityMode.TEAM, privacyService.getLastTransmissionMode(playerId));
        assertTrue(privacyService.isTransmissionEnabled(playerId));

        locatorCommand.onCommand(player, command, "locator", new String[]{"off"});
        locatorCommand.onCommand(player, command, "locator", new String[]{"on"});

        assertEquals(VisibilityMode.TEAM, privacyService.getTransmissionMode(playerId));
    }

    @Test
    void unknownAndMalformedCommandsShowHelpWithoutThrowing() {
        locatorCommand.onCommand(player, command, "locator", new String[]{"unknown"});
        locatorCommand.onCommand(player, command, "locator", new String[]{"mode"});
        locatorCommand.onCommand(player, command, "locator", new String[]{"receive", "team", "extra"});
        locatorCommand.onCommand(player, command, "locator", new String[]{"help", "extra"});
        locatorCommand.onCommand(player, command, "locator", new String[]{"mode", "public", UUID.randomUUID().toString()});

        verify(player, atLeast(4)).sendMessage(messageContaining("/locator"));
        assertEquals(VisibilityMode.TEAM, privacyService.getTransmissionMode(playerId));
    }

    @Test
    void playerWithoutPermissionCannotChangePreferences() {
        when(player.hasPermission(LOCATOR_PERMISSION)).thenReturn(false);

        locatorCommand.onCommand(player, command, "locator", new String[]{"mode", "public"});

        assertEquals(VisibilityMode.TEAM, privacyService.getTransmissionMode(playerId));
        verify(player).sendMessage(messageContaining("permission"));
    }

    @Test
    void consoleCannotUsePersonalPreferencesOrSupplyAnotherPlayer() {
        CommandSender console = mock(CommandSender.class);

        locatorCommand.onCommand(console, command, "locator", new String[]{"mode", "public", "someone"});

        verify(console).sendMessage(messageContaining("only be used by a player"));
        assertEquals(VisibilityMode.TEAM, privacyService.getTransmissionMode(playerId));
    }

    @Test
    void commandPreferencesAreTheModesReadByTheVisibilityPolicy() {
        UUID otherPlayer = UUID.randomUUID();
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(otherPlayer);
        when(other.hasPermission(LOCATOR_PERMISSION)).thenReturn(true);

        locatorCommand.onCommand(player, command, "locator", new String[]{"mode", "public"});
        locatorCommand.onCommand(other, command, "locator", new String[]{"receive", "off"});
        assertFalse(VisibilityPolicy.canSee(
                playerId, otherPlayer,
                privacyService.getTransmissionMode(playerId),
                privacyService.getReceptionMode(otherPlayer),
                false
        ));
        assertTrue(VisibilityPolicy.canSee(
                playerId, otherPlayer,
                privacyService.getTransmissionMode(playerId),
                privacyService.getReceptionMode(otherPlayer),
                true
        ));

        locatorCommand.onCommand(player, command, "locator", new String[]{"off"});
        assertFalse(VisibilityPolicy.canSee(
                playerId, otherPlayer,
                privacyService.getTransmissionMode(playerId),
                privacyService.getReceptionMode(otherPlayer),
                true
        ));
    }

    @Test
    void reloadRequiresItsSeparateAdministrativePermission() {
        TeamLocatorBar plugin = mock(TeamLocatorBar.class);
        CommandSender sender = mock(CommandSender.class);
        Command reloadCommand = mock(Command.class);
        when(sender.hasPermission("teamlocatorbar.reload")).thenReturn(false);
        doCallRealMethod().when(plugin).onCommand(sender, reloadCommand, "teamlocatorbar", new String[0]);

        plugin.onCommand(sender, reloadCommand, "teamlocatorbar", new String[0]);

        verify(sender).sendMessage(messageContaining("permission"));
        verify(plugin, never()).reloadConfig();
    }

    @Test
    void statusReportsWhenCurrentWorldIsRestricted() {
        TeamLocatorBar plugin = mock(TeamLocatorBar.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("TeamLocatorBar-test"));
        when(plugin.getConfig()).thenReturn(config);
        when(config.get("worlds.mode")).thenReturn("WHITELIST");
        when(config.get("worlds.list")).thenReturn(java.util.List.of("allowed-world"));
        PlayerPrivacyService restrictedService = new PlayerPrivacyService(plugin);
        restrictedService.reloadWorldRestriction();

        World world = mock(World.class);
        when(world.getName()).thenReturn("blocked-world");
        when(player.getWorld()).thenReturn(world);
        LocatorCommand restrictedCommand = new LocatorCommand(restrictedService);

        restrictedCommand.onCommand(player, command, "locator", new String[]{"status"});

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(player, times(6)).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().stream().anyMatch(message ->
                message.contains("World restriction: §crestricted")));
    }

    private static String messageContaining(String expected) {
        return org.mockito.ArgumentMatchers.argThat((String message) -> message.contains(expected));
    }
}
