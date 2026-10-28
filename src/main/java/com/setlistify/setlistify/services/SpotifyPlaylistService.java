package com.setlistify.setlistify.services;

import com.setlistify.setlistify.client.SpotifyClient;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.NeedsReviewSong;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ResultResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.TrackCandidate;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.CreateRequest;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.CreatePlaylistRequest;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.PlaylistResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.ArtistItem;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.TrackItem;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
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

        List<String> trackUris = new ArrayList<>();
        List<NeedsReviewSong> needsReviewSongs = new ArrayList<>();
        List<String> unmatchedSongs = new ArrayList<>();
        String normalizedArtist = normalize(request.artistName());

        for (String song : songs) {
            if (normalize(song).isEmpty()) {
                unmatchedSongs.add(song == null ? "[missing song name]" : song);
                continue;
            }

            String query = String.format("track:%s artist:%s", song, request.artistName());
            SearchResponse search = spotifyClient.searchTrack(accessToken, query);
            if (search == null || search.tracks() == null || search.tracks().items() == null) {
                throw new IllegalStateException("Spotify track search returned an incomplete response");
            }

            List<TrackItem> candidates = uniqueCandidates(search.tracks().items());
            List<TrackItem> exactTitleMatches = candidates.stream()
                    .filter(track -> normalize(track.name()).equals(normalize(song)))
                    .toList();

            if (exactTitleMatches.isEmpty()) {
                unmatchedSongs.add(song);
                continue;
            }

            List<TrackItem> exactTitleAndArtistMatches = exactTitleMatches.stream()
                    .filter(track -> hasArtist(track, normalizedArtist))
                    .toList();

            if (exactTitleAndArtistMatches.size() == 1) {
                trackUris.add(exactTitleAndArtistMatches.getFirst().uri());
            } else {
                List<TrackItem> reviewCandidates = exactTitleAndArtistMatches.isEmpty()
                        ? exactTitleMatches
                        : exactTitleAndArtistMatches;
                needsReviewSongs.add(new NeedsReviewSong(
                        song,
                        reviewCandidates.stream().map(SpotifyPlaylistService::toCandidate).toList()
                ));
            }
        }

        if (trackUris.isEmpty()) {
            return new ResultResponse(
                    null,
                    null,
                    null,
                    false,
                    songs.size(),
                    0,
                    needsReviewSongs,
                    unmatchedSongs
            );
        }

        PlaylistResponse playlist = spotifyClient.createPlaylist(CreatePlaylistRequest.from(request), accessToken);
        for (int i = 0; i < trackUris.size(); i += 100) {
            List<String> batch = trackUris.subList(i, Math.min(i + 100, trackUris.size()));
            spotifyClient.addTracksToPlaylist(accessToken, playlist.id(), batch);
        }
        return new ResultResponse(
                playlist.id(),
                playlist.name(),
                playlist.getSpotifyUrl(),
                true,
                songs.size(),
                trackUris.size(),
                needsReviewSongs,
                unmatchedSongs
        );
    }

    private static List<TrackItem> uniqueCandidates(List<TrackItem> items) {
        Set<String> seenUris = new HashSet<>();
        List<TrackItem> unique = new ArrayList<>();
        for (TrackItem item : items) {
            if (item != null && item.uri() != null && !item.uri().isBlank() && seenUris.add(item.uri())) {
                unique.add(item);
            }
        }
        return unique;
    }

    private static boolean hasArtist(TrackItem track, String normalizedArtist) {
        if (normalizedArtist.isEmpty() || track.artists() == null) {
            return false;
        }
        return track.artists().stream()
                .filter(Objects::nonNull)
                .map(ArtistItem::name)
                .anyMatch(artist -> normalize(artist).equals(normalizedArtist));
    }

    private static TrackCandidate toCandidate(TrackItem track) {
        List<String> artists = track.artists() == null
                ? List.of()
                : track.artists().stream()
                        .filter(Objects::nonNull)
                        .map(ArtistItem::name)
                        .filter(Objects::nonNull)
                        .toList();
        String spotifyUrl = track.externalUrls() == null ? null : track.externalUrls().get("spotify");
        return new TrackCandidate(track.name(), artists, track.uri(), spotifyUrl);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD);
        return decomposed.replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
