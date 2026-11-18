package com.setlistify.setlistify.client;

import com.setlistify.setlistify.config.SpotifyOauthConfig;
import com.setlistify.setlistify.controller.PlaylistController;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.ArtistRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.ConcertRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SetRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SetsWrapper;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SongRecord;
import com.setlistify.setlistify.services.SetlistService;
import com.setlistify.setlistify.services.SpotifyPlaylistService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlaylistArtworkIntegrationTest {
    @Test
    void returnsAlbumArtworkAndKeepsCandidatesWithoutArtworkReviewable() throws Exception {
        RestClient.Builder apiBuilder = RestClient.builder();
        MockRestServiceServer spotify = MockRestServiceServer.bindTo(apiBuilder).build();
        spotify.expect(requestTo(containsString("/search")))
                .andRespond(withSuccess("""
                        {
                          "tracks": {
                            "items": [
                              {
                                "name": "Song",
                                "uri": "spotify:track:first",
                                "artists": [{"name": "Band"}],
                                "external_urls": {"spotify": "https://open.spotify.com/track/first"},
                                "album": {
                                  "name": "First Release",
                                  "external_urls": {"spotify": "https://open.spotify.com/album/first"},
                                  "images": [
                                    {"url": "https://i.scdn.co/image/first", "width": 300, "height": 300},
                                    {"url": "", "width": 64, "height": 64}
                                  ]
                                }
                              },
                              {
                                "name": "Song",
                                "uri": "spotify:track:second",
                                "artists": [{"name": "Band"}],
                                "album": {"name": "Second Release", "images": []}
                              },
                              {
                                "name": "Song",
                                "uri": "spotify:track:third",
                                "artists": [{"name": "Band"}]
                              }
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        SpotifyOauthConfig config = new SpotifyOauthConfig("test-client", "http://127.0.0.1/callback", "");
        SpotifyClient client = new SpotifyClient(config, RestClient.builder(), apiBuilder);
        SetlistService setlists = mock(SetlistService.class);
        when(setlists.getTracksFromSetlist("concert")).thenReturn(List.of("Song"));
        PlaylistController controller = new PlaylistController(new SpotifyPlaylistService(client, setlists));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("access_token", "test-token");

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/api/v1/playlists/generate")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"setlistId":"concert","artistName":"Band","playlistName":"Concert"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.playlistCreated").value(false))
                .andExpect(jsonPath("$.needsReviewSongs[0].candidates[0].spotifyUrl")
                        .value("https://open.spotify.com/track/first"))
                .andExpect(jsonPath("$.needsReviewSongs[0].candidates[0].album.name").value("First Release"))
                .andExpect(jsonPath("$.needsReviewSongs[0].candidates[0].album.spotifyUrl")
                        .value("https://open.spotify.com/album/first"))
                .andExpect(jsonPath("$.needsReviewSongs[0].candidates[0].album.images.length()").value(1))
                .andExpect(jsonPath("$.needsReviewSongs[0].candidates[0].album.images[0].url")
                        .value("https://i.scdn.co/image/first"))
                .andExpect(jsonPath("$.needsReviewSongs[0].candidates[0].album.images[0].width").value(300))
                .andExpect(jsonPath("$.needsReviewSongs[0].candidates[0].album.images[0].height").value(300))
                .andExpect(jsonPath("$.needsReviewSongs[0].candidates[1].album.images").isEmpty())
                .andExpect(jsonPath("$.needsReviewSongs[0].candidates[2].uri").value("spotify:track:third"))
                .andExpect(jsonPath("$.needsReviewSongs[0].candidates[2].album").doesNotHaveJsonPath());
        spotify.verify();
    }

    @Test
    void previewsOrderedSongOccurrencesWithReadOnlySpotifyRequests() throws Exception {
        RestClient.Builder apiBuilder = RestClient.builder();
        MockRestServiceServer spotify = MockRestServiceServer.bindTo(apiBuilder).build();
        String openingTrack = """
                {"tracks":{"items":[{
                  "name":"Opening + {Live}",
                  "uri":"spotify:track:opening",
                  "artists":[{"name":"Bánd"}],
                  "album":{"name":"Concert Album","images":[{"url":"https://i.scdn.co/image/opening"}]}
                }]}}
                """;
        expectSearch(spotify, "Opening%20%2B%20%7BLive%7D", openingTrack);
        expectSearch(spotify, "Cover", """
                {"tracks":{"items":[{
                  "name":"Cover","uri":"spotify:track:cover","artists":[{"name":"Other Artist"}]
                }]}}
                """);
        expectSearch(spotify, "Lost", """
                {"tracks":{"items":[]}}
                """);
        expectSearch(spotify, "Opening%20%2B%20%7BLive%7D", openingTrack);

        SetlistFmClient setlistFm = mock(SetlistFmClient.class);
        when(setlistFm.fetchSingleSetlist("concert")).thenReturn(new ConcertRecord(
                "concert", null, new ArtistRecord("Bánd"), null, new SetsWrapper(List.of(
                        new SetRecord(List.of(new SongRecord("Opening + {Live}"), new SongRecord("Cover"),
                                new SongRecord("Lost"))),
                        new SetRecord(List.of(new SongRecord("Opening + {Live}"), new SongRecord(null)))
                ))
        ));
        SpotifyOauthConfig config = new SpotifyOauthConfig("test-client", "http://127.0.0.1/callback", "");
        SpotifyClient client = new SpotifyClient(config, RestClient.builder(), apiBuilder);
        PlaylistController controller = new PlaylistController(new SpotifyPlaylistService(client, new SetlistService(setlistFm)));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("access_token", "test-token");

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/api/v1/playlist-previews")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"setlistId":"concert"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.setlistId").value("concert"))
                .andExpect(jsonPath("$.artistName").value("Bánd"))
                .andExpect(jsonPath("$.songs.length()").value(5))
                .andExpect(jsonPath("$.songs[0].songId").value(1))
                .andExpect(jsonPath("$.songs[0].title").value("Opening + {Live}"))
                .andExpect(jsonPath("$.songs[0].status").value("MATCHED"))
                .andExpect(jsonPath("$.songs[0].candidates[0].album.images[0].url")
                        .value("https://i.scdn.co/image/opening"))
                .andExpect(jsonPath("$.songs[1].status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.songs[1].reason").value("ARTIST_MISMATCH"))
                .andExpect(jsonPath("$.songs[2].status").value("UNMATCHED"))
                .andExpect(jsonPath("$.songs[2].candidates").isEmpty())
                .andExpect(jsonPath("$.songs[3].songId").value(4))
                .andExpect(jsonPath("$.songs[3].title").value("Opening + {Live}"))
                .andExpect(jsonPath("$.songs[4].reason").value("MISSING_TITLE"));
        spotify.verify();
    }

    private static void expectSearch(MockRestServiceServer spotify, String encodedTitle, String response) {
        spotify.expect(requestTo(containsString("/search?q=track%3A" + encodedTitle + "%20artist%3AB%C3%A1nd")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}
