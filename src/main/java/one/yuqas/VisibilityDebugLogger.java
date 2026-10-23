package one.yuqas;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

final class VisibilityDebugLogger {

    private static final long MIN_LOG_INTERVAL_NANOS = 15_000_000_000L;

    enum Decision {
        ALLOWED,
        DENIED_INVALID_OPERATION,
        DENIED_UNSUPPORTED_OPERATION,
        DENIED_NON_PRIMARY_THREAD,
        DENIED_TRANSMITTER_UNAVAILABLE,
        DENIED_PRIVACY_POLICY,
        DENIED_COLOR_DISABLED,
        MARKER_REMOVAL_SENT
    }

    private final Logger logger;
    private final BooleanSupplier enabled;
    private final LongSupplier clock;
    private final Map<Decision, Long> lastLogged = new EnumMap<>(Decision.class);

    VisibilityDebugLogger(Logger logger, BooleanSupplier enabled) {
        this(logger, enabled, System::nanoTime);
    }

    VisibilityDebugLogger(Logger logger, BooleanSupplier enabled, LongSupplier clock) {
        this.logger = Objects.requireNonNull(logger);
        this.enabled = Objects.requireNonNull(enabled);
        this.clock = Objects.requireNonNull(clock);
    }

    synchronized void log(Decision decision) {
        if (!enabled.getAsBoolean()) {
            return;
        }

        long now = clock.getAsLong();
        Long previous = lastLogged.get(decision);
        if (previous != null && now - previous < MIN_LOG_INTERVAL_NANOS) {
            return;
        }

        lastLogged.put(decision, now);
        logger.info("Debug: Locator Bar decision " + decision.name().toLowerCase(java.util.Locale.ROOT) + ".");
    }
}
