package com.setlistify.setlistify.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SpotifyUserProfile(@JsonProperty("account_id") String id,
                                 @JsonProperty("display_name") String displayName) {
}
