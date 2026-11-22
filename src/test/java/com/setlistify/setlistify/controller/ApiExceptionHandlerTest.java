package com.setlistify.setlistify.controller;

import com.setlistify.setlistify.exception.SpotifyAuthorizationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler exceptionHandler = new ApiExceptionHandler();

    @Test
    void mapsSpotifyUnauthorizedTo401WithSignInMessage() {
        SpotifyAuthorizationException exception = new SpotifyAuthorizationException(
                HttpStatus.UNAUTHORIZED,
                new RuntimeException("Spotify rejected the access token")
        );

        var response = exceptionHandler.handleSpotifyAuthorization(exception);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(401, response.getBody().status());
        assertEquals("Spotify authorization is no longer valid. Sign in with Spotify again.",
                response.getBody().message());
    }

    @Test
    void mapsSpotifyForbiddenTo403WithPermissionMessage() {
        SpotifyAuthorizationException exception = new SpotifyAuthorizationException(
                HttpStatus.FORBIDDEN,
                new RuntimeException("Spotify denied the operation")
        );

        var response = exceptionHandler.handleSpotifyAuthorization(exception);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(403, response.getBody().status());
        assertEquals("Spotify denied this operation. Check the granted permissions and app access.",
                response.getBody().message());
    }

    @Test
    void mapsUpstreamNotFoundTo404() {
        RestClientResponseException exception = upstreamException(HttpStatus.NOT_FOUND, new HttpHeaders());

        var response = exceptionHandler.handleUpstreamResponse(exception);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("The requested external resource was not found.", response.getBody().message());
    }

    @Test
    void mapsUpstreamRateLimitTo503AndPreservesRetryAfter() {
        HttpHeaders upstreamHeaders = new HttpHeaders();
        upstreamHeaders.set(HttpHeaders.RETRY_AFTER, "7");
        RestClientResponseException exception = upstreamException(HttpStatus.TOO_MANY_REQUESTS, upstreamHeaders);

        var response = exceptionHandler.handleUpstreamResponse(exception);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("7", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals(503, response.getBody().status());
    }

    @Test
    void mapsUpstreamConnectionFailureTo503() {
        var response = exceptionHandler.handleUpstreamConnection(
                new ResourceAccessException("Connection timed out")
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("An external service could not be reached.", response.getBody().message());
    }

    @Test
    void mapsMissingRequestParameterTo400() {
        var response = exceptionHandler.handleInvalidRequest(
                new MissingServletRequestParameterException("artist", "String")
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("The request is malformed or contains an invalid parameter.", response.getBody().message());
    }

    private static RestClientResponseException upstreamException(HttpStatus status, HttpHeaders headers) {
        return new RestClientResponseException(
                "Upstream error",
                status,
                status.getReasonPhrase(),
                headers,
                new byte[0],
                StandardCharsets.UTF_8
        );
    }
}
