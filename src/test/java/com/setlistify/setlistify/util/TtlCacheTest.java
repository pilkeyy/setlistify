package com.setlistify.setlistify.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TtlCacheTest {
    private final AtomicLong now = new AtomicLong();

    @Test
    void expiresEntriesAfterTheirTtl() {
        TtlCache<String, String> cache = new TtlCache<>(10, Duration.ofSeconds(60), now::get);
        cache.put("a", "value");

        now.set(Duration.ofSeconds(59).toNanos());
        assertEquals("value", cache.get("a"));
        now.set(Duration.ofSeconds(60).toNanos());
        assertNull(cache.get("a"));
    }

    @Test
    void evictsTheLeastRecentlyUsedEntryWhenFull() {
        TtlCache<String, String> cache = new TtlCache<>(2, Duration.ofHours(1), now::get);
        cache.put("a", "A");
        cache.put("b", "B");
        cache.get("a"); // "b" is now least recently used.
        cache.put("c", "C");

        assertEquals("A", cache.get("a"));
        assertNull(cache.get("b"));
        assertEquals("C", cache.get("c"));
    }
}
