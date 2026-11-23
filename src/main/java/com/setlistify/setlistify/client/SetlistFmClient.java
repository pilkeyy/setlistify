package com.setlistify.setlistify.client;

import com.setlistify.setlistify.model.dto.SetlistFmDTOs.ConcertRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SetlistResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

@Component
public class SetlistFmClient {
    private static final Logger logger = LoggerFactory.getLogger(SetlistFmClient.class);
    private final RestClient restClient;
    private final String apiKey;

    public SetlistFmClient(@Value("${SETLIST_FM_KEY}") String apiKey) {
        this.apiKey = apiKey;
        restClient = RestClient.builder()
                               .requestFactory(UpstreamHttp.requestFactory())
                               .baseUrl("https://api.setlist.fm/rest/1.0").build();
    }

    public ConcertRecord fetchSingleSetlist(String setlistId) {
        return logRateLimit("setlist", () -> restClient.get()
                         .uri("/setlist/{id}", setlistId)
                         .header("Accept", "application/json")
                         .header("x-api-key", apiKey)
                         .retrieve()
                         .body(ConcertRecord.class));
    }

    public SetlistResponse fetchRawSetlists(String artistName) {
        return logRateLimit("artist search", () -> restClient.get()
                         .uri("/search/setlists?artistName={name}", artistName)
                         .header("Accept", "application/json")
                         .header("x-api-key", this.apiKey)
                         .retrieve()
                         .body(SetlistResponse.class));
    }

    private static <T> T logRateLimit(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 429) {
                logger.warn("setlist.fm {} returned 429 (Retry-After: {})", operation, SpotifyClient.retryAfter(exception));
            }
            throw exception;
        }
    }
}
