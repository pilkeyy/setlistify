package com.setlistify.setlistify.services;

import com.setlistify.setlistify.client.SpotifyClient;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ResultResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.CreateRequest;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.CreatePlaylistRequest;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.PlaylistResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


public class SpotifyPlaylistService {
    private final SpotifyClient spotifyClient;
    private final SetlistService setlistService;

    public SpotifyPlaylistService(SpotifyClient spotifyClient, SetlistService setlistService) {
        this.spotifyClient = spotifyClient;
        this.setlistService = setlistService;
    }

    public ResultResponse generatePlaylistFromSetlist(CreateRequest request, String accessToken) {
        List<String> songs = setlistService.getTracksFromSetlist(request.setlistId());
        if (songs == null || songs.isEmpty()) {
            return null;
        }
        PlaylistResponse playlist = spotifyClient.createPlaylist(CreatePlaylistRequest.from(request), accessToken);
        List<String> trackUris = new ArrayList<>();
        List<String> unmatchedSongs = new ArrayList<>();
        for (String song : songs) {
            String query = String.format("track:%s artist:%s", song, request.artistName());
            SearchResponse search = spotifyClient.searchTrack(accessToken, query);
            Optional.ofNullable(search)
                    .map(SearchResponse::tracks)
                    .map(SearchResponse.TracksWrapper::items)
                    .filter(items -> !items.isEmpty())
                    .map(List::getFirst)
                    .map(SearchResponse.TrackItem::uri)
                    .ifPresentOrElse(
                            trackUris::add,
                            () -> unmatchedSongs.add(song)
                    );
        }
        for (int i = 0; i < trackUris.size(); i += 100) {
            List<String> batch = trackUris.subList(i, Math.min(i + 100, trackUris.size()));
            spotifyClient.addTracksToPlaylist(accessToken, playlist.id(), batch);
        }
        return new ResultResponse(
                playlist.id(),
                playlist.name(),
                playlist.getSpotifyUrl(),
                songs.size(),
                trackUris.size(),
                unmatchedSongs
        );
    }
}
