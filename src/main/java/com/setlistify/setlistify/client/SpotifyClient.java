package com.setlistify.setlistify.client;

import com.setlistify.setlistify.config.SpotifyOauthConfig;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import com.setlistify.setlistify.model.dto.TokenResponse;

@Component
public class SpotifyClient {
    private final SpotifyOauthConfig oauthConfig;
    private final RestClient restClient;

    public SpotifyClient(SpotifyOauthConfig oauthConfig) {
        this.oauthConfig = oauthConfig;
        this.restClient = RestClient.builder().baseUrl("https://accounts.spotify.com").build();
    }

    public TokenResponse fetchTokens(String code, String codeVerifier) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", oauthConfig.redirectUri());
        body.add("client_id", oauthConfig.clientId());
        body.add("code_verifier", codeVerifier);

        return restClient.post()
                         .uri("/api/token")
                         .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                         .body(body)
                         .retrieve()
                         .body(TokenResponse.class);
    }

}
