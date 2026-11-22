package com.setlistify.setlistify.client;

import com.jayway.jsonpath.JsonPath;
import com.setlistify.setlistify.config.SpotifyOauthConfig;
import com.setlistify.setlistify.controller.PlaylistController;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.ArtistRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.ConcertRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SetRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SetsWrapper;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SongRecord;
import com.setlistify.setlistify.services.SetlistService;
import com.setlistify.setlistify.services.SpotifyPlaylistService;
import com.setlistify.setlistify.services.SpotifyAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
        PlaylistController controller = new PlaylistController(new SpotifyPlaylistService(client, setlists), new SpotifyAuthService(client));
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
        expectSearchQuery(spotify, "track%3ACover", """
                {"tracks":{"items":[]}}
                """);
        expectSearch(spotify, "Lost", """
                {"tracks":{"items":[]}}
                """);
        expectSearchQuery(spotify, "track%3ALost", """
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
        PlaylistController controller = new PlaylistController(new SpotifyPlaylistService(client, new SetlistService(setlistFm)), new SpotifyAuthService(client));
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void previewsCreditedRemixEvenWhenTheArtistFilterMissesIt(boolean foundByArtistSearch) throws Exception {
        RestClient.Builder apiBuilder = RestClient.builder();
        MockRestServiceServer spotify = MockRestServiceServer.bindTo(apiBuilder).build();
        String noMeConoce = """
                {"tracks":{"items":[
                  {"name":"No Me Conoce","uri":"spotify:track:original","artists":[{"name":"JHAYCO"}]},
                  {"name":"No Me Conoce - Remix","uri":"spotify:track:remix",
                    "artists":[{"name":"JHAYCO"},{"name":"J Balvin"},{"name":"Bad Bunny"}]},
                  {"name":"No Me Conoce (Remix)","uri":"spotify:track:uncredited-remix",
                    "artists":[{"name":"Other Artist"}]}
                ]}}
                """;
        expectSearchQuery(spotify, "track%3ANo%20Me%20Conoce%20artist%3ABad%20Bunny",
                foundByArtistSearch ? noMeConoce : """
                        {"tracks":{"items":[]}}
                        """);
        if (!foundByArtistSearch) {
            expectSearchQuery(spotify, "track%3ANo%20Me%20Conoce", noMeConoce);
        }

        SetlistFmClient setlistFm = mock(SetlistFmClient.class);
        when(setlistFm.fetchSingleSetlist("concert")).thenReturn(new ConcertRecord(
                "concert", null, new ArtistRecord("Bad Bunny"), null,
                new SetsWrapper(List.of(new SetRecord(List.of(new SongRecord("No Me Conoce")))))
        ));
        SpotifyOauthConfig config = new SpotifyOauthConfig("test-client", "http://127.0.0.1/callback", "");
        SpotifyClient client = new SpotifyClient(config, RestClient.builder(), apiBuilder);
        PlaylistController controller = new PlaylistController(
                new SpotifyPlaylistService(client, new SetlistService(setlistFm)), new SpotifyAuthService(client));
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
                .andExpect(jsonPath("$.songs[0].status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.songs[0].reason").value("TITLE_VARIANT"))
                .andExpect(jsonPath("$.songs[0].candidates.length()").value(1))
                .andExpect(jsonPath("$.songs[0].candidates[0].name").value("No Me Conoce - Remix"))
                .andExpect(jsonPath("$.songs[0].candidates[0].artists[2]").value("Bad Bunny"));
        spotify.verify();
    }

    @Test
    void createsPrivatePlaylistFromValidatedSelectionsInSetlistOrderOnlyOnce() throws Exception {
        RestClient.Builder api = RestClient.builder();
        MockRestServiceServer spotify = MockRestServiceServer.bindTo(api).build();
        expectSearchQuery(spotify, "track%3ATiti%20Me%20Pregunto%20artist%3ABad%20Bunny", """
                {"tracks":{"items":[{"name":"Tití Me Preguntó","uri":"spotify:track:titi",
                "artists":[{"name":"Bad Bunny"}]}]}}
                """);
        expectSearchQuery(spotify, "track%3AEoO%20artist%3ABad%20Bunny", """
                {"tracks":{"items":[
                {"name":"EoO","uri":"spotify:track:album","artists":[{"name":"Bad Bunny"}]},
                {"name":"EoO","uri":"spotify:track:single","artists":[{"name":"Bad Bunny"}]}]}}
                """);
        expectSearchQuery(spotify, "track%3ADakiti%20artist%3ABad%20Bunny", """
                {"tracks":{"items":[{"name":"Dákiti","uri":"spotify:track:dakiti",
                "artists":[{"name":"Bad Bunny"}]}]}}
                """);
        spotify.expect(requestTo("https://api.spotify.com/v1/me/playlists"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"name":"Bad Bunny setlist","public":false}
                        """))
                .andRespond(withSuccess("""
                        {"id":"newPlaylist","name":"Bad Bunny setlist"}
                        """, MediaType.APPLICATION_JSON));
        spotify.expect(requestTo("https://api.spotify.com/v1/playlists/newPlaylist/items"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"uris":["spotify:track:titi","spotify:track:single"]}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SetlistFmClient setlistFm = mock(SetlistFmClient.class);
        when(setlistFm.fetchSingleSetlist("concert")).thenReturn(new ConcertRecord(
                "concert", null, new ArtistRecord("Bad Bunny"), null, new SetsWrapper(List.of(
                        new SetRecord(List.of(new SongRecord("Titi Me Pregunto"), new SongRecord("EoO"), new SongRecord("Dakiti")))
                ))
        ));
        SpotifyClient client = new SpotifyClient(new SpotifyOauthConfig("test-client", "http://127.0.0.1/callback", ""),
                RestClient.builder(), api);
        var mvc = MockMvcBuilders.standaloneSetup(new PlaylistController(
                new SpotifyPlaylistService(client, new SetlistService(setlistFm)), new SpotifyAuthService(client))).build();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("access_token", "test-token");

        String preview = mvc.perform(post("/api/v1/playlist-previews").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"setlistId\":\"concert\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String reviewId = JsonPath.read(preview, "$.reviewId");
        String valid = """
                {"reviewId":"%s","playlistName":"Bad Bunny setlist","selections":[
                {"songId":2,"trackUri":"spotify:track:single"},{"songId":1,"trackUri":"spotify:track:titi"}]}
                """.formatted(reviewId);

        mvc.perform(post("/api/v1/playlists").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(valid.replace("spotify:track:titi", "spotify:track:dakiti")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/playlists").session(session).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.spotifyUrl").value("https://open.spotify.com/playlist/newPlaylist"));
        mvc.perform(post("/api/v1/playlists").session(session).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isConflict());
        spotify.verify();
    }

    private static void expectSearch(MockRestServiceServer spotify, String encodedTitle, String response) {
        expectSearchQuery(spotify, "track%3A" + encodedTitle + "%20artist%3AB%C3%A1nd", response);
    }

    private static void expectSearchQuery(MockRestServiceServer spotify, String encodedQuery, String response) {
        spotify.expect(requestTo(containsString("/search?q=" + encodedQuery + "&type=track")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}
