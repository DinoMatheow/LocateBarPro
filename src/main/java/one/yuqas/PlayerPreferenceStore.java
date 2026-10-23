package one.yuqas;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PlayerPreferenceStore {

    private static final PlayerPreferences DEFAULT_PREFERENCES =
            new PlayerPreferences(VisibilityMode.TEAM, true, VisibilityMode.TEAM);

    private final ConcurrentMap<UUID, Entry> preferences = new ConcurrentHashMap<>();
    private final PlayerPreferencePersistence persistence;

    public PlayerPreferenceStore() {
        this(null);
    }

    public PlayerPreferenceStore(PlayerPreferencePersistence persistence) {
        this.persistence = persistence;
    }

    public PlayerPreferences get(UUID playerId) {
        Entry entry = preferences.get(Objects.requireNonNull(playerId));
        return entry == null ? DEFAULT_PREFERENCES : entry.preferences();
    }

    public void setTransmissionMode(UUID playerId, VisibilityMode mode) {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(mode);
        update(playerId, existing -> mode == VisibilityMode.OFF
                ? new PlayerPreferences(existing.lastTransmissionMode(), false, existing.reception())
                : new PlayerPreferences(mode, true, existing.reception()));
    }

    public void setTransmissionEnabled(UUID playerId, boolean enabled) {
        Objects.requireNonNull(playerId);
        update(playerId, existing ->
                new PlayerPreferences(existing.lastTransmissionMode(), enabled, existing.reception()));
    }

    public void setReceptionMode(UUID playerId, VisibilityMode mode) {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(mode);
        update(playerId, existing ->
                new PlayerPreferences(existing.lastTransmissionMode(), existing.transmissionEnabled(), mode));
    }

    public void load(UUID playerId) {
        Objects.requireNonNull(playerId);
        if (persistence == null) {
            preferences.putIfAbsent(playerId, new Entry(DEFAULT_PREFERENCES, 0, true, false));
            return;
        }

        PlayerPreferencePersistence.LoadResult loaded = persistence.load(playerId);
        preferences.compute(playerId, (id, current) -> {
            if (current != null && current.dirty()) {
                return new Entry(current.preferences(), current.revision(),
                        current.writable() && loaded.writable(), true);
            }
            return new Entry(loaded.preferences(), current == null ? 0 : current.revision(),
                    loaded.writable(), false);
        });
    }

    public boolean save(UUID playerId) {
        Objects.requireNonNull(playerId);
        Entry snapshot = preferences.get(playerId);
        if (snapshot == null || !snapshot.dirty()) {
            return true;
        }
        if (persistence == null || !snapshot.writable()) {
            return false;
        }
        if (!persistence.save(playerId, snapshot.preferences())) {
            return false;
        }

        preferences.computeIfPresent(playerId, (id, current) -> current.revision() == snapshot.revision()
                ? new Entry(current.preferences(), current.revision(), current.writable(), false)
                : current);
        return true;
    }

    public boolean saveAndUnload(UUID playerId) {
        Objects.requireNonNull(playerId);
        while (true) {
            Entry snapshot = preferences.get(playerId);
            if (snapshot == null) {
                return true;
            }
            if (persistence != null && snapshot.dirty() && snapshot.writable()
                    && !persistence.save(playerId, snapshot.preferences())) {
                return false;
            }

            boolean[] removed = {false};
            preferences.computeIfPresent(playerId, (id, current) -> {
                if (current.revision() == snapshot.revision()) {
                    removed[0] = true;
                    return null;
                }
                return current;
            });
            if (removed[0]) {
                return true;
            }
        }
    }

    public void saveAll() {
        if (persistence == null) {
            return;
        }
        for (UUID playerId : new ArrayList<>(preferences.keySet())) {
            save(playerId);
        }
    }

    public void unloadWithoutSave(UUID playerId) {
        preferences.remove(Objects.requireNonNull(playerId));
    }

    private void update(UUID playerId, java.util.function.UnaryOperator<PlayerPreferences> update) {
        preferences.compute(playerId, (id, current) -> {
            Entry existing = current == null
                    ? new Entry(DEFAULT_PREFERENCES, 0, true, false)
                    : current;
            return new Entry(update.apply(existing.preferences()), existing.revision() + 1,
                    existing.writable(), true);
        });
    }

    private record Entry(
            PlayerPreferences preferences,
            long revision,
            boolean writable,
            boolean dirty
    ) {
    }
}
