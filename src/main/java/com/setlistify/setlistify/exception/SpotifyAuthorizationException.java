package com.setlistify.setlistify.exception;

import org.springframework.http.HttpStatus;

public class SpotifyAuthorizationException extends RuntimeException {
    private final HttpStatus status;

    public SpotifyAuthorizationException(HttpStatus status, Throwable cause) {
        super("Spotify rejected the authorization for a Web API request.", cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
