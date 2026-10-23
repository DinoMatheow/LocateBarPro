package one.yuqas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamStatePersistenceTest {

    @TempDir
    Path dataDirectory;

    private TeamStatePersistence persistence() {
        return new TeamStatePersistence(dataDirectory, Logger.getLogger("team-state-persistence-test"));
    }

    @Test
    void saveAndLoadPersistsOnlyLeaderIdentityAndSchemaVersion() throws IOException {
        UUID leaderId = UUID.randomUUID();
        TeamStatePersistence persistence = persistence();

        assertTrue(persistence.save(Map.of(
                "tlb_team1", new TeamStatePersistence.SavedTeam(leaderId, "Leader"),
                "tlb_team2", new TeamStatePersistence.SavedTeam(null, null)
        )));

        TeamStatePersistence.LoadResult loaded = persistence.load();
        assertTrue(loaded.writable());
        assertEquals(Map.of(
                "tlb_team1", new TeamStatePersistence.SavedTeam(leaderId, "Leader"),
                "tlb_team2", new TeamStatePersistence.SavedTeam(null, null)
        ), loaded.teams());
        String contents = Files.readString(dataDirectory.resolve("teams.yml"));
        assertTrue(contents.contains("schema-version: 1"));
        assertFalse(contents.contains("members:"));
    }

    @Test
    void corruptFileIsQuarantinedAndExistingQuarantineIsPreserved() throws IOException {
        Path file = dataDirectory.resolve("teams.yml");
        Files.writeString(file, "schema-version: 1\nteams: [invalid");
        Files.writeString(dataDirectory.resolve("teams.yml.corrupt"), "previous");

        TeamStatePersistence.LoadResult loaded = persistence().load();

        assertTrue(loaded.teams().isEmpty());
        assertTrue(loaded.writable());
        assertEquals("previous", Files.readString(dataDirectory.resolve("teams.yml.corrupt")));
        assertTrue(Files.exists(dataDirectory.resolve("teams.yml.corrupt.1")));
        assertFalse(Files.exists(file));
    }

    @Test
    void futureSchemaIsLeftUntouchedAndIsNotWritable() throws IOException {
        Path file = dataDirectory.resolve("teams.yml");
        String contents = "schema-version: 99\nopaque: preserve\n";
        Files.writeString(file, contents);

        TeamStatePersistence.LoadResult loaded = persistence().load();

        assertFalse(loaded.writable());
        assertTrue(loaded.teams().isEmpty());
        assertEquals(contents, Files.readString(file));
    }
}
