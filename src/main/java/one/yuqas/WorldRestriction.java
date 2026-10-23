package one.yuqas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

public final class WorldRestriction {

    private static final WorldRestriction DEFAULT = new WorldRestriction(Mode.BLACKLIST, List.of());

    private final Mode mode;
    private final List<String> worlds;

    private WorldRestriction(Mode mode, List<String> worlds) {
        this.mode = mode;
        this.worlds = List.copyOf(worlds);
    }

    public static WorldRestriction defaults() {
        return DEFAULT;
    }

    public static WorldRestriction fromConfig(Object modeValue, Object listValue, Logger logger) {
        Mode mode = parseMode(modeValue);
        List<String> worlds = parseWorlds(listValue);
        if (mode == null || worlds == null) {
            logger.warning("Invalid worlds restriction configuration; using defaults (BLACKLIST with an empty list).");
            return DEFAULT;
        }
        return new WorldRestriction(mode, worlds);
    }

    public boolean isAllowed(String worldName) {
        boolean listed = worlds.stream().anyMatch(world -> world.equalsIgnoreCase(worldName));
        return mode == Mode.BLACKLIST ? !listed : listed;
    }

    public boolean isRestricted(String worldName) {
        return !isAllowed(worldName);
    }

    private static Mode parseMode(Object value) {
        if (!(value instanceof String mode)) {
            return value == null ? Mode.BLACKLIST : null;
        }
        try {
            return Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static List<String> parseWorlds(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            return null;
        }

        List<String> worlds = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof String worldName) || worldName.isBlank()) {
                return null;
            }
            worlds.add(worldName);
        }
        return Collections.unmodifiableList(worlds);
    }

    private enum Mode {
        BLACKLIST,
        WHITELIST
    }
}
