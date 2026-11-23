package com.setlistify.setlistify.client;

import com.setlistify.setlistify.config.SpotifyOauthConfig;
import com.setlistify.setlistify.exception.SpotifyAuthorizationException;
import com.setlistify.setlistify.model.dto.SpotifyDTOs;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.CreatePlaylistRequest;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.PlaylistResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.UserProfile;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.TokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.function.Supplier;

@Component
public class SpotifyClient {
    private static final Logger logger = LoggerFactory.getLogger(SpotifyClient.class);
    private final SpotifyOauthConfig oauthConfig;
    private final RestClient authRestClient;
    private final RestClient apiRestClient;
    @Autowired
    public SpotifyClient(SpotifyOauthConfig oauthConfig) {
        this(oauthConfig,
                RestClient.builder().requestFactory(UpstreamHttp.requestFactory()),
                RestClient.builder().requestFactory(UpstreamHttp.requestFactory()));
    }

    SpotifyClient(SpotifyOauthConfig oauthConfig,
                  RestClient.Builder authRestClientBuilder,
                  RestClient.Builder apiRestClientBuilder) {
        this.oauthConfig = oauthConfig;
        authRestClient = authRestClientBuilder.baseUrl("https://accounts.spotify.com").build();
        apiRestClient = apiRestClientBuilder.baseUrl("https://api.spotify.com/v1").build();
    }

    public TokenResponse fetchTokens(String code, String codeVerifier) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", oauthConfig.redirectUri());
        body.add("client_id", oauthConfig.clientId());
        body.add("code_verifier", codeVerifier);

        return requestTokens(body);
    }

    public TokenResponse refreshTokens(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);
        body.add("client_id", oauthConfig.clientId());
        return requestTokens(body);
    }

    private TokenResponse requestTokens(MultiValueMap<String, String> body) {
        return authRestClient.post()
                             .uri("/api/token")
                             .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                             .body(body)
                             .retrieve()
                             .body(TokenResponse.class);
    }

    public UserProfile fetchUserProfile(String accessToken) {
        return executeSpotifyApiRequest("profile", () -> apiRestClient.get()
                                                          .uri("/me")
                                                          .header("Authorization", "Bearer " + accessToken)
                                                          .retrieve()
                                                          .body(UserProfile.class));
    }

    public PlaylistResponse createPlaylist(CreatePlaylistRequest request, String accessToken) {

        return executeSpotifyApiRequest("create playlist", () -> apiRestClient.post()
                                                          .uri("/me/playlists")
                                                          .header("Authorization", "Bearer " + accessToken)
                                                          .contentType(MediaType.APPLICATION_JSON)
                                                          .body(request)
                                                          .retrieve()
                                                          .body(PlaylistResponse.class));
    }

    public SearchResponse searchTrack(String accessToken, String query) {
        return executeSpotifyApiRequest("search", () -> apiRestClient.get()
                                                          .uri(uriBuilder -> uriBuilder.path("/search")
                                                                                       .queryParam("q", "{query}")
                                                                                       .queryParam("type", "track")
                                                                                       .queryParam("limit", 5)
                                                                                       .build(query))
                                                          .header("Authorization", "Bearer " + accessToken)
                                                          .retrieve()
                                                          .body(SearchResponse.class));
    }

    public void addTracksToPlaylist(String accessToken, String playlistID, List<String> batch) {
        executeSpotifyApiRequest("add tracks", () -> {
            apiRestClient.post()
                         .uri("/playlists/{playlist_id}/items", playlistID)
                         .header("Authorization", "Bearer " + accessToken)
                         .contentType(MediaType.APPLICATION_JSON)
                         .body(new SpotifyDTOs.AddItemsRequest(batch))
                         .retrieve()
                         .toBodilessEntity();
            return null;
        });
    }

    private <T> T executeSpotifyApiRequest(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 429) {
                logger.warn("Spotify {} returned 429 (Retry-After: {})", operation, retryAfter(exception));
            }
            if (status == 401) {
                throw new SpotifyAuthorizationException(HttpStatus.UNAUTHORIZED, exception);
            }
            if (status == 403) {
                throw new SpotifyAuthorizationException(HttpStatus.FORBIDDEN, exception);
            }
            throw exception;
        }
    }

    static String retryAfter(RestClientResponseException exception) {
        return exception.getResponseHeaders() == null
                ? "none"
                : String.valueOf(exception.getResponseHeaders().getFirst("Retry-After"));
    }
}
