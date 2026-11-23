package com.setlistify.setlistify.exception;

/** Thrown when matching a whole setlist against Spotify exceeds the preview deadline. */
public class SpotifyMatchTimeoutException extends RuntimeException {
    public SpotifyMatchTimeoutException() {
        super("Matching the setlist against Spotify exceeded the preview deadline.");
    }
}
