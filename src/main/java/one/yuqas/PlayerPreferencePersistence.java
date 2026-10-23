package one.yuqas;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PlayerPreferencePersistence {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final PlayerPreferences DEFAULT_PREFERENCES =
            new PlayerPreferences(VisibilityMode.TEAM, true, VisibilityMode.TEAM);

    private final Path dataDirectory;
    private final Logger logger;

    public PlayerPreferencePersistence(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.logger = logger;
    }

    public synchronized LoadResult load(UUID playerId) {
        Path file = fileFor(playerId);
        Path legacyFile = legacyFileFor(playerId);
        if (!Files.exists(file) && Files.exists(legacyFile)) {
            try {
                Files.createDirectories(dataDirectory.resolve("players"));
                Files.move(legacyFile, file);
                logger.info("Moved legacy preferences for " + playerId + " into the players directory.");
            } catch (IOException | RuntimeException exception) {
                logger.log(Level.WARNING, "Could not migrate legacy preferences for " + playerId
                        + "; reading the existing file in place.", exception);
                file = legacyFile;
            }
        }
        if (!Files.exists(file)) {
            return new LoadResult(DEFAULT_PREFERENCES, true);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            if (Files.size(file) == 0) {
                return quarantine(file, "is empty");
            }
            yaml.load(file.toFile());
            Object versionValue = yaml.get("schema-version");
            if (!(versionValue instanceof Number number) || number.doubleValue() != number.intValue()
                    || number.intValue() < 1) {
                return quarantine(file, "has an invalid schema version");
            }
            int version = number.intValue();
            if (version > CURRENT_SCHEMA_VERSION) {
                logger.warning("Preferences for " + playerId + " use future schema version " + version
                        + "; using defaults without overwriting that file.");
                return new LoadResult(DEFAULT_PREFERENCES, false);
            }

            Object enabledValue = yaml.get("transmission.enabled");
            if (!(enabledValue instanceof Boolean enabled)) {
                return quarantine(file, "has an invalid transmission.enabled value");
            }

            VisibilityMode lastMode = parseMode(yaml.getString("transmission.last-mode"),
                    VisibilityMode.TEAM, false, playerId, "transmission.last-mode");
            VisibilityMode reception = parseMode(yaml.getString("reception.mode"),
                    VisibilityMode.TEAM, true, playerId, "reception.mode");
            return new LoadResult(new PlayerPreferences(lastMode, enabled, reception), true);
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            logger.log(Level.WARNING, "Could not read preferences for " + playerId
                    + "; using defaults without interrupting the server.", exception);
            return quarantine(file, "could not be parsed");
        }
    }

    public synchronized boolean save(UUID playerId, PlayerPreferences preferences) {
        Path target = fileFor(playerId);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("schema-version", CURRENT_SCHEMA_VERSION);
            yaml.set("transmission.enabled", preferences.transmissionEnabled());
            yaml.set("transmission.last-mode", preferences.lastTransmissionMode().name());
            yaml.set("reception.mode", preferences.reception().name());
            yaml.save(temporary.toFile());

            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "Could not save preferences for " + playerId
                    + "; the in-memory values remain available.", exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException cleanupException) {
                logger.log(Level.WARNING, "Could not remove temporary preference file " + temporary,
                        cleanupException);
            }
            return false;
        }
    }

    private VisibilityMode parseMode(
            String value,
            VisibilityMode fallback,
            boolean allowOff,
            UUID playerId,
            String field
    ) {
        String trimmedValue = value == null ? null : value.trim();
        if (trimmedValue != null) {
            try {
                VisibilityMode mode = VisibilityMode.valueOf(trimmedValue.toUpperCase(Locale.ROOT));
                if (allowOff || mode != VisibilityMode.OFF) {
                    return mode;
                }
            } catch (IllegalArgumentException ignored) {
                // Unknown modes fall back to the documented field default.
            }
        }
        logger.warning("Unknown or missing " + field + " in preferences for " + playerId
                + "; using " + fallback + ".");
        return fallback;
    }

    private LoadResult quarantine(Path file, String reason) {
        logger.warning("Preference file " + file.getFileName() + " " + reason + "; moving it aside and using defaults.");
        try {
            Path corrupt = nextCorruptPath(file);
            Files.move(file, corrupt);
            return new LoadResult(DEFAULT_PREFERENCES, true);
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "Could not quarantine preference file " + file
                    + "; it will not be overwritten.", exception);
            return new LoadResult(DEFAULT_PREFERENCES, false);
        }
    }

    private Path nextCorruptPath(Path file) {
        Path candidate = file.resolveSibling(file.getFileName() + ".corrupt");
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = file.resolveSibling(file.getFileName() + ".corrupt." + suffix++);
        }
        return candidate;
    }

    private Path fileFor(UUID playerId) {
        String fileName = playerId.toString() + ".yml";
        Path playersDirectory = dataDirectory.resolve("players").normalize();
        Path file = playersDirectory.resolve(fileName).normalize();
        UUID parsed = UUID.fromString(file.getFileName().toString().replaceFirst("\\.yml$", ""));
        if (!parsed.equals(playerId) || !file.getParent().equals(playersDirectory)) {
            throw new IllegalArgumentException("Invalid preference file UUID.");
        }
        return file;
    }

    private Path legacyFileFor(UUID playerId) {
        Path file = dataDirectory.resolve(playerId + ".yml").normalize();
        if (!file.getParent().equals(dataDirectory)) {
            throw new IllegalArgumentException("Invalid legacy preference file UUID.");
        }
        return file;
    }

    public record LoadResult(PlayerPreferences preferences, boolean writable) {
    }
}
