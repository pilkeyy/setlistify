package com.setlistify.setlistify.controller;

import com.setlistify.setlistify.client.SpotifyClient;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.CreateRequest;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.PreviewResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ResultResponse;
import com.setlistify.setlistify.services.SpotifyPlaylistService;
import com.setlistify.setlistify.services.SpotifyAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import com.jayway.jsonpath.JsonPath;

@WebMvcTest(PlaylistController.class)
@Import(SpotifyAuthService.class)
class PlaylistControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SpotifyPlaylistService playlistService;

    @MockitoBean
    private SpotifyClient spotifyClient;

    @Test
    void previewRequiresSpotifySession() throws Exception {
        mockMvc.perform(post("/api/v1/playlist-previews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"setlistId":"setlist-123"}
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(playlistService);
    }

    @Test
    void previewRejectsBlankSetlistId() throws Exception {
        mockMvc.perform(post("/api/v1/playlist-previews")
                        .session(authenticatedSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"setlistId":" "}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(playlistService);
    }

    @Test
    void generatePlaylistReturnsBadRequestWhenRequiredFieldsAreMissing() throws Exception {
        mockMvc.perform(post("/api/v1/playlists/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "setlistId": "setlist-123",
                                  "artistName": "Bad Bunny",
                                  "playlistName": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));

        verifyNoInteractions(playlistService);
    }

    @Test
    void generatePlaylistReturnsUnauthorizedWhenSpotifySessionIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/playlists/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Sign in with Spotify before creating a playlist."));

        verifyNoInteractions(playlistService);
    }

    @Test
    void generatePlaylistReturnsUnprocessableEntityWhenServiceCannotFindSongs() throws Exception {
        when(playlistService.generatePlaylistFromSetlist(validRequest(), "spotify-access-token"))
                .thenReturn(null);

        mockMvc.perform(post("/api/v1/playlists/generate")
                        .session(authenticatedSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Unprocessable Entity"));
    }

    @Test
    void generatePlaylistReturnsUnprocessableEntityWhenNoPlaylistWasCreated() throws Exception {
        ResultResponse result = new ResultResponse(
                null,
                null,
                null,
                false,
                2,
                0,
                List.of(),
                List.of("Unknown Song")
        );
        when(playlistService.generatePlaylistFromSetlist(validRequest(), "spotify-access-token"))
                .thenReturn(result);

        mockMvc.perform(post("/api/v1/playlists/generate")
                        .session(authenticatedSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.playlistCreated").value(false))
                .andExpect(jsonPath("$.unmatchedSongs[0]").value("Unknown Song"));
    }

    @Test
    void generatePlaylistReturnsCreatedWhenPlaylistWasCreated() throws Exception {
        ResultResponse result = new ResultResponse(
                "playlist-123",
                "Bad Bunny en San Juan",
                "https://open.spotify.com/playlist/playlist-123",
                true,
                2,
                2,
                List.of(),
                List.of()
        );
        when(playlistService.generatePlaylistFromSetlist(validRequest(), "spotify-access-token"))
                .thenReturn(result);

        mockMvc.perform(post("/api/v1/playlists/generate")
                        .session(authenticatedSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playlistCreated").value(true))
                .andExpect(jsonPath("$.playlistId").value("playlist-123"));
    }

    @Test
    void slowerOlderPreviewDoesNotReplaceNewerReview() throws Exception {
        MockHttpSession session = authenticatedSession();
        String[] newerReviewId = new String[1];
        when(playlistService.previewPlaylist(eq("concert-b"), anyString()))
                .thenReturn(new PreviewResponse("concert-b", "Band B", List.of()));
        // Concert A's preview is still matching when the user starts concert B, and A finishes last.
        when(playlistService.previewPlaylist(eq("concert-a"), anyString())).thenAnswer(invocation -> {
            String newer = mockMvc.perform(post("/api/v1/playlist-previews").session(session)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"setlistId\":\"concert-b\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            newerReviewId[0] = JsonPath.read(newer, "$.reviewId");
            return new PreviewResponse("concert-a", "Band A", List.of());
        });

        mockMvc.perform(post("/api/v1/playlist-previews").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"setlistId\":\"concert-a\"}"))
                .andExpect(status().isConflict());

        Object review = session.getAttribute("playlist_review");
        var id = review.getClass().getDeclaredMethod("id");
        id.setAccessible(true);
        assertEquals(newerReviewId[0], id.invoke(review));
    }

    private static MockHttpSession authenticatedSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("access_token", "spotify-access-token");
        return session;
    }

    private static CreateRequest validRequest() {
        return new CreateRequest("setlist-123", "Bad Bunny", "Bad Bunny en San Juan", false);
    }

    private static String validRequestJson() {
        return """
                {
                  "setlistId": "setlist-123",
                  "artistName": "Bad Bunny",
                  "playlistName": "Bad Bunny en San Juan",
                  "isPublic": false
                }
                """;
    }
}
