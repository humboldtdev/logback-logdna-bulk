package uk.co.humboldt.logging;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Manage LogDNA metadata via methods similar to MDC, but
 * allowing typed objects rather than just strings. This allows
 * numbers to be recognised and graphed by LogDNA, and also
 * allows nested objects to be serialized into metadata.
 *
 * These are not controlled via the sendMDC setting on the appender,
 * which is intended to suppress MDC from libraries already using it.
 */
@SuppressWarnings("WeakerAccess")
public class LogDNAMeta {

    private static final ThreadLocal<Map<String,Object>> meta =
            new ThreadLocal<>();

    /**
     * Access current metadata.
     */
    public static Map<String,Object> getAll() {
        Map<String, Object> all = meta.get();
        if (all == null)
            return Collections.emptyMap();
        else
            return all;
    }

    /**
     * Place a value in LogDNA metadata.
     */
    public static void put(String key, Object value) {
        Map<String, Object> all = meta.get();
        if (all == null) {
            all = new HashMap<>();
            meta.set(all);
        }

        all.put(key, value);
    }

    /**
     * Place a value in LogDNA metadata, with a Closeable
     * handle to remove it.
     */
    public static CloseableMeta putCloseable(
            String key, Object value) {
        Map<String, Object> all = meta.get();
        if (all == null) {
            all = new HashMap<>();
            meta.set(all);
        }

        all.put(key, value);
        return new CloseableMeta(key);
    }

    /**
     * Remove a key from LogDNA metadata.
     */
    public static void remove(String key) {
        Map<String, Object> all = meta.get();
        if (all != null)
            all.remove(key);
    }

    /**
     * Clear all values from metadata. This will not remove
     * any values inserted by MDC if sendMDC is set true on
     * the logger.
     */
    public static void clear() {
        meta.remove();
    }

    public static class CloseableMeta implements Closeable {

        private final String key;

        CloseableMeta(String key) {
            this.key = key;
        }

        @Override
        public void close() {
            remove(key);
        }
    }

}
