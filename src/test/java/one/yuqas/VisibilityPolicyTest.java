package one.yuqas;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibilityPolicyTest {

    private final UUID transmitter = UUID.randomUUID();
    private final UUID receiver = UUID.randomUUID();

    @Test
    void preferencesDefaultToTeamForTransmissionAndReception() {
        PlayerPreferenceStore store = new PlayerPreferenceStore();

        assertEquals(VisibilityMode.TEAM, store.get(transmitter).transmission());
        assertEquals(VisibilityMode.TEAM, store.get(transmitter).reception());
    }

    @Test
    void transmissionOffDeniesTeammatesAndOtherPlayers() {
        assertDenied(VisibilityMode.OFF, VisibilityMode.PUBLIC, true);
        assertDenied(VisibilityMode.OFF, VisibilityMode.PUBLIC, false);
    }

    @Test
    void teamTransmissionAllowsTeammateRegardlessOfReceptionMode() {
        assertAllowed(VisibilityMode.TEAM, VisibilityMode.OFF, true);
    }

    @Test
    void teamTransmissionDeniesOtherTeamsEvenWithPublicReception() {
        assertDenied(VisibilityMode.TEAM, VisibilityMode.PUBLIC, false);
    }

    @Test
    void publicTransmissionAllowsTeammatesRegardlessOfReceptionMode() {
        assertAllowed(VisibilityMode.PUBLIC, VisibilityMode.OFF, true);
    }

    @Test
    void publicTransmissionRequiresPublicReceptionAcrossTeams() {
        assertDenied(VisibilityMode.PUBLIC, VisibilityMode.OFF, false);
        assertDenied(VisibilityMode.PUBLIC, VisibilityMode.TEAM, false);
        assertAllowed(VisibilityMode.PUBLIC, VisibilityMode.PUBLIC, false);
    }

    @Test
    void playersWithoutTeamsAreNotTeammates() {
        assertDenied(VisibilityMode.TEAM, VisibilityMode.OFF, false);
        assertAllowed(VisibilityMode.PUBLIC, VisibilityMode.PUBLIC, false);
    }

    @Test
    void playerCannotSeeTheirOwnMarker() {
        assertFalse(VisibilityPolicy.canSee(
                transmitter,
                transmitter,
                VisibilityMode.PUBLIC,
                VisibilityMode.PUBLIC,
                true
        ));
    }

    @Test
    void transmissionAndReceptionPreferencesRemainIndependent() {
        PlayerPreferenceStore store = new PlayerPreferenceStore();
        store.setTransmissionMode(transmitter, VisibilityMode.PUBLIC);

        assertEquals(VisibilityMode.PUBLIC, store.get(transmitter).transmission());
        assertEquals(VisibilityMode.TEAM, store.get(transmitter).reception());

        store.setReceptionMode(transmitter, VisibilityMode.OFF);
        assertEquals(VisibilityMode.PUBLIC, store.get(transmitter).transmission());
        assertEquals(VisibilityMode.OFF, store.get(transmitter).reception());
    }

    @Test
    void worldRestrictionsHavePriorityWithoutChangingPreferences() {
        PlayerPreferenceStore store = new PlayerPreferenceStore();
        store.setTransmissionMode(transmitter, VisibilityMode.PUBLIC);
        store.setReceptionMode(receiver, VisibilityMode.PUBLIC);

        assertFalse(VisibilityPolicy.canSee(
                transmitter,
                receiver,
                store.get(transmitter).transmission(),
                store.get(receiver).reception(),
                true,
                false,
                true
        ));
        assertEquals(VisibilityMode.PUBLIC, store.get(transmitter).transmission());
        assertEquals(VisibilityMode.PUBLIC, store.get(receiver).reception());

        assertTrue(VisibilityPolicy.canSee(
                transmitter,
                receiver,
                store.get(transmitter).transmission(),
                store.get(receiver).reception(),
                true,
                true,
                true
        ));
    }

    private void assertAllowed(VisibilityMode transmission, VisibilityMode reception, boolean sameTeam) {
        assertTrue(VisibilityPolicy.canSee(transmitter, receiver, transmission, reception, sameTeam));
    }

    private void assertDenied(VisibilityMode transmission, VisibilityMode reception, boolean sameTeam) {
        assertFalse(VisibilityPolicy.canSee(transmitter, receiver, transmission, reception, sameTeam));
    }
}
