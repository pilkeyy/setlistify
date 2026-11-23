package com.setlistify.setlistify.exception;

/** Thrown when a newer preview in the same session replaces this one, so its remaining searches stop. */
public class PreviewSupersededException extends RuntimeException {
    public PreviewSupersededException() {
        super("A newer preview superseded this one.");
    }
}
