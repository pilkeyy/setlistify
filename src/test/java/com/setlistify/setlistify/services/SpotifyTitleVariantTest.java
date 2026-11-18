package com.setlistify.setlistify.services;

import com.setlistify.setlistify.client.SpotifyClient;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.MatchReason;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.MatchStatus;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.SongReview;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.ArtistItem;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.TrackItem;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.TracksWrapper;
import com.setlistify.setlistify.services.SetlistService.SetlistTracks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SpotifyTitleVariantTest {
    private static final String ARTIST_QUERY = "track:Logo artist:OsamaSon";
    private static final String TITLE_QUERY = "track:Logo";

    private final SpotifyClient spotify = mock(SpotifyClient.class);
    private final SetlistService setlists = mock(SetlistService.class);
    private final SpotifyPlaylistService service = new SpotifyPlaylistService(spotify, setlists);

    @ParameterizedTest
    @CsvSource(value = {
            "Logo (Bonus)|true",
            "Logo [Bonus Track]|false",
            "Logo - Live|false",
            "Logo - 2025 Remaster|false"
    }, delimiter = '|')
    void offersCreditedVersionForReviewWithItsFullTitle(String spotifyTitle, boolean foundByArtistSearch) {
        SearchResponse candidate = searchResult(spotifyTitle, "OsamaSon");
        when(spotify.searchTrack("token", ARTIST_QUERY)).thenReturn(foundByArtistSearch ? candidate : emptySearch());
        if (!foundByArtistSearch) {
            when(spotify.searchTrack("token", TITLE_QUERY)).thenReturn(candidate);
        }

        SongReview row = previewLogo();

        assertEquals(MatchStatus.NEEDS_REVIEW, row.status());
        assertEquals(MatchReason.TITLE_VARIANT, row.reason());
        assertEquals(List.of(spotifyTitle), row.candidates().stream().map(track -> track.name()).toList());
        verify(spotify).searchTrack("token", ARTIST_QUERY);
        if (!foundByArtistSearch) {
            verify(spotify).searchTrack("token", TITLE_QUERY);
        }
        verifyNoMoreInteractions(spotify);
    }

    @Test
    void leavesUnrecognizedSubtitleAndUncreditedBonusUnmatched() {
        when(spotify.searchTrack("token", ARTIST_QUERY))
                .thenReturn(searchResult("Logo (Of My Heart)", "OsamaSon"));
        when(spotify.searchTrack("token", TITLE_QUERY))
                .thenReturn(searchResult("Logo (Bonus)", "Other Artist"));

        SongReview row = previewLogo();

        assertEquals(MatchStatus.UNMATCHED, row.status());
        assertTrue(row.candidates().isEmpty());
    }

    private SongReview previewLogo() {
        when(setlists.getSetlistTracks("concert")).thenReturn(new SetlistTracks("OsamaSon", List.of("Logo")));
        return service.previewPlaylist("concert", "token").songs().getFirst();
    }

    private static SearchResponse searchResult(String title, String artist) {
        TrackItem track = new TrackItem("logo", title, "spotify:track:logo", List.of(new ArtistItem(artist)), null, null);
        return new SearchResponse(new TracksWrapper(List.of(track)));
    }

    private static SearchResponse emptySearch() {
        return new SearchResponse(new TracksWrapper(List.of()));
    }
}
