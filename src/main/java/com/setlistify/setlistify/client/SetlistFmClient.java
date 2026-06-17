package com.setlistify.setlistify.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SetlistFmClient {
    private final RestClient restClient;
    @Value("${SETLIST_FM_KEY}")
    private String apiKey;

    public SetlistFmClient() {
        this.restClient = RestClient.builder().baseUrl("https://api.setlist.fm/rest/1.0").build();
    }

    public String fetchRawSetlists(String artistName) {
        return restClient.get().
                uri("/search/setlists?artistName=" + artistName)
                .header("Accept", "application/json")
                .header("x-api-key", apiKey)
                .retrieve().
                body(String.class);
    }
}
