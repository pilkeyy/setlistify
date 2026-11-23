package com.setlistify.setlistify.services;

import com.setlistify.setlistify.client.SpotifyClient;
import com.setlistify.setlistify.exception.SpotifyAuthorizationException;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ArtworkImage;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.CandidateAlbum;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ConfirmPlaylistResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.CreateRequest;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.MatchReason;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.MatchStatus;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.NeedsReviewSong;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.PreviewResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ResultResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.SongReview;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.TrackCandidate;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.CreatePlaylistRequest;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.PlaylistResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.AlbumItem;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.ArtistItem;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.TrackItem;
import com.setlistify.setlistify.services.SetlistService.SetlistTracks;
import com.setlistify.setlistify.exception.SpotifyMatchTimeoutException;
import com.setlistify.setlistify.exception.SpotifyRateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Service
public class SpotifyPlaylistService {
    private static final Logger logger = LoggerFactory.getLogger(SpotifyPlaylistService.class);
    /** Searches in flight across all requests; Spotify's rate limit applies to the whole app. */
    static final int MAX_CONCURRENT_SEARCHES = 6;
    /** Candidates offered per song; the confirm step only accepts URIs from this list. */
    static final int MAX_CANDIDATES_PER_SONG = 3;
    private static final Duration MATCH_DEADLINE = Duration.ofSeconds(15);
    private static final Duration DEFAULT_RATE_LIMIT_COOLDOWN = Duration.ofSeconds(5);
    /** Artwork renders at 48px, so the smallest image covering 2x density is enough. */
    private static final int MIN_ARTWORK_PIXELS = 96;
    private static final String TITLE_VARIANT_LABEL = "bonus(?:\\s+track)?|remix|live|acoustic|demo|instrumental"
            + "|remaster(?:ed)?(?:\\s+\\d{4})?|\\d{4}\\s+remaster(?:ed)?"
            + "|(?:feat(?:uring)?|ft)\\.?\\s+[\\p{L}\\p{N}][^()\\[\\]]*";
    private static final Pattern TITLE_VARIANT_SUFFIX = Pattern.compile(
            "(?iu)\\s*(?:[-–—]\\s*(?:%1$s)|\\(\\s*(?:%1$s)\\s*\\)|\\[\\s*(?:%1$s)\\s*\\])\\s*$"
                    .formatted(TITLE_VARIANT_LABEL));

    private final SpotifyClient spotifyClient;
    private final SetlistService setlistService;
    private final Semaphore searchPermits = new Semaphore(MAX_CONCURRENT_SEARCHES);
    private final AtomicLong rateLimitedUntilMillis = new AtomicLong();

    public SpotifyPlaylistService(SpotifyClient spotifyClient, SetlistService setlistService) {
        this.spotifyClient = spotifyClient;
        this.setlistService = setlistService;
    }

    public PreviewResponse previewPlaylist(String setlistId, String accessToken) {
        long started = System.nanoTime();
        SetlistTracks setlist = setlistService.getSetlistTracks(setlistId);
        long setlistNanos = System.nanoTime() - started;
        if (setlist.songs().isEmpty() || normalize(setlist.artistName()).isEmpty()) {
            return null;
        }
        MatchStats stats = new MatchStats();
        try {
            return new PreviewResponse(setlistId, setlist.artistName(),
                    matchSongs(setlist.songs(), setlist.artistName(), accessToken, stats));
        } finally {
            logTimings("preview", setlist.songs().size(), setlistNanos, stats, System.nanoTime() - started);
        }
    }

