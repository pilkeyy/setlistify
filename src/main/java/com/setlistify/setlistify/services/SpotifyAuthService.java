package com.setlistify.setlistify.services;

import com.setlistify.setlistify.client.SpotifyClient;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.TokenResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
public class SpotifyAuthService {
    private static final String TOKENS = "spotify_tokens";
    private final SpotifyClient spotifyClient;

    public SpotifyAuthService(SpotifyClient spotifyClient) {
        this.spotifyClient = spotifyClient;
    }

    public void exchangeCodeForTokens(String code, String codeVerifier, HttpSession session) {
        saveTokens(session, spotifyClient.fetchTokens(code, codeVerifier), null);
    }

    public String getAccessToken(HttpSession session) {
        if (session == null) {
            return null;
        }
        SessionTokens tokens = (SessionTokens) session.getAttribute(TOKENS);
        if (tokens == null) {
            return (String) session.getAttribute("access_token");
        }
        if (Instant.now().isBefore(tokens.expiresAt())) {
            return tokens.accessToken();
        }
        if (tokens.refreshToken() == null || tokens.refreshToken().isBlank()) {
            clearTokens(session);
            return null;
        }
        try {
            return saveTokens(session, spotifyClient.refreshTokens(tokens.refreshToken()), tokens.refreshToken());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 400) {
                clearTokens(session);
                return null;
            }
            throw exception;
        }
    }

    private String saveTokens(HttpSession session, TokenResponse tokens, String previousRefreshToken) {
        if (tokens == null || tokens.accessToken() == null || tokens.accessToken().isBlank() || tokens.expiresIn() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Spotify returned an incomplete token response.");
        }
        String refreshToken = tokens.refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshToken = previousRefreshToken;
        }
        session.setAttribute(TOKENS, new SessionTokens(
                tokens.accessToken(), Instant.now().plusSeconds(tokens.expiresIn()), refreshToken));
        session.removeAttribute("access_token");
        return tokens.accessToken();
    }

    private void clearTokens(HttpSession session) {
        session.removeAttribute("access_token");
        session.removeAttribute(TOKENS);
    }

    private record SessionTokens(String accessToken, Instant expiresAt, String refreshToken) {
    }
}

