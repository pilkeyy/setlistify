package com.setlistify.setlistify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spotify")
public record SpotifyOauthConfig(
        String clientId,
        String redirectUri,
        String scopes
) {}