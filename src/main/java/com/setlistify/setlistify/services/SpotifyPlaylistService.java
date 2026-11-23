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
import com.setlistify.setlistify.exception.PreviewSupersededException;
import com.setlistify.setlistify.exception.SpotifyMatchTimeoutException;
import com.setlistify.setlistify.exception.SpotifyRateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

@Service
public class SpotifyPlaylistService {
    private static final Logger logger = LoggerFactory.getLogger(SpotifyPlaylistService.class);
    /** Searches in flight across all requests; Spotify's rate limit applies to the whole app. */
    static final int MAX_CONCURRENT_SEARCHES = 6;
    /** Candidates offered per song; the confirm step only accepts URIs from this list. */
    static final int MAX_CANDIDATES_PER_SONG = 3;
    private static final Duration DEFAULT_MATCH_DEADLINE = Duration.ofSeconds(15);
    private static final Duration DEFAULT_RATE_LIMIT_COOLDOWN = Duration.ofSeconds(5);
    /** How often a search waiting for a permit checks whether its preview was superseded. */
    private static final Duration PERMIT_POLL = Duration.ofMillis(100);
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
    private final Duration matchDeadline;

    @Autowired
    public SpotifyPlaylistService(SpotifyClient spotifyClient, SetlistService setlistService) {
        this(spotifyClient, setlistService, DEFAULT_MATCH_DEADLINE);
    }

    SpotifyPlaylistService(SpotifyClient spotifyClient, SetlistService setlistService, Duration matchDeadline) {
        this.spotifyClient = spotifyClient;
        this.setlistService = setlistService;
        this.matchDeadline = matchDeadline;
    }

    public PreviewResponse previewPlaylist(String setlistId, String accessToken) {
        return previewPlaylist(setlistId, accessToken, () -> false);
    }

