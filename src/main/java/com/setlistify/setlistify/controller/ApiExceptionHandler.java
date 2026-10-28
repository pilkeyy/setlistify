package com.setlistify.setlistify.controller;

import com.setlistify.setlistify.exception.SpotifyAuthorizationException;
import com.setlistify.setlistify.model.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(SpotifyAuthorizationException.class)
    public ResponseEntity<ApiError> handleSpotifyAuthorization(SpotifyAuthorizationException exception) {
        HttpStatus status = exception.status();
        String message = "Spotify authorization is no longer valid. Sign in with Spotify again.";
        if (status == HttpStatus.FORBIDDEN) {
            message = "Spotify denied this operation. Check the granted permissions and app access.";
        }

        logger.warn("Spotify Web API rejected authorization with status {}", status.value());
        return error(status, message);
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiError> handleUpstreamResponse(RestClientResponseException exception) {
        int upstreamStatus = exception.getStatusCode().value();
        HttpStatus localStatus = HttpStatus.BAD_GATEWAY;
        String message = "An external service returned an error.";

        if (upstreamStatus == HttpStatus.NOT_FOUND.value()) {
            localStatus = HttpStatus.NOT_FOUND;
            message = "The requested external resource was not found.";
        } else if (upstreamStatus == HttpStatus.TOO_MANY_REQUESTS.value()) {
            localStatus = HttpStatus.SERVICE_UNAVAILABLE;
            message = "An external service is rate limiting requests. Please try again later.";
        } else if (upstreamStatus >= 500) {
            message = "An external service is temporarily unavailable.";
        }

        logger.warn("Upstream request failed with status {}", upstreamStatus);
        HttpHeaders headers = new HttpHeaders();
        if (upstreamStatus == HttpStatus.TOO_MANY_REQUESTS.value()) {
            String retryAfter = exception.getResponseHeaders() == null
                    ? null
                    : exception.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) {
                headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
            }
        }

        ApiError body = new ApiError(localStatus.value(), localStatus.getReasonPhrase(), message);
        return new ResponseEntity<>(body, headers, localStatus);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiError> handleUpstreamConnection(ResourceAccessException exception) {
        logger.warn("Could not reach an external service", exception);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "An external service could not be reached.");
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiError> handleInvalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "The request is malformed or contains an invalid parameter.");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message) {
        ApiError body = new ApiError(status.value(), status.getReasonPhrase(), message);
        return ResponseEntity.status(status).body(body);
    }
}
