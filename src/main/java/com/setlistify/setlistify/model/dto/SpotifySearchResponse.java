package com.setlistify.setlistify.model.dto;

import java.util.List;

public record SpotifySearchResponse(TrackWrapper tracks) {
    public record TrackWrapper(List<TrackItem> items) {
    }

    public record TrackItem(String id, String name, String uri) {
    }
}
