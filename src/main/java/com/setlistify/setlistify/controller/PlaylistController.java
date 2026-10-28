package com.setlistify.setlistify.controller;

import com.setlistify.setlistify.model.dto.PlaylistDTOs.ResultResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.CreateRequest;
import com.setlistify.setlistify.model.dto.ApiError;
import com.setlistify.setlistify.services.SpotifyPlaylistService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/v1/playlists")
public class PlaylistController {
    private final SpotifyPlaylistService
            playlistService;

    public PlaylistController(SpotifyPlaylistService playlistService) {
        this.playlistService = playlistService;
    }
    @PostMapping("/generate")
    public ResponseEntity<?> generatePlaylist(@RequestBody CreateRequest request, HttpSession session) {
        if (request == null || request.setlistId() == null || request.setlistId().isBlank()
                || request.artistName() == null || request.artistName().isBlank()
                || request.playlistName() == null || request.playlistName().isBlank()) {
            ApiError error = new ApiError(400, "Bad Request",
                    "setlistId, artistName, and playlistName are required.");
            return ResponseEntity.badRequest().body(error);
        }

        String accessToken = (String) session.getAttribute("access_token");
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
