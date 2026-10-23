package one.yuqas;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VisibilityDebugLoggerTest {

    @Test
    void debugIsDisabledByDefaultAndRateLimitedWithoutPlayerIdentifiers() {
        Logger logger = Logger.getLogger("visibility-debug-test");
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);

        AtomicBoolean enabled = new AtomicBoolean(false);
        AtomicLong now = new AtomicLong(1);
        VisibilityDebugLogger debugLogger =
                new VisibilityDebugLogger(logger, enabled::get, now::get);

        debugLogger.log(VisibilityDebugLogger.Decision.ALLOWED);
        assertEquals(0, messages.size());

        enabled.set(true);
        debugLogger.log(VisibilityDebugLogger.Decision.ALLOWED);
        debugLogger.log(VisibilityDebugLogger.Decision.ALLOWED);
        assertEquals(1, messages.size());
        assertEquals("Debug: Locator Bar decision allowed.", messages.getFirst());

        now.addAndGet(15_000_000_000L);
        debugLogger.log(VisibilityDebugLogger.Decision.ALLOWED);
        assertEquals(2, messages.size());

        logger.removeHandler(handler);
    }
}
