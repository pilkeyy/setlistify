package com.setlistify.setlistify.services;

import com.setlistify.setlistify.client.SpotifyClient;
import com.setlistify.setlistify.model.dto.TokenResponse;
import org.springframework.stereotype.Service;

@Service
public class SpotifyAuthService {
    private final SpotifyClient spotifyClient;

    public SpotifyAuthService(SpotifyClient spotifyClient) {
        this.spotifyClient = spotifyClient;
    }

    public TokenResponse exchangeCodeForTokens(String code, String codeVerifier) {
        return spotifyClient.fetchTokens(code, codeVerifier);
    }
}
