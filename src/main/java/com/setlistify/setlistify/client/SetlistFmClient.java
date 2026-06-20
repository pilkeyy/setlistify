package com.setlistify.setlistify.client;

import com.setlistify.setlistify.model.dto.ConcertRecord;
import com.setlistify.setlistify.model.dto.SetlistResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SetlistFmClient {
    private final RestClient restClient;
    private final String apiKey;

    public SetlistFmClient(@Value("${SETLIST_FM_KEY}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder().baseUrl("https://api.setlist.fm/rest/1.0").build();
    }

    public ConcertRecord fetchSingleSetlist(String setlistId) {
        return this.restClient.get()
                              .uri("/setlist/{id}", setlistId)
                              .header("Accept", "application/json")
                              .header("x-api-key", apiKey)
                              .retrieve()
                              .body(ConcertRecord.class);
    }

    public SetlistResponse fetchRawSetlists(String artistName) {
        return restClient.get()
                         .uri("/search/setlists?artistName={name}", artistName)
                         .header("Accept", "application/json")
                         .header("x-api-key", this.apiKey)
                         .retrieve()
                         .body(SetlistResponse.class);
    }
}