    public ResultResponse generatePlaylistFromSetlist(CreateRequest request, String accessToken) {
        long started = System.nanoTime();
        List<String> songs = setlistService.getTracksFromSetlist(request.setlistId());
        long setlistNanos = System.nanoTime() - started;
        if (songs == null || songs.isEmpty()) {
            return null;
        }
        MatchStats stats = new MatchStats();
        List<SongReview> reviews;
        try {
            reviews = matchSongs(songs, request.artistName(), accessToken, stats);
        } finally {
            logTimings("generate", songs.size(), setlistNanos, stats, System.nanoTime() - started);
        }

        List<String> trackUris = new ArrayList<>();
        List<NeedsReviewSong> needsReviewSongs = new ArrayList<>();
        List<String> unmatchedSongs = new ArrayList<>();
        for (SongReview review : reviews) {
            switch (review.status()) {
                case MATCHED -> trackUris.add(review.candidates().getFirst().uri());
                case NEEDS_REVIEW -> needsReviewSongs.add(new NeedsReviewSong(review.title(), review.candidates()));
                case UNMATCHED -> unmatchedSongs.add(review.title() == null ? "[missing song name]" : review.title());
            }
        }

        if (trackUris.isEmpty()) {
            return new ResultResponse(
                    null,
                    null,
                    null,
                    false,
                    songs.size(),
                    0,
                    needsReviewSongs,
                    unmatchedSongs
            );
        }

        PlaylistResponse playlist = spotifyClient.createPlaylist(CreatePlaylistRequest.from(request), accessToken);
        addTracks(playlist.id(), trackUris, accessToken);
        return new ResultResponse(
                playlist.id(),
                playlist.name(),
                playlist.getSpotifyUrl(),
                true,
                songs.size(),
                trackUris.size(),
                needsReviewSongs,
                unmatchedSongs
        );
    }

    public ConfirmPlaylistResponse createReviewedPlaylist(String name, String artist, List<String> uris, String accessToken) {
        var request = new CreatePlaylistRequest(name, "Setlist for " + artist + " generated by Setlistify", false);
        PlaylistResponse playlist = spotifyClient.createPlaylist(request, accessToken);
        if (playlist == null || playlist.id() == null || playlist.id().isBlank()) {
            return new ConfirmPlaylistResponse(null, name, null,
                    "Spotify did not confirm playlist creation. Check your Spotify library before trying again.");
        }

        String link = playlist.getSpotifyUrl();
        if (link == null || link.isBlank()) {
            link = "https://open.spotify.com/playlist/" + playlist.id();
        }
        try {
            addTracks(playlist.id(), uris, accessToken);
        } catch (RestClientException | SpotifyAuthorizationException exception) {
            return new ConfirmPlaylistResponse(playlist.id(), name, link,
                    "Spotify created the playlist but did not confirm all tracks were added. Open it to check its contents.");
        }
        return new ConfirmPlaylistResponse(playlist.id(), name, link, null);
    }

    private void addTracks(String playlistId, List<String> trackUris, String accessToken) {
        for (int i = 0; i < trackUris.size(); i += 100) {
            List<String> batch = trackUris.subList(i, Math.min(i + 100, trackUris.size()));
            spotifyClient.addTracksToPlaylist(accessToken, playlistId, batch);
        }
    }

