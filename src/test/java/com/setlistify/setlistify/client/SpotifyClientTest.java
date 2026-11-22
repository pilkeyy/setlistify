package com.setlistify.setlistify.client;

import com.setlistify.setlistify.config.SpotifyOauthConfig;
import com.setlistify.setlistify.exception.SpotifyAuthorizationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class SpotifyClientTest {
    private static final String ARTIST = "Bad Bunny";
    private static final String TRACK_QUERY = "track:Titi Me Pregunto artist:" + ARTIST;

    @Test
    void translatesSpotifyWebApi401IntoAuthorizationException() {
        RestClient.Builder apiBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(apiBuilder).build();
        SpotifyClient client = new SpotifyClient(testConfig(), RestClient.builder(), apiBuilder);
        server.expect(requestTo(containsString("/search")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer expired-token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        SpotifyAuthorizationException exception = assertThrows(
                SpotifyAuthorizationException.class,
                () -> client.searchTrack("expired-token", TRACK_QUERY)
        );

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
        server.verify();
    }

    @Test
    void translatesSpotifyWebApi403IntoAuthorizationException() {
        RestClient.Builder apiBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(apiBuilder).build();
        SpotifyClient client = new SpotifyClient(testConfig(), RestClient.builder(), apiBuilder);
        server.expect(requestTo(containsString("/search")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer restricted-token"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        SpotifyAuthorizationException exception = assertThrows(
                SpotifyAuthorizationException.class,
                () -> client.searchTrack("restricted-token", TRACK_QUERY)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        server.verify();
    }

    @Test
    void leavesTokenExchangeFailuresAsGeneralUpstreamErrors() {
        RestClient.Builder authBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(authBuilder).build();
        SpotifyClient client = new SpotifyClient(testConfig(), authBuilder, RestClient.builder());
        server.expect(requestTo("https://accounts.spotify.com/api/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThrows(RestClientResponseException.class,
                () -> client.fetchTokens("invalid-code", "test-verifier"));
        server.verify();
    }

    private static SpotifyOauthConfig testConfig() {
        return new SpotifyOauthConfig(
                "test-client-id",
                "http://127.0.0.1:8080/api/v1/auth/callback",
                ""
        );
    }
}
