package com.setlistify.setlistify.services;

import com.setlistify.setlistify.client.SetlistFmClient;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.ConcertRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.ConcertSummary;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SetlistResponse;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SongRecord;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class SetlistService {
    // The browser loads a setlist's songs before the user previews Spotify matches, often with an OAuth
    // round-trip in between, so cached entries must outlive that gap.
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final int CACHE_MAX_ENTRIES = 200;

    private final SetlistFmClient setlistFmClient;
    private final ConcurrentMap<String, CompletableFuture<SetlistTracks>> inFlightFetches = new ConcurrentHashMap<>();
    private final Map<String, CachedSetlist> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedSetlist> eldest) {
            return size() > CACHE_MAX_ENTRIES;
        }
    };

    public SetlistService(SetlistFmClient setlistFmClient) {
        this.setlistFmClient = setlistFmClient;
    }

    public List<ConcertSummary> searchConcertDirectory(String artistName) {
        SetlistResponse response = setlistFmClient.fetchRawSetlists(artistName);
        if (response == null || response.setlist() == null) {
            return Collections.emptyList();
        }
        return response.setlist().stream()
                       .map(ConcertSummary::from)
                       .toList();
    }

    public List<String> getTracksFromSetlist(String setlistId) {
        return getSetlistTracks(setlistId).songs();
    }

    SetlistTracks getSetlistTracks(String setlistId) {
        long now = System.nanoTime();
        synchronized (cache) {
            CachedSetlist cached = cache.get(setlistId);
            if (cached != null && now - cached.loadedAt() < CACHE_TTL.toNanos()) {
                return cached.tracks();
            }
            cache.remove(setlistId);
        }
        // Single flight: concurrent misses for the same setlist share one setlist.fm request.
        CompletableFuture<SetlistTracks> fetch = new CompletableFuture<>();
        CompletableFuture<SetlistTracks> inFlight = inFlightFetches.putIfAbsent(setlistId, fetch);
        if (inFlight != null) {
            return awaitShared(inFlight);
        }
        try {
            SetlistTracks tracks = fetchSetlistTracks(setlistId);
            if (tracks.artistName() != null && !tracks.songs().isEmpty()) {
                synchronized (cache) {
                    cache.put(setlistId, new CachedSetlist(tracks, now));
                }
            }
            fetch.complete(tracks);
            return tracks;
        } catch (RuntimeException | Error failure) {
            fetch.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlightFetches.remove(setlistId, fetch);
        }
    }

    /** Rethrows the shared fetch's own exception so callers see the same error as the fetching thread. */
    private static SetlistTracks awaitShared(CompletableFuture<SetlistTracks> inFlight) {
        try {
            return inFlight.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (exception.getCause() instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }

    private SetlistTracks fetchSetlistTracks(String setlistId) {
        ConcertRecord concert = setlistFmClient.fetchSingleSetlist(setlistId);
        if (concert == null || concert.sets() == null || concert.sets().set() == null) {
            return new SetlistTracks(null, List.of());
        }
        String artistName = concert.artist() == null ? null : concert.artist().name();
        List<String> songs = concert.sets()
                      .set()
                      .stream()
                      .filter(set -> set.song() != null)
                      .flatMap(set -> set.song().stream())
                      .map(SongRecord::name)
                      .toList();
        return new SetlistTracks(artistName, songs);
    }

    record SetlistTracks(String artistName, List<String> songs) {
    }

    private record CachedSetlist(SetlistTracks tracks, long loadedAt) {
    }
}

