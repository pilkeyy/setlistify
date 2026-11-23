package com.setlistify.setlistify.controller;

import com.setlistify.setlistify.exception.PreviewSupersededException;
import com.setlistify.setlistify.model.dto.ApiError;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ConfirmPlaylistRequest;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ConfirmPlaylistResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.CreateRequest;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.PreviewRequest;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.PreviewResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ResultResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ReviewPreviewResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.TrackSelection;
import com.setlistify.setlistify.services.SpotifyPlaylistService;
import com.setlistify.setlistify.services.SpotifyAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class PlaylistController {
    private static final String REVIEW = "playlist_review";
    /** ID of the most recently started preview; only that preview may replace the active review. */
    private static final String PENDING_REVIEW = "playlist_review_pending";
    private static final int MAX_PLAYLIST_NAME_LENGTH = 100;
    private final SpotifyPlaylistService playlistService;
    private final SpotifyAuthService authService;

    public PlaylistController(SpotifyPlaylistService playlistService, SpotifyAuthService authService) {
        this.playlistService = playlistService;
        this.authService = authService;
    }

    @PostMapping("/playlist-previews")
    public ResponseEntity<?> previewPlaylist(
            @RequestBody PreviewRequest request,
            HttpServletRequest servletRequest) {
        if (request == null || request.setlistId() == null || request.setlistId().isBlank()) {
            return ResponseEntity.badRequest().body(new ApiError(400, "Bad Request", "setlistId is required."));
        }
        HttpSession session = servletRequest.getSession(false);
        if (session == null) {
            return reviewError(HttpStatus.UNAUTHORIZED, "Sign in with Spotify before previewing a playlist.");
        }
        String sessionId = session.getId();
        // Register this preview as the latest before anything that can block (token refresh, matching),
        // so an older request that resumes later can't claim to be newer.
        String reviewId = UUID.randomUUID().toString();
        synchronized (WebUtils.getSessionMutex(session)) {
            session.setAttribute(PENDING_REVIEW, reviewId);
        }
        String accessToken = authService.getAccessToken(session);
        if (accessToken == null || accessToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(
                    401, "Unauthorized", "Sign in with Spotify before previewing a playlist."));
        }

        PreviewResponse preview;
        try {
            preview = playlistService.previewPlaylist(request.setlistId(), accessToken,
                    () -> isSuperseded(session, reviewId));
        } catch (PreviewSupersededException exception) {
            return reviewError(HttpStatus.CONFLICT, "A newer review was started. Use that review instead.");
        }
        if (preview == null) {
            return ResponseEntity.unprocessableEntity().body(new ApiError(
                    422, "Unprocessable Entity", "The selected setlist must include an artist and at least one song."));
        }
        List<ReviewRow> rows = preview.songs().stream()
                .map(song -> new ReviewRow(song.songId(), song.candidates().stream().map(candidate -> candidate.uri()).toList()))
                .toList();
        synchronized (WebUtils.getSessionMutex(session)) {
            if (!sessionId.equals(session.getId())) {
                return reviewError(HttpStatus.CONFLICT, "Your Spotify session changed. Review the matches again.");
            }
            if (!reviewId.equals(session.getAttribute(PENDING_REVIEW))) {
                return reviewError(HttpStatus.CONFLICT, "A newer review was started. Use that review instead.");
            }
            session.removeAttribute(PENDING_REVIEW);
            session.setAttribute(REVIEW, new PlaylistReview(reviewId, sessionId, preview.artistName(),
                    Instant.now().plusSeconds(30 * 60), rows));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ReviewPreviewResponse(reviewId, preview.setlistId(), preview.artistName(), preview.songs()));
    }

    @PostMapping(value = "/playlists", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> confirmPlaylist(@RequestBody ConfirmPlaylistRequest request, HttpServletRequest servletRequest) {
        if (request == null || request.reviewId() == null || request.reviewId().isBlank()
                || request.playlistName() == null || request.playlistName().isBlank()
                || request.playlistName().strip().length() > MAX_PLAYLIST_NAME_LENGTH) {
            return reviewError(HttpStatus.BAD_REQUEST, "A review ID and a playlist name of 1 to 100 characters are required.");
        }
        if (request.selections() == null || request.selections().isEmpty()) {
            return reviewError(HttpStatus.UNPROCESSABLE_ENTITY, "Select at least one song before making a playlist.");
        }

        HttpSession session = servletRequest.getSession(false);
        if (session == null) {
            return reviewError(HttpStatus.UNAUTHORIZED, "Connect Spotify again before making a playlist.");
        }
        String sessionId = session.getId();
        String accessToken = authService.getAccessToken(session);
        if (accessToken == null || accessToken.isBlank()) {
            return reviewError(HttpStatus.UNAUTHORIZED, "Connect Spotify again before making a playlist.");
        }

        PlaylistReview review;
        List<String> trackUris;
        synchronized (WebUtils.getSessionMutex(session)) {
            review = (PlaylistReview) session.getAttribute(REVIEW);
            if (review == null || !review.id().equals(request.reviewId()) || !review.sessionId().equals(sessionId)
                    || !sessionId.equals(session.getId())
                    || !Instant.now().isBefore(review.expiresAt())) {
                return reviewError(HttpStatus.CONFLICT, "This review is no longer active. Review the matches again.");
            }
            trackUris = resolveSelections(review.rows(), request.selections());
            if (trackUris == null) {
                return reviewError(HttpStatus.UNPROCESSABLE_ENTITY, "Each selected recording must belong to its reviewed song.");
            }
            // Consume before the first Spotify write so this review can only be submitted once.
            session.removeAttribute(REVIEW);
        }

        ConfirmPlaylistResponse result = playlistService.createReviewedPlaylist(
                request.playlistName().strip(), review.artistName(), trackUris, accessToken);
        HttpStatus status = HttpStatus.CREATED;
        if (result.message() != null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(result);
    }

    /** True once a newer preview registered itself, or the session ended; called from search threads. */
    private static boolean isSuperseded(HttpSession session, String reviewId) {
        try {
            return !reviewId.equals(session.getAttribute(PENDING_REVIEW));
        } catch (IllegalStateException invalidated) {
            return true;
        }
    }

    private List<String> resolveSelections(List<ReviewRow> rows, List<TrackSelection> selections) {
        if (selections.size() > rows.size()) {
            return null;
        }
        Map<Integer, String> bySongId = new HashMap<>();
        for (TrackSelection selection : selections) {
            if (selection == null || selection.trackUri() == null || selection.trackUri().isBlank()
                    || bySongId.putIfAbsent(selection.songId(), selection.trackUri()) != null) {
                return null;
            }
        }
        List<String> ordered = new ArrayList<>();
        for (ReviewRow row : rows) {
            String uri = bySongId.remove(row.songId());
            if (uri != null) {
                if (!row.candidateUris().contains(uri)) {
                    return null;
                }
                ordered.add(uri);
            }
        }
        if (!bySongId.isEmpty()) {
            return null;
        }
        return ordered;
    }

    private ResponseEntity<ApiError> reviewError(HttpStatus status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ApiError(status.value(), status.getReasonPhrase(), message));
    }

    private record PlaylistReview(String id, String sessionId, String artistName, Instant expiresAt, List<ReviewRow> rows) {}

    private record ReviewRow(int songId, List<String> candidateUris) {}

    @PostMapping("/playlists/generate")
    public ResponseEntity<?> generatePlaylist(@RequestBody CreateRequest request, HttpServletRequest servletRequest) {
        if (request == null || request.setlistId() == null || request.setlistId().isBlank()
                || request.artistName() == null || request.artistName().isBlank()
                || request.playlistName() == null || request.playlistName().isBlank()) {
            ApiError error = new ApiError(400, "Bad Request",
                    "setlistId, artistName, and playlistName are required.");
            return ResponseEntity.badRequest().body(error);
        }

        String accessToken = authService.getAccessToken(servletRequest.getSession(false));
        if (accessToken == null || accessToken.isBlank()) {
            ApiError error = new ApiError(401, "Unauthorized", "Sign in with Spotify before creating a playlist.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        ResultResponse response = playlistService.generatePlaylistFromSetlist(request, accessToken);
        if (response == null) {
            ApiError error = new ApiError(422, "Unprocessable Entity",
                    "The selected setlist does not contain songs that can be processed.");
            return ResponseEntity.unprocessableEntity().body(error);
        }
        if (!response.playlistCreated()) {
            return ResponseEntity.unprocessableEntity().body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
