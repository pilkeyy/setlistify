package com.setlistify.setlistify.controller;

import com.setlistify.setlistify.config.SpotifyOauthConfig;
import com.setlistify.setlistify.services.SpotifyAuthService;
import com.setlistify.setlistify.util.PkceUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final SpotifyOauthConfig oauthConfig;
    private final SpotifyAuthService spotifyAuthService;
    private final URI frontendUrl;

    public AuthController(SpotifyOauthConfig oauthConfig, SpotifyAuthService spotifyAuthService,
                          @Value("${app.frontend-url:http://127.0.0.1:5173}") URI frontendUrl) {
        this.oauthConfig = oauthConfig;
        this.spotifyAuthService = spotifyAuthService;
        this.frontendUrl = frontendUrl;
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

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(authorizationUri)
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> handleSpotifyCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return returnToFrontend("failed");
        }
        String savedState = (String) session.getAttribute("oauth_state");
        if (state == null || !state.equals(savedState)) {
            return returnToFrontend("failed");
        }
        String codeVerifier = (String) session.getAttribute("spotify_verifier");
        session.removeAttribute("oauth_state");
        session.removeAttribute("spotify_verifier");
        if (error != null || code == null || code.isBlank() || codeVerifier == null) {
            return returnToFrontend("failed");
        }

        try {
            spotifyAuthService.exchangeCodeForTokens(code, codeVerifier, session);
            request.changeSessionId();
            return returnToFrontend("connected");
        } catch (RestClientException | ResponseStatusException exception) {
            return returnToFrontend("failed");
        }
    }

    private ResponseEntity<Void> returnToFrontend(String result) {
        URI location = UriComponentsBuilder.fromUri(frontendUrl)
                .replaceQueryParam("spotify", result)
                .build(true)
                .toUri();
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(location)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .build();
    }
}
