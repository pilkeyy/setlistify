package com.setlistify.setlistify.services;

import com.setlistify.setlistify.client.SpotifyClient;
import com.setlistify.setlistify.exception.SpotifyRateLimitedException;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.SongReview;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.ArtistItem;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.TrackItem;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.TracksWrapper;
import com.setlistify.setlistify.services.SetlistService.SetlistTracks;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

        HttpClientErrorException thrown = assertThrows(HttpClientErrorException.class,
                () -> service.previewPlaylist("concert", "token"));
        assertEquals("30", thrown.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER));

        SpotifyRateLimitedException cooldown = assertThrows(SpotifyRateLimitedException.class,
                () -> service.previewPlaylist("concert", "token"));
        assertTrue(cooldown.retryAfterSeconds() > 0 && cooldown.retryAfterSeconds() <= 30);
        verify(spotify, times(1)).searchTrack(eq("token"), anyString());
    }

    private static SearchResponse found(String title) {
        TrackItem track = new TrackItem(title, title, "spotify:track:" + title.replace(' ', '-'),
                List.of(new ArtistItem("Band")), null, null);
        return new SearchResponse(new TracksWrapper(List.of(track)));
    }
}
