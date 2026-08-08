package com.setlistify.setlistify.controller;

import com.setlistify.setlistify.config.SpotifyOauthConfig;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.TokenResponse;
import com.setlistify.setlistify.services.SpotifyAuthService;
import com.setlistify.setlistify.util.PkceUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final SpotifyOauthConfig oauthConfig;
    private final SpotifyAuthService spotifyAuthService;

    public AuthController(SpotifyOauthConfig oauthConfig, SpotifyAuthService spotifyAuthService) {
        this.oauthConfig = oauthConfig;
        this.spotifyAuthService = spotifyAuthService;
    }

    @GetMapping("/login")
    public ResponseEntity<Void> initiateSpotifyLogin(HttpSession session) {
        String codeVerifier = PkceUtil.generateCodeVerifier();
        String codeChallenge = PkceUtil.generateCodeChallenge(codeVerifier);

        String secureState = UUID.randomUUID().toString();

        session.setAttribute("spotify_verifier", codeVerifier);
        session.setAttribute("oauth_state", secureState);

        URI authorizationUri = UriComponentsBuilder.newInstance()
                                                   .scheme("https")
                                                   .host("accounts.spotify.com")
                                                   .path("/authorize")
                                                   .queryParam("response_type", "code")
                                                   .queryParam("client_id", oauthConfig.clientId())
                                                   .queryParam("scope", oauthConfig.scopes())
                                                   .queryParam("redirect_uri", oauthConfig.redirectUri())
                                                   .queryParam("state", secureState)
                                                   .queryParam("code_challenge_method", "S256")
                                                   .queryParam("code_challenge", codeChallenge)
                                                   .build()
                                                   .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(authorizationUri);

        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    @GetMapping("/callback")
    public ResponseEntity<String> handleSpotifyCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            HttpSession session) {
        if (error != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Authorization failed: " + error);
        }
        String savedState = (String) session.getAttribute("oauth_state");
        if (state == null || !state.equals(savedState)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid state parameter.");
        }
        String codeVerifier = (String) session.getAttribute("spotify_verifier");
        if (code == null || codeVerifier == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing authorization code or code verifier");
        }
        TokenResponse tokenResponse = spotifyAuthService.exchangeCodeForTokens(code, codeVerifier);

        session.setAttribute("access_token", tokenResponse.accessToken());
        return ResponseEntity.ok("Successfully authenticated with Spotify!");
    }
}

