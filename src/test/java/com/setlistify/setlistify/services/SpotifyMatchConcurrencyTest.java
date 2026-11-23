package com.setlistify.setlistify.services;

import com.setlistify.setlistify.client.SpotifyClient;
import com.setlistify.setlistify.exception.PreviewSupersededException;
import com.setlistify.setlistify.exception.SpotifyAuthorizationException;
import com.setlistify.setlistify.exception.SpotifyRateLimitedException;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.CreateRequest;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ResultResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.SongReview;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.TrackCandidate;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.AlbumItem;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.ImageItem;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.ArtistItem;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.TrackItem;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.TracksWrapper;
import com.setlistify.setlistify.services.SetlistService.SetlistTracks;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpotifyMatchConcurrencyTest {
    private final SpotifyClient spotify = mock(SpotifyClient.class);
    private final SetlistService setlists = mock(SetlistService.class);
    private final SpotifyPlaylistService service = new SpotifyPlaylistService(spotify, setlists);

    @Test
    void keepsSetlistOrderAndCapsSearchesInFlight() {
        List<String> songs = IntStream.rangeClosed(1, 20).mapToObj(i -> "Song " + i).toList();
        when(setlists.getSetlistTracks("concert")).thenReturn(new SetlistTracks("Band", songs));
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        when(spotify.searchTrack(eq("token"), anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(1);
            int number = Integer.parseInt(query.replaceAll("\\D+", " ").trim().split(" ")[0]);
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            Thread.sleep(5L * (21 - number)); // Later songs finish first.
            inFlight.decrementAndGet();
            return found("Song " + number);
        });

        List<SongReview> rows = service.previewPlaylist("concert", "token").songs();

        assertEquals(songs, rows.stream().map(SongReview::title).toList());
        assertEquals(IntStream.rangeClosed(1, 20).boxed().toList(), rows.stream().map(SongReview::songId).toList());
        assertTrue(peak.get() > 1, "searches should overlap");
        assertTrue(peak.get() <= SpotifyPlaylistService.MAX_CONCURRENT_SEARCHES, "peak was " + peak.get());
    }

    @Test
    void propagatesRateLimitFromWorkerAndFailsFastDuringCooldown() {
        when(setlists.getSetlistTracks("concert")).thenReturn(new SetlistTracks("Band", List.of("Song")));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "30");
        HttpClientErrorException tooMany = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers, new byte[0], null);
        when(spotify.searchTrack(eq("token"), anyString())).thenThrow(tooMany);

        SpotifyRateLimitedException first = assertThrows(SpotifyRateLimitedException.class,
                () -> service.previewPlaylist("concert", "token"));
        assertEquals(30, first.retryAfterSeconds());

        SpotifyRateLimitedException cooldown = assertThrows(SpotifyRateLimitedException.class,
                () -> service.previewPlaylist("concert", "token"));
        assertTrue(cooldown.retryAfterSeconds() > 0 && cooldown.retryAfterSeconds() <= 30);
        verify(spotify, times(1)).searchTrack(eq("token"), anyString());
    }

    @Test
    void reportsLaterSongsRateLimitInsteadOfEarlierSongsSlowFailure() {
        when(setlists.getSetlistTracks("concert")).thenReturn(new SetlistTracks("Band", List.of("Slow", "Limited")));
        when(spotify.searchTrack(eq("token"), contains("Slow"))).thenAnswer(invocation -> {
            Thread.sleep(300);
            throw new ResourceAccessException("Read timed out");
        });
        when(spotify.searchTrack(eq("token"), contains("Limited"))).thenThrow(tooManyRequests("30"));

        SpotifyRateLimitedException thrown = assertThrows(SpotifyRateLimitedException.class,
                () -> service.previewPlaylist("concert", "token"));

        assertEquals(30, thrown.retryAfterSeconds());
    }

    @Test
    void reportsActiveCooldownWhenTheDeadlinePasses() {
        SpotifyPlaylistService shortDeadline = new SpotifyPlaylistService(spotify, setlists, Duration.ofMillis(200));
        when(setlists.getSetlistTracks("slow")).thenReturn(new SetlistTracks("Band", List.of("Slow")));
        when(setlists.getSetlistTracks("limited")).thenReturn(new SetlistTracks("Band", List.of("Limited")));
        when(spotify.searchTrack(eq("token"), contains("Limited"))).thenThrow(tooManyRequests("30"));
        when(spotify.searchTrack(eq("token"), contains("Slow"))).thenAnswer(invocation -> {
            // Another preview is rate limited while this search is still in flight.
            assertThrows(SpotifyRateLimitedException.class, () -> shortDeadline.previewPlaylist("limited", "token"));
            Thread.sleep(1_000);
            return found("Slow");
        });

        SpotifyRateLimitedException thrown = assertThrows(SpotifyRateLimitedException.class,
                () -> shortDeadline.previewPlaylist("slow", "token"));

        assertTrue(thrown.retryAfterSeconds() > 0 && thrown.retryAfterSeconds() <= 30);
    }

    @Test
    void capsCandidatesAndArtworkOnlyInThePreview() {
        when(setlists.getSetlistTracks("concert")).thenReturn(new SetlistTracks("Band", List.of("Song")));
        when(setlists.getTracksFromSetlist("concert")).thenReturn(List.of("Song"));
        AlbumItem album = new AlbumItem("Album", List.of(new ImageItem("https://i.scdn.co/640", 640, 640),
                new ImageItem("https://i.scdn.co/300", 300, 300), new ImageItem("https://i.scdn.co/64", 64, 64)), null);
        List<TrackItem> recordings = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> new TrackItem("id" + i, "Song", "spotify:track:" + i,
                        List.of(new ArtistItem("Band")), album, null))
                .toList();
        when(spotify.searchTrack(eq("token"), anyString()))
                .thenReturn(new SearchResponse(new TracksWrapper(recordings)));

        SongReview previewRow = service.previewPlaylist("concert", "token").songs().getFirst();
        ResultResponse generated = service.generatePlaylistFromSetlist(
                new CreateRequest("concert", "Band", "Playlist", false), "token");

        assertEquals(SpotifyPlaylistService.MAX_CANDIDATES_PER_SONG, previewRow.candidates().size());
        assertEquals(List.of("https://i.scdn.co/300"),
                previewRow.candidates().getFirst().album().images().stream().map(image -> image.url()).toList());
        TrackCandidate generatedFirst = generated.needsReviewSongs().getFirst().candidates().getFirst();
        assertEquals(5, generated.needsReviewSongs().getFirst().candidates().size());
        assertEquals(3, generatedFirst.album().images().size());
    }

    @Test
    void reportsDefaultCooldownWhenA429HasNoRetryAfter() {
        when(setlists.getSetlistTracks("concert")).thenReturn(new SetlistTracks("Band", List.of("Song")));
        when(spotify.searchTrack(eq("token"), anyString())).thenThrow(tooManyRequests(null));

        SpotifyRateLimitedException thrown = assertThrows(SpotifyRateLimitedException.class,
                () -> service.previewPlaylist("concert", "token"));

        assertTrue(thrown.retryAfterSeconds() > 0 && thrown.retryAfterSeconds() <= 5, "was " + thrown.retryAfterSeconds());
    }

    @Test
    void keepsAuthorizationFailureDuringCooldown() {
        when(setlists.getSetlistTracks("slow")).thenReturn(new SetlistTracks("Band", List.of("Slow")));
        when(setlists.getSetlistTracks("limited")).thenReturn(new SetlistTracks("Band", List.of("Limited")));
        when(spotify.searchTrack(eq("token"), contains("Limited"))).thenThrow(tooManyRequests("30"));
        when(spotify.searchTrack(eq("token"), contains("Slow"))).thenAnswer(invocation -> {
            // Another preview starts a cooldown, then this in-flight search is rejected as unauthorized.
            assertThrows(SpotifyRateLimitedException.class, () -> service.previewPlaylist("limited", "token"));
            throw new SpotifyAuthorizationException(HttpStatus.UNAUTHORIZED, null);
        });

        SpotifyAuthorizationException thrown = assertThrows(SpotifyAuthorizationException.class,
                () -> service.previewPlaylist("slow", "token"));

        assertEquals(HttpStatus.UNAUTHORIZED, thrown.status());
    }

    @Test
    void stopsQueuedSearchesOnceSuperseded() {
        List<String> songs = IntStream.rangeClosed(1, 30).mapToObj(i -> "Song " + i).toList();
        when(setlists.getSetlistTracks("concert")).thenReturn(new SetlistTracks("Band", songs));
        AtomicBoolean superseded = new AtomicBoolean();
        when(spotify.searchTrack(eq("token"), anyString())).thenAnswer(invocation -> {
            superseded.set(true); // The user started a newer preview while the first searches were running.
            Thread.sleep(300);
            return found("Song");
        });

        assertThrows(PreviewSupersededException.class,
                () -> service.previewPlaylist("concert", "token", superseded::get));

        verify(spotify, atMost(SpotifyPlaylistService.MAX_CONCURRENT_SEARCHES)).searchTrack(eq("token"), anyString());
    }

    @Test
    void rematchingAConcertReusesCachedSearches() {
        when(setlists.getSetlistTracks("concert")).thenReturn(new SetlistTracks("Band", List.of("Song 1", "Song 2")));
        when(spotify.searchTrack(eq("token"), anyString())).thenAnswer(invocation ->
                found(((String) invocation.getArgument(1)).contains("Song 1") ? "Song 1" : "Song 2"));

        List<SongReview> first = service.previewPlaylist("concert", "token").songs();
        List<SongReview> second = service.previewPlaylist("concert", "token").songs();

        assertEquals(first, second);
        verify(spotify, times(2)).searchTrack(eq("token"), anyString());
    }

    @Test
    void cachedConcertStillPreviewsDuringCooldown() {
        when(setlists.getSetlistTracks("cached")).thenReturn(new SetlistTracks("Band", List.of("Song 1")));
        when(setlists.getSetlistTracks("limited")).thenReturn(new SetlistTracks("Band", List.of("Limited")));
        when(spotify.searchTrack(eq("token"), contains("Song 1"))).thenReturn(found("Song 1"));
        when(spotify.searchTrack(eq("token"), contains("Limited"))).thenThrow(tooManyRequests("30"));
        service.previewPlaylist("cached", "token");
        assertThrows(SpotifyRateLimitedException.class, () -> service.previewPlaylist("limited", "token"));

        SongReview row = service.previewPlaylist("cached", "token").songs().getFirst();

        assertEquals("Song 1", row.title());
        verify(spotify, times(1)).searchTrack(eq("token"), contains("Song 1"));
    }

    private static HttpClientErrorException tooManyRequests(String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers, new byte[0], null);
    }

    private static SearchResponse found(String title) {
        TrackItem track = new TrackItem(title, title, "spotify:track:" + title.replace(' ', '-'),
                List.of(new ArtistItem("Band")), null, null);
        return new SearchResponse(new TracksWrapper(List.of(track)));
    }
}
