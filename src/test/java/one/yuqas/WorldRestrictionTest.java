package one.yuqas;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldRestrictionTest {

    private final Logger logger = Logger.getLogger("TeamLocatorBar-world-test");

    @Test
    void blacklistAllowsUnlistedWorldsAndBlocksListedWorldsCaseInsensitively() {
        WorldRestriction restriction = WorldRestriction.fromConfig("BLACKLIST", List.of("Nether"), logger);

        assertFalse(restriction.isAllowed("nEtHeR"));
        assertTrue(restriction.isAllowed("world"));
        assertTrue(restriction.isAllowed("world-that-does-not-exist"));
    }

    @Test
    void whitelistAllowsOnlyListedWorldsCaseInsensitively() {
        WorldRestriction restriction = WorldRestriction.fromConfig("WHITELIST", List.of("world"), logger);

        assertTrue(restriction.isAllowed("WORLD"));
        assertFalse(restriction.isAllowed("nether"));
        assertFalse(restriction.isAllowed("world-that-does-not-exist"));
    }

    @Test
    void invalidModeOrMalformedListFallsBackToDefaultBlacklistWithEmptyList() {
        WorldRestriction invalidMode = WorldRestriction.fromConfig("INVALID", List.of("world"), logger);
        WorldRestriction malformedList = WorldRestriction.fromConfig("WHITELIST", List.of("world", 42), logger);
        WorldRestriction nonList = WorldRestriction.fromConfig("WHITELIST", "world", logger);

        assertTrue(invalidMode.isAllowed("world"));
        assertTrue(malformedList.isAllowed("world"));
        assertTrue(nonList.isAllowed("world"));
    }

    @Test
    void nullConfigurationUsesDefaultBlacklist() {
        WorldRestriction restriction = WorldRestriction.fromConfig(null, null, logger);

        assertTrue(restriction.isAllowed("world"));
    }
}
