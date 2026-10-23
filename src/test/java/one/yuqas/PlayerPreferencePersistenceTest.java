package one.yuqas;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerPreferencePersistenceTest {

    @TempDir
    Path dataFolder;

    private PlayerPreferencePersistence persistence() {
        return new PlayerPreferencePersistence(dataFolder, Logger.getLogger("TeamLocatorBar-test"));
    }

    @Test
    void saveAndLoadPreservesTransmissionEnabledAndModes() {
        UUID playerId = UUID.randomUUID();
        PlayerPreferenceStore store = new PlayerPreferenceStore(persistence());
        store.load(playerId);
        store.setTransmissionMode(playerId, VisibilityMode.PUBLIC);
        store.setReceptionMode(playerId, VisibilityMode.OFF);
        store.setTransmissionEnabled(playerId, false);

        assertTrue(store.save(playerId));

        PlayerPreferenceStore restored = new PlayerPreferenceStore(persistence());
        restored.load(playerId);
        assertEquals(new PlayerPreferences(VisibilityMode.PUBLIC, false, VisibilityMode.OFF), restored.get(playerId));
    }

    @Test
    void locatorOffIsPersistedWithoutForgettingTheSelectedMode() {
        UUID playerId = UUID.randomUUID();
        TeamLocatorBar plugin = mock(TeamLocatorBar.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TeamLocatorBar-test"));
        PlayerPrivacyService service = new PlayerPrivacyService(plugin, dataFolder, true);
        LocatorCommand commandExecutor = new LocatorCommand(service);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("teamlocatorbar.locator")).thenReturn(true);

        commandExecutor.onCommand(player, mock(Command.class), "locator", new String[]{"mode", "public"});
        commandExecutor.onCommand(player, mock(Command.class), "locator", new String[]{"off"});
        service.shutdownAndSave();

        PlayerPreferencePersistence.LoadResult loaded = persistence().load(playerId);
        assertEquals(new PlayerPreferences(VisibilityMode.PUBLIC, false, VisibilityMode.TEAM),
                loaded.preferences());
    }

    @Test
    void corruptAndEmptyFilesAreQuarantinedWithoutOverwritingPreviousQuarantine() throws IOException {
        UUID corruptId = UUID.randomUUID();
        Path corruptFile = dataFolder.resolve("players").resolve(corruptId + ".yml");
        Files.createDirectories(corruptFile.getParent());
        Files.writeString(corruptFile, "this: [is: not valid yaml");
        Files.writeString(corruptFile.resolveSibling(corruptFile.getFileName() + ".corrupt"), "previous");

        assertEquals(defaults(), persistence().load(corruptId).preferences());
        assertTrue(Files.exists(corruptFile.resolveSibling(corruptFile.getFileName() + ".corrupt.1")));
        assertEquals("previous", Files.readString(corruptFile.resolveSibling(corruptFile.getFileName() + ".corrupt")));

        UUID emptyId = UUID.randomUUID();
        Path emptyFile = dataFolder.resolve("players").resolve(emptyId + ".yml");
        Files.createFile(emptyFile);

        assertEquals(defaults(), persistence().load(emptyId).preferences());
        assertTrue(Files.exists(emptyFile.resolveSibling(emptyFile.getFileName() + ".corrupt")));
    }

    @Test
    void invalidRequiredValueIsQuarantinedAndUnknownModesUseFieldDefaults() throws IOException {
        UUID invalidId = UUID.randomUUID();
        Path invalidFile = dataFolder.resolve("players").resolve(invalidId + ".yml");
        Files.createDirectories(invalidFile.getParent());
        Files.writeString(invalidFile, """
                schema-version: 1
                transmission:
                  enabled: "not a boolean"
                  last-mode: PUBLIC
                reception:
                  mode: OFF
                """);

        assertEquals(defaults(), persistence().load(invalidId).preferences());
        assertTrue(Files.exists(invalidFile.resolveSibling(invalidFile.getFileName() + ".corrupt")));

        UUID unknownModeId = UUID.randomUUID();
        Path unknownModeFile = dataFolder.resolve("players").resolve(unknownModeId + ".yml");
        Files.writeString(unknownModeFile, """
                schema-version: 1
                transmission:
                  enabled: false
                  last-mode: FUTURE_MODE
                reception:
                  mode: FUTURE_MODE
                """);

        assertEquals(new PlayerPreferences(VisibilityMode.TEAM, false, VisibilityMode.TEAM),
                persistence().load(unknownModeId).preferences());
        assertFalse(Files.exists(dataFolder.resolve(unknownModeFile.getFileName() + ".corrupt")));
    }

    @Test
    void futureSchemaIsKeptAndNeverOverwritten() throws IOException {
        UUID playerId = UUID.randomUUID();
        Path file = dataFolder.resolve("players").resolve(playerId + ".yml");
        Files.createDirectories(file.getParent());
        String futureContents = """
                schema-version: 999
                opaque: preserve-me
                """;
        Files.writeString(file, futureContents);

        PlayerPreferenceStore store = new PlayerPreferenceStore(persistence());
        store.load(playerId);
        store.setTransmissionMode(playerId, VisibilityMode.PUBLIC);

        assertFalse(store.save(playerId));
        assertEquals(futureContents, Files.readString(file));
    }

    @Test
    void newPlayerWithoutFileGetsDocumentedDefaults() {
        UUID playerId = UUID.randomUUID();
        PlayerPreferenceStore store = new PlayerPreferenceStore(persistence());

        store.load(playerId);

        assertEquals(defaults(), store.get(playerId));
        assertFalse(Files.exists(dataFolder.resolve("players").resolve(playerId + ".yml")));
    }

    @Test
    void successfulAtomicSaveLeavesNoTemporaryFile() throws IOException {
        UUID playerId = UUID.randomUUID();

        assertTrue(persistence().save(playerId, new PlayerPreferences(
                VisibilityMode.PUBLIC, false, VisibilityMode.OFF
        )));

        try (var files = Files.list(dataFolder.resolve("players"))) {
            assertEquals(List.of(playerId + ".yml"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void concurrentLoadsAndSavesLeaveValidPreferences() throws Exception {
        UUID playerId = UUID.randomUUID();
        PlayerPreferencePersistence persistence = persistence();
        PlayerPreferenceStore store = new PlayerPreferenceStore(persistence);
        store.load(playerId);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> operations = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                int value = index;
                operations.add(workers.submit(() -> {
                    store.setTransmissionMode(playerId,
                            value % 2 == 0 ? VisibilityMode.PUBLIC : VisibilityMode.TEAM);
                    store.setReceptionMode(playerId,
                            value % 3 == 0 ? VisibilityMode.OFF : VisibilityMode.TEAM);
                    store.save(playerId);
                    persistence.load(playerId);
                }));
            }
            for (Future<?> operation : operations) {
                operation.get();
            }
        } finally {
            workers.shutdownNow();
        }

        assertTrue(store.save(playerId));
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(
                dataFolder.resolve("players").resolve(playerId + ".yml").toFile());
        assertEquals(PlayerPreferencePersistence.CURRENT_SCHEMA_VERSION, saved.getInt("schema-version"));
        assertTrue(saved.getBoolean("transmission.enabled"));
        assertTrue(List.of("TEAM", "PUBLIC").contains(saved.getString("transmission.last-mode")));
        assertTrue(List.of("OFF", "TEAM", "PUBLIC").contains(saved.getString("reception.mode")));
    }

    @Test
    void migratesRootLevelPreferenceFileIntoPlayersDirectoryOnFirstLoad() throws IOException {
        UUID playerId = UUID.randomUUID();
        Path legacyFile = dataFolder.resolve(playerId + ".yml");
        Files.writeString(legacyFile, """
                schema-version: 1
                transmission:
                  enabled: false
                  last-mode: PUBLIC
                reception:
                  mode: "OFF"
                """);

        assertEquals(new PlayerPreferences(VisibilityMode.PUBLIC, false, VisibilityMode.OFF),
                persistence().load(playerId).preferences());
        assertFalse(Files.exists(legacyFile));
        assertTrue(Files.exists(dataFolder.resolve("players").resolve(playerId + ".yml")));
    }

    @Test
    void rejectedPreLoginReleasesLoadedPreferencesWithoutDeletingDiskFile() {
        UUID playerId = UUID.randomUUID();
        PlayerPreferencePersistence disk = persistence();
        assertTrue(disk.save(playerId,
                new PlayerPreferences(VisibilityMode.PUBLIC, true, VisibilityMode.OFF)));

        TeamLocatorBar plugin = mock(TeamLocatorBar.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TeamLocatorBar-test"));
        PlayerPrivacyService service = new PlayerPrivacyService(plugin, dataFolder, true);
        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(playerId);
        when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.KICK_BANNED);

        service.loadPreferencesOnPreLogin(event);
        assertEquals(VisibilityMode.PUBLIC, service.getTransmissionMode(playerId));

        service.discardRejectedPreLogin(event);

        assertEquals(VisibilityMode.TEAM, service.getTransmissionMode(playerId));
        assertTrue(Files.exists(dataFolder.resolve("players").resolve(playerId + ".yml")));
        service.shutdownAndSave();
    }

    @Test
    void preLoginThatNeverReachesJoinReleasesPreferencesButKeepsDiskFile() {
        UUID playerId = UUID.randomUUID();
        PlayerPreferencePersistence disk = persistence();
        assertTrue(disk.save(playerId,
                new PlayerPreferences(VisibilityMode.PUBLIC, true, VisibilityMode.OFF)));

        TeamLocatorBar plugin = mock(TeamLocatorBar.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TeamLocatorBar-test"));
        PlayerPrivacyService service = new PlayerPrivacyService(plugin, dataFolder, true);
        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(playerId);
        when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);

        service.loadPreferencesOnPreLogin(event);
        service.discardRejectedPreLogin(event);
        assertEquals(VisibilityMode.PUBLIC, service.getTransmissionMode(playerId));

        service.discardUnjoinedPreLogin(playerId);

        assertEquals(VisibilityMode.TEAM, service.getTransmissionMode(playerId));
        assertTrue(Files.exists(dataFolder.resolve("players").resolve(playerId + ".yml")));
        service.shutdownAndSave();
    }

    private PlayerPreferences defaults() {
        return new PlayerPreferences(VisibilityMode.TEAM, true, VisibilityMode.TEAM);
    }
}
