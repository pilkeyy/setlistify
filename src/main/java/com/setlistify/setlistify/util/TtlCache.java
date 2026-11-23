package com.setlistify.setlistify.util;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Small thread-safe cache: least-recently-used eviction beyond {@code maxEntries}, and entries expire after
 * {@code ttl}. Enough for one instance; a shared store (Redis) would replace it if the app ran on several.
 */
public final class TtlCache<K, V> {
    private final Duration ttl;
    private final LongSupplier nanoClock;
    private final Map<K, Entry<V>> entries;

    public TtlCache(int maxEntries, Duration ttl) {
        this(maxEntries, ttl, System::nanoTime);
    }

    TtlCache(int maxEntries, Duration ttl, LongSupplier nanoClock) {
        this.ttl = ttl;
        this.nanoClock = nanoClock;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** Returns the cached value, or null if absent or expired. */
    public synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (nanoClock.getAsLong() - entry.storedAt() >= ttl.toNanos()) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    public synchronized void put(K key, V value) {
        entries.put(key, new Entry<>(value, nanoClock.getAsLong()));
    }

    private record Entry<V>(V value, long storedAt) {
    }
}
