package com.setlistify.setlistify.controller;

import com.setlistify.setlistify.model.dto.PlaylistDTOs.ResultResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.CreateRequest;
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
        String accessToken =  (String) session.getAttribute("access_token");
         ResultResponse response =  playlistService.generatePlaylistFromSetlist(request,accessToken);
         if (response == null) {
             return ResponseEntity.status(HttpStatus.
                     BAD_REQUEST).body("No songs found for setlist ID " + request.setlistId());
         }
         if (!response.playlistCreated()) {
             return ResponseEntity.unprocessableEntity().body(response);
         }
         return ResponseEntity.ok(response);
    }
}
