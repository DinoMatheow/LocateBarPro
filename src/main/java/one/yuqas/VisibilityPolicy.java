package one.yuqas;

import java.util.UUID;

public final class VisibilityPolicy {

    private VisibilityPolicy() {
    }

    public static boolean canSee(
            UUID transmitterId,
            UUID receiverId,
            VisibilityMode transmission,
            VisibilityMode reception,
            boolean sameTeam
    ) {
        return canSee(transmitterId, receiverId, transmission, reception, sameTeam, true, true);
    }

    public static boolean canSee(
            UUID transmitterId,
            UUID receiverId,
            VisibilityMode transmission,
            VisibilityMode reception,
            boolean sameTeam,
            boolean transmitterWorldAllowed,
            boolean receiverWorldAllowed
    ) {
        if (!transmitterWorldAllowed || !receiverWorldAllowed) {
            return false;
        }
        if (transmission == VisibilityMode.OFF) {
            return false;
        }
        if (transmitterId.equals(receiverId)) {
            return false;
        }
        if (sameTeam && (transmission == VisibilityMode.TEAM || transmission == VisibilityMode.PUBLIC)) {
            return true;
        }
        return transmission == VisibilityMode.PUBLIC && reception == VisibilityMode.PUBLIC;
    }
}
