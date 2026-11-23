package com.setlistify.setlistify.exception;

/** Thrown without calling Spotify while a recent 429's Retry-After cooldown is still in effect. */
public class SpotifyRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public SpotifyRateLimitedException(long retryAfterSeconds) {
        super("Spotify is rate limiting searches; retry after " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
