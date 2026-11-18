package com.setlistify.setlistify.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public class PlaylistDTOs {

    public record PreviewRequest(String setlistId) {
    }

    public record PreviewResponse(String setlistId, String artistName, List<SongReview> songs) {
    }

    public record SongReview(
            int songId,
            String title,
            MatchStatus status,
            MatchReason reason,
            List<TrackCandidate> candidates
    ) {
    }

    public enum MatchStatus {
        MATCHED, NEEDS_REVIEW, UNMATCHED
    }

    public enum MatchReason {
        UNIQUE_EXACT_MATCH, MULTIPLE_RECORDINGS, TITLE_VARIANT, ARTIST_MISMATCH, NO_EXACT_TITLE, MISSING_TITLE
    }

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
            String spotifyUrl,
            @JsonInclude(JsonInclude.Include.NON_NULL) CandidateAlbum album
    ) {
    }

    public record CandidateAlbum(String name, String spotifyUrl, List<ArtworkImage> images) {
    }

    public record ArtworkImage(String url, Integer width, Integer height) {
    }
}
