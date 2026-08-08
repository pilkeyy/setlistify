package com.setlistify.setlistify.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public class SpotifyDTOs {

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") int expiresIn,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("scope") String scope
    ) {}

    public record UserProfile(
            String id,
            @JsonProperty("display_name") String displayName
    ) {}

    public record SearchResponse(TracksWrapper tracks) {
        public record TracksWrapper(List<TrackItem> items) {}
        public record TrackItem(String id, String name, String uri) {}
    }

    public record CreatePlaylistRequest(
            String name,
            String description,
            @JsonProperty("public") boolean isPublic
    ) {}

    public record PlaylistResponse(
            String id,
            String name,
            @JsonProperty("external_urls") Map<String, String> externalUrls
    ) {
        public String getSpotifyUrl() {
            return externalUrls != null ? externalUrls.get("spotify") : null;
        }
    }

    public record AddItemsRequest(List<String> uris) {}
}
