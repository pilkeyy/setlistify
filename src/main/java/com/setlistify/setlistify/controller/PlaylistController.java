package com.setlistify.setlistify.controller;

import com.setlistify.setlistify.model.dto.ApiError;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.CreateRequest;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.PreviewRequest;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.PreviewResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ResultResponse;
import com.setlistify.setlistify.services.SpotifyPlaylistService;
import com.setlistify.setlistify.services.SpotifyAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PlaylistController {
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
        String accessToken = authService.getAccessToken(servletRequest.getSession(false));
        if (accessToken == null || accessToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(
                    401, "Unauthorized", "Sign in with Spotify before previewing a playlist."));
        }

        PreviewResponse preview = playlistService.previewPlaylist(request.setlistId(), accessToken);
        if (preview == null) {
            return ResponseEntity.unprocessableEntity().body(new ApiError(
                    422, "Unprocessable Entity", "The selected setlist must include an artist and at least one song."));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preview);
    }

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
