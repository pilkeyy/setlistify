package com.setlistify.setlistify.model.dto;

import java.util.List;

public class PlaylistDTOs {

    public record CreateRequest(
            String setlistId,
            String artistName,
            String playlistName,
            Boolean isPublic
    ) {}

    public record ResultResponse(
            String playlistId,
            String playlistName,
            String spotifyUrl,
            boolean playlistCreated,
            int totalSetlistSongsCount,
            int matchedItemsCount,
            List<NeedsReviewSong> needsReviewSongs,
            List<String> unmatchedSongs
    ) {
    }

    public record NeedsReviewSong(
            String setlistSong,
            List<TrackCandidate> candidates
    ) {
    }

    public record TrackCandidate(
            String name,
            List<String> artists,
            String uri,
            String spotifyUrl
    ) {
    }
}
