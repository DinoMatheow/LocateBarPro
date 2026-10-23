package one.yuqas;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

final class TeamStatePersistence {

    static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Pattern INTERNAL_NAME = Pattern.compile("tlb_[A-Za-z0-9_-]{1,12}");

    private final Path file;
    private final Logger logger;

    TeamStatePersistence(Path dataDirectory, Logger logger) {
        this.file = dataDirectory.toAbsolutePath().normalize().resolve("teams.yml");
        this.logger = logger;
    }

    synchronized LoadResult load() {
        if (!Files.exists(file)) {
            return new LoadResult(Map.of(), true);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            if (Files.size(file) == 0) {
                return quarantine("is empty");
            }
            yaml.load(file.toFile());
            Object versionValue = yaml.get("schema-version");
            if (!(versionValue instanceof Number version)
                    || version.doubleValue() != version.intValue()
                    || version.intValue() < 1) {
                return quarantine("has an invalid schema version");
            }
            if (version.intValue() > CURRENT_SCHEMA_VERSION) {
                logger.warning("Team state uses future schema version " + version.intValue()
                        + "; saved leader data will not be loaded or overwritten.");
                return new LoadResult(Map.of(), false);
            }

            ConfigurationSection entries = yaml.getConfigurationSection("teams");
            if (entries == null) {
                if (!yaml.contains("teams")) {
                    return new LoadResult(Map.of(), true);
                }
                return quarantine("has an invalid teams section");
            }

            Map<String, SavedTeam> savedTeams = new LinkedHashMap<>();
            for (String internalName : entries.getKeys(false)) {
                ConfigurationSection values = entries.getConfigurationSection(internalName);
                if (!INTERNAL_NAME.matcher(internalName).matches() || values == null) {
                    return quarantine("contains an invalid team entry");
                }
                Object leaderUuidValue = values.get("leader-uuid");
                Object leaderNameValue = values.get("leader-name");
                if (!(leaderUuidValue instanceof String leaderUuid)
                        || !(leaderNameValue instanceof String leaderName)) {
                    return quarantine("contains invalid leader data");
                }
                if (leaderUuid.isEmpty()) {
                    savedTeams.put(internalName,
                            new SavedTeam(null, leaderName.isBlank() ? null : leaderName));
                    continue;
                }
                if (leaderName.isBlank()) {
                    return quarantine("contains invalid leader data");
                }
                try {
                    savedTeams.put(internalName, new SavedTeam(UUID.fromString(leaderUuid), leaderName));
                } catch (IllegalArgumentException exception) {
                    return quarantine("contains an invalid leader UUID");
                }
            }
            return new LoadResult(Map.copyOf(savedTeams), true);
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            logger.log(Level.WARNING, "Could not read team state; using no saved leaders.", exception);
            return quarantine("could not be parsed");
        }
    }

    synchronized boolean save(Map<String, SavedTeam> teams) {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("schema-version", CURRENT_SCHEMA_VERSION);
            for (Map.Entry<String, SavedTeam> entry : teams.entrySet()) {
                String base = "teams." + entry.getKey();
                SavedTeam team = entry.getValue();
                yaml.set(base + ".leader-uuid", team.leaderId() == null ? "" : team.leaderId().toString());
                yaml.set(base + ".leader-name", team.leaderName() == null ? "" : team.leaderName());
            }
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "Could not save team leadership to " + file
                    + "; the in-memory team state remains available.", exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException cleanupException) {
                logger.log(Level.WARNING, "Could not remove temporary team state file " + temporary,
                        cleanupException);
            }
            return false;
        }
    }

    private LoadResult quarantine(String reason) {
        logger.warning("Team state file " + file.getFileName() + " " + reason + "; moving it aside.");
        try {
            Path corrupt = nextCorruptPath();
            Files.move(file, corrupt);
            return new LoadResult(Map.of(), true);
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "Could not quarantine team state file " + file
                    + "; it will not be overwritten.", exception);
            return new LoadResult(Map.of(), false);
        }
    }

    private Path nextCorruptPath() {
        Path candidate = file.resolveSibling(file.getFileName() + ".corrupt");
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = file.resolveSibling(file.getFileName() + ".corrupt." + suffix++);
        }
        return candidate;
    }

    record SavedTeam(UUID leaderId, String leaderName) {
    }

    record LoadResult(Map<String, SavedTeam> teams, boolean writable) {
    }
}