    /**
     * @param superseded checked before each Spotify search; once true, queued and future searches stop and the
     *                   preview fails with {@link PreviewSupersededException}
     */
    public PreviewResponse previewPlaylist(String setlistId, String accessToken, BooleanSupplier superseded) {
        long started = System.nanoTime();
        SetlistTracks setlist = setlistService.getSetlistTracks(setlistId);
        long setlistNanos = System.nanoTime() - started;
        if (setlist.songs().isEmpty() || normalize(setlist.artistName()).isEmpty()) {
            return null;
        }
        MatchStats stats = new MatchStats(superseded);
        try {
            List<SongReview> songs = matchSongs(setlist.songs(), setlist.artistName(), accessToken, stats);
            return new PreviewResponse(setlistId, setlist.artistName(),
                    songs.stream().map(SpotifyPlaylistService::forPreview).toList());
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
        MatchStats stats = new MatchStats(() -> false);
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
     * others, and repeated titles reuse that result under their own song ID. Results are consumed as workers
     * finish, so the first failure (such as a 429) surfaces even while earlier songs are still in flight.
     */
    private List<SongReview> matchSongs(List<String> songs, String artistName, String accessToken,
                                        MatchStats stats) {
        long deadline = System.nanoTime() + matchDeadline.toNanos();
        String normalizedArtist = normalize(artistName);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CompletionService<SongReview> completions = new ExecutorCompletionService<>(executor);
            Set<String> titles = new HashSet<>();
            for (String song : songs) {
                if (!normalize(song).isEmpty() && titles.add(song)) {
                    completions.submit(() -> classifySong(song, normalizedArtist, artistName, accessToken, stats));
                }
            }
            stats.distinctTitles = titles.size();

            Map<String, SongReview> byTitle = new HashMap<>();
            for (int remaining = titles.size(); remaining > 0; remaining--) {
                SongReview review = nextCompleted(completions, deadline);
                byTitle.put(review.title(), review);
            }

            List<SongReview> reviews = new ArrayList<>(songs.size());
            for (int i = 0; i < songs.size(); i++) {
                int songId = i + 1;
                SongReview review = byTitle.get(songs.get(i));
                if (review == null) {
                    reviews.add(new SongReview(songId, songs.get(i), MatchStatus.UNMATCHED,
                            MatchReason.MISSING_TITLE, List.of()));
                    continue;
                }
                reviews.add(new SongReview(songId, review.title(), review.status(), review.reason(),
                        review.candidates()));
            }
            return List.copyOf(reviews);
        } finally {
            // Don't wait for stragglers after a failure; interrupting them frees their search permits.
            executor.shutdownNow();
        }
    }

    private SongReview nextCompleted(CompletionService<SongReview> completions, long deadlineNanos) {
        Future<SongReview> done;
        try {
            done = completions.poll(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while matching songs", exception);
        }
        if (done == null) {
            throw preferCooldown(new SpotifyMatchTimeoutException());
        }
        try {
            return done.get();
        } catch (ExecutionException exception) {
            // Rethrow the worker's own exception so ApiExceptionHandler maps it as before.
            if (exception.getCause() instanceof RuntimeException cause) {
                throw preferCooldown(cause);
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

    /**
     * While a Spotify cooldown is active, report it instead of other failures, so the client always receives
     * Retry-After. Every 429 starts a cooldown (Retry-After or a default), so a 429 without its own header still
     * reports how long to wait. Authorization failures are kept: the user must reconnect regardless.
     */
    private RuntimeException preferCooldown(RuntimeException failure) {
        if (failure instanceof SpotifyAuthorizationException || failure instanceof PreviewSupersededException) {
            return failure;
        }
        long remainingMillis = rateLimitedUntilMillis.get() - System.currentTimeMillis();
        if (remainingMillis > 0) {
            return new SpotifyRateLimitedException(cooldownSeconds(remainingMillis));
        }
        return failure;
    }

    private static long cooldownSeconds(long remainingMillis) {
        return Math.max(1, TimeUnit.MILLISECONDS.toSeconds(remainingMillis + 999));
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
        acquireSearchPermit(stats);
        SearchResponse search;
        long started = System.nanoTime();
        try {
            // Re-check: while this search was queued, another may have been rate limited or a newer preview
            // may have replaced this one.
            failFastDuringCooldown();
            failIfSuperseded(stats);
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

    /** Waits for a permit, giving up as soon as a newer preview supersedes this one. */
    private void acquireSearchPermit(MatchStats stats) {
        try {
            while (!searchPermits.tryAcquire(PERMIT_POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                failIfSuperseded(stats);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to search Spotify", exception);
        }
    }

    private static void failIfSuperseded(MatchStats stats) {
        if (stats.superseded.getAsBoolean()) {
            throw new PreviewSupersededException();
        }
    }

    private void failFastDuringCooldown() {
        long remainingMillis = rateLimitedUntilMillis.get() - System.currentTimeMillis();
        if (remainingMillis > 0) {
            throw new SpotifyRateLimitedException(cooldownSeconds(remainingMillis));
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
        final BooleanSupplier superseded;
        final AtomicInteger searches = new AtomicInteger();
        final AtomicInteger fallbacks = new AtomicInteger();
        final AtomicInteger rateLimited = new AtomicInteger();
        final AtomicLong searchNanos = new AtomicLong();
        volatile int distinctTitles;

        MatchStats(BooleanSupplier superseded) {
            this.superseded = superseded;
        }
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
                candidates.stream().map(SpotifyPlaylistService::toCandidate).toList());
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
        List<ArtworkImage> images = album.images() == null
                ? List.of()
                : album.images().stream()
                        .filter(image -> image != null && image.url() != null && !image.url().isBlank())
                        .map(image -> new ArtworkImage(image.url(), image.width(), image.height()))
                        .toList();
        String spotifyUrl = album.externalUrls() == null ? null : album.externalUrls().get("spotify");
        return new CandidateAlbum(album.name(), spotifyUrl, images);
    }

    /**
     * Trims a row to what the review UI shows: at most MAX_CANDIDATES_PER_SONG candidates, each with one
     * artwork image. The controller builds the confirm allowlist from these rows, so it matches the UI.
     * /playlists/generate keeps the untrimmed rows.
     */
    private static SongReview forPreview(SongReview review) {
        List<TrackCandidate> candidates = review.candidates().stream()
                .limit(MAX_CANDIDATES_PER_SONG)
                .map(candidate -> new TrackCandidate(candidate.name(), candidate.artists(), candidate.uri(),
                        candidate.spotifyUrl(), withOneArtwork(candidate.album())))
                .toList();
        return new SongReview(review.songId(), review.title(), review.status(), review.reason(), candidates);
    }

    private static CandidateAlbum withOneArtwork(CandidateAlbum album) {
        if (album == null || album.images().size() <= 1) {
            return album;
        }
        return new CandidateAlbum(album.name(), album.spotifyUrl(), List.of(pickArtwork(album.images())));
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
