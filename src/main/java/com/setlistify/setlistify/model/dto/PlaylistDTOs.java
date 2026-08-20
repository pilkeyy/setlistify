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
            int totalSetlistSongsCount,
            int matchedItemsCount,
            List<String> unmatchedSongs
    ) {}
}
