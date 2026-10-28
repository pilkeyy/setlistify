package com.setlistify.setlistify.client;

import com.setlistify.setlistify.config.SpotifyOauthConfig;
import com.setlistify.setlistify.model.dto.SpotifyDTOs;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.CreatePlaylistRequest;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.PlaylistResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.UserProfile;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.TokenResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class SpotifyClient {
    private final SpotifyOauthConfig oauthConfig;
    private final RestClient authRestClient;
    private final RestClient apiRestClient;

    public SpotifyClient(SpotifyOauthConfig oauthConfig) {
        this.oauthConfig = oauthConfig;
        authRestClient = RestClient.builder().baseUrl("https://accounts.spotify.com").build();
        apiRestClient = RestClient.builder().baseUrl("https://api.spotify.com/v1").build();
    }

    public TokenResponse fetchTokens(String code, String codeVerifier) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", oauthConfig.redirectUri());
        body.add("client_id", oauthConfig.clientId());
        body.add("code_verifier", codeVerifier);

        return authRestClient.post()
                             .uri("/api/token")
                             .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                             .body(body)
                             .retrieve()
                             .body(TokenResponse.class);
    }

    public UserProfile fetchUserProfile(String accessToken) {
        return apiRestClient.get()
                            .uri("/me")
                            .header("Authorization", "Bearer " + accessToken)
                            .retrieve()
                            .body(UserProfile.class);
    }

    public PlaylistResponse createPlaylist(CreatePlaylistRequest request, String accessToken) {

        return apiRestClient.post()
                            .uri("/me/playlists")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .body(PlaylistResponse.class);
    }

    public SearchResponse searchTrack(String accessToken, String query) {
        return apiRestClient.get()
                            .uri(uriBuilder -> uriBuilder.path("/search")
                                                         .queryParam("q", query)
                                                         .queryParam("type", "track")
                                                         .queryParam("limit", 5)
                                                         .build())
                            .header("Authorization", "Bearer " + accessToken)
                            .retrieve()
                            .body(SearchResponse.class);
    }

    public void addTracksToPlaylist(String accessToken, String playlistID, List<String> batch) {
        apiRestClient.post()
                     .uri("/playlists/{playlist_id}/items", playlistID)
                     .header("Authorization", "Bearer " + accessToken)
                     .contentType(MediaType.APPLICATION_JSON)
                     .body(new SpotifyDTOs.AddItemsRequest(batch))
                     .retrieve()
                     .toBodilessEntity();
    }
}