    /**
     * Classifies every song in setlist order. Each distinct title is searched once, concurrently with the
     * others, and repeated titles reuse that result under their own song ID.
     */
    private List<SongReview> matchSongs(List<String> songs, String artistName, String accessToken,
                                        MatchStats stats) {
        long deadline = System.nanoTime() + MATCH_DEADLINE.toNanos();
        String normalizedArtist = normalize(artistName);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Map<String, Future<SongReview>> byTitle = new HashMap<>();
            List<Future<SongReview>> rows = new ArrayList<>(songs.size());
            for (String song : songs) {
                if (normalize(song).isEmpty()) {
                    rows.add(null);
                    continue;
                }
                rows.add(byTitle.computeIfAbsent(song, title -> executor.submit(
                        () -> classifySong(title, normalizedArtist, artistName, accessToken, stats))));
            }
            stats.distinctTitles = byTitle.size();

            List<SongReview> reviews = new ArrayList<>(songs.size());
            for (int i = 0; i < songs.size(); i++) {
                int songId = i + 1;
                Future<SongReview> row = rows.get(i);
                if (row == null) {
                    reviews.add(new SongReview(songId, songs.get(i), MatchStatus.UNMATCHED,
                            MatchReason.MISSING_TITLE, List.of()));
                    continue;
                }
                SongReview review = await(row, deadline);
                reviews.add(new SongReview(songId, review.title(), review.status(), review.reason(),
                        review.candidates()));
            }
            return List.copyOf(reviews);
        } finally {
            // Don't wait for stragglers after a failure; interrupting them frees their search permits.
            executor.shutdownNow();
        }
    }

    private static SongReview await(Future<SongReview> row, long deadlineNanos) {
        try {
            return row.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw new SpotifyMatchTimeoutException();
        } catch (ExecutionException exception) {
            // Rethrow the worker's own exception so ApiExceptionHandler maps it as before.
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (exception.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while matching songs", exception);
        }
    }

    /** Returns the classification for one non-blank title; the caller assigns the song ID. */
    private SongReview classifySong(String song, String normalizedArtist, String artistName, String accessToken,
                                    MatchStats stats) {
        String normalizedTitle = normalize(song);
        String query = String.format("track:%s artist:%s", song, artistName);
        List<TrackItem> candidates = searchCandidates(accessToken, query, stats);
        SongReview review = classifyCandidates(0, song, normalizedTitle, normalizedArtist, candidates);
        if (review.reason() != MatchReason.NO_EXACT_TITLE && review.reason() != MatchReason.ARTIST_MISMATCH) {
            return review;
        }

        stats.fallbacks.incrementAndGet();
        List<TrackItem> combinedCandidates = new ArrayList<>(candidates);
        combinedCandidates.addAll(searchCandidates(accessToken, "track:" + song, stats));
        return classifyCandidates(0, song, normalizedTitle, normalizedArtist, combinedCandidates);
    }

    private List<TrackItem> searchCandidates(String accessToken, String query, MatchStats stats) {
        failFastDuringCooldown();
        try {
            searchPermits.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to search Spotify", exception);
        }
        SearchResponse search;
        long started = System.nanoTime();
        try {
            // Re-check: a search that finished while this one was queued may have been rate limited.
            failFastDuringCooldown();
            stats.searches.incrementAndGet();
            search = spotifyClient.searchTrack(accessToken, query);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                stats.rateLimited.incrementAndGet();
                startCooldown(exception);
            }
            throw exception;
        } finally {
            stats.searchNanos.addAndGet(System.nanoTime() - started);
            searchPermits.release();
        }
        if (search == null || search.tracks() == null || search.tracks().items() == null) {
            throw new IllegalStateException("Spotify track search returned an incomplete response");
        }
        return search.tracks().items();
    }

    private void failFastDuringCooldown() {
        long remainingMillis = rateLimitedUntilMillis.get() - System.currentTimeMillis();
        if (remainingMillis > 0) {
            throw new SpotifyRateLimitedException(Math.max(1, TimeUnit.MILLISECONDS.toSeconds(remainingMillis + 999)));
        }
    }

    private void startCooldown(RestClientResponseException exception) {
        Duration cooldown = DEFAULT_RATE_LIMIT_COOLDOWN;
        String retryAfter = exception.getResponseHeaders() == null
                ? null
                : exception.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null && retryAfter.strip().matches("\\d{1,6}")) {
            cooldown = Duration.ofSeconds(Long.parseLong(retryAfter.strip()));
        }
        long until = System.currentTimeMillis() + cooldown.toMillis();
        rateLimitedUntilMillis.accumulateAndGet(until, Math::max);
    }

    private static void logTimings(String operation, int songCount, long setlistNanos, MatchStats stats,
                                   long totalNanos) {
        logger.info("Spotify {} for {} songs ({} distinct titles): total {} ms, setlist {} ms, "
                        + "{} searches ({} fallbacks, {} rate limited) taking {} ms combined",
                operation, songCount, stats.distinctTitles, TimeUnit.NANOSECONDS.toMillis(totalNanos),
                TimeUnit.NANOSECONDS.toMillis(setlistNanos), stats.searches.get(), stats.fallbacks.get(),
                stats.rateLimited.get(), TimeUnit.NANOSECONDS.toMillis(stats.searchNanos.get()));
    }

    private static final class MatchStats {
        final AtomicInteger searches = new AtomicInteger();
        final AtomicInteger fallbacks = new AtomicInteger();
        final AtomicInteger rateLimited = new AtomicInteger();
        final AtomicLong searchNanos = new AtomicLong();
        volatile int distinctTitles;
    }

    private static SongReview classifyCandidates(int songId, String song, String normalizedTitle,
                                                 String normalizedArtist, List<TrackItem> rawCandidates) {
        List<TrackItem> candidates = uniqueCandidates(rawCandidates);
        List<TrackItem> exactTitleMatches = candidates.stream()
                .filter(track -> normalize(track.name()).equals(normalizedTitle))
                .toList();
        List<TrackItem> exactArtistMatches = exactTitleMatches.stream()
                .filter(track -> hasArtist(track, normalizedArtist))
                .toList();
        if (exactArtistMatches.size() == 1) {
            return new SongReview(songId, song, MatchStatus.MATCHED, MatchReason.UNIQUE_EXACT_MATCH,
                    List.of(toCandidate(exactArtistMatches.getFirst())));
        }
        if (!exactArtistMatches.isEmpty()) {
            return needsReview(songId, song, MatchReason.MULTIPLE_RECORDINGS, exactArtistMatches);
        }

        List<TrackItem> creditedVariants = candidates.stream()
                .filter(track -> hasMatchingBaseTitle(track.name(), normalizedTitle))
                .filter(track -> hasArtist(track, normalizedArtist))
                .toList();
        if (!creditedVariants.isEmpty()) {
            return needsReview(songId, song, MatchReason.TITLE_VARIANT, creditedVariants);
        }
        if (!exactTitleMatches.isEmpty()) {
            return needsReview(songId, song, MatchReason.ARTIST_MISMATCH, exactTitleMatches);
        }
        return new SongReview(songId, song, MatchStatus.UNMATCHED, MatchReason.NO_EXACT_TITLE, List.of());
    }

    private static SongReview needsReview(int songId, String song, MatchReason reason, List<TrackItem> candidates) {
        return new SongReview(songId, song, MatchStatus.NEEDS_REVIEW, reason,
                candidates.stream().limit(MAX_CANDIDATES_PER_SONG).map(SpotifyPlaylistService::toCandidate).toList());
    }

    private static boolean hasMatchingBaseTitle(String trackName, String normalizedTitle) {
        if (trackName == null) {
            return false;
        }
        var suffix = TITLE_VARIANT_SUFFIX.matcher(trackName);
        return suffix.find() && normalize(trackName.substring(0, suffix.start())).equals(normalizedTitle);
    }

    private static List<TrackItem> uniqueCandidates(List<TrackItem> items) {
        Set<String> seenUris = new HashSet<>();
        List<TrackItem> unique = new ArrayList<>();
        for (TrackItem item : items) {
            if (item != null && item.uri() != null && !item.uri().isBlank() && seenUris.add(item.uri())) {
                unique.add(item);
            }
        }
        return unique;
    }

    private static boolean hasArtist(TrackItem track, String normalizedArtist) {
        if (normalizedArtist.isEmpty() || track.artists() == null) {
            return false;
        }
        return track.artists().stream()
                .filter(Objects::nonNull)
                .map(ArtistItem::name)
                .anyMatch(artist -> normalize(artist).equals(normalizedArtist));
    }

    private static TrackCandidate toCandidate(TrackItem track) {
        List<String> artists = track.artists() == null
                ? List.of()
                : track.artists().stream()
                        .filter(Objects::nonNull)
                        .map(ArtistItem::name)
                        .filter(Objects::nonNull)
                        .toList();
        String spotifyUrl = track.externalUrls() == null ? null : track.externalUrls().get("spotify");
        return new TrackCandidate(track.name(), artists, track.uri(), spotifyUrl, toCandidateAlbum(track.album()));
    }

    private static CandidateAlbum toCandidateAlbum(AlbumItem album) {
        if (album == null) {
            return null;
        }
        List<ArtworkImage> available = album.images() == null
                ? List.of()
                : album.images().stream()
                        .filter(image -> image != null && image.url() != null && !image.url().isBlank())
                        .map(image -> new ArtworkImage(image.url(), image.width(), image.height()))
                        .toList();
        List<ArtworkImage> images = available.isEmpty() ? List.of() : List.of(pickArtwork(available));
        String spotifyUrl = album.externalUrls() == null ? null : album.externalUrls().get("spotify");
        return new CandidateAlbum(album.name(), spotifyUrl, images);
    }

    /** Smallest image at least MIN_ARTWORK_PIXELS wide, else the largest sized one, else the first. */
    private static ArtworkImage pickArtwork(List<ArtworkImage> images) {
        Comparator<ArtworkImage> byWidth = Comparator.comparingInt(ArtworkImage::width);
        List<ArtworkImage> sized = images.stream().filter(image -> image.width() != null).toList();
        return sized.stream()
                .filter(image -> image.width() >= MIN_ARTWORK_PIXELS)
                .min(byWidth)
                .or(() -> sized.stream().max(byWidth))
                .orElse(images.getFirst());
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD);
        return decomposed.replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
