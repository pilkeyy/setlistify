package com.setlistify.setlistify.services;

import com.setlistify.setlistify.client.SpotifyClient;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ArtworkImage;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.CandidateAlbum;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.CreateRequest;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.MatchReason;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.MatchStatus;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.NeedsReviewSong;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.PreviewResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.ResultResponse;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.SongReview;
import com.setlistify.setlistify.model.dto.PlaylistDTOs.TrackCandidate;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.CreatePlaylistRequest;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.PlaylistResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.AlbumItem;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.ArtistItem;
import com.setlistify.setlistify.model.dto.SpotifyDTOs.SearchResponse.TrackItem;
import com.setlistify.setlistify.services.SetlistService.SetlistTracks;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SpotifyPlaylistService {
    private static final String VERSION_LABEL = "bonus(?:\\s+track)?|remix|live|acoustic|demo|instrumental"
            + "|remaster(?:ed)?(?:\\s+\\d{4})?|\\d{4}\\s+remaster(?:ed)?";
    private static final Pattern VERSION_SUFFIX = Pattern.compile(
            "(?iu)\\s*(?:[-–—]\\s*(?:%1$s)|\\(\\s*(?:%1$s)\\s*\\)|\\[\\s*(?:%1$s)\\s*\\])\\s*$"
                    .formatted(VERSION_LABEL));

    private final SpotifyClient spotifyClient;
    private final SetlistService setlistService;

    public SpotifyPlaylistService(SpotifyClient spotifyClient, SetlistService setlistService) {
        this.spotifyClient = spotifyClient;
        this.setlistService = setlistService;
    }

    public PreviewResponse previewPlaylist(String setlistId, String accessToken) {
        SetlistTracks setlist = setlistService.getSetlistTracks(setlistId);
        if (setlist.songs().isEmpty() || normalize(setlist.artistName()).isEmpty()) {
            return null;
        }
        return new PreviewResponse(setlistId, setlist.artistName(),
                matchSongs(setlist.songs(), setlist.artistName(), accessToken));
    }

    public ResultResponse generatePlaylistFromSetlist(CreateRequest request, String accessToken) {
        List<String> songs = setlistService.getTracksFromSetlist(request.setlistId());
        if (songs == null || songs.isEmpty()) {
            return null;
        }

        List<String> trackUris = new ArrayList<>();
        List<NeedsReviewSong> needsReviewSongs = new ArrayList<>();
        List<String> unmatchedSongs = new ArrayList<>();
        for (SongReview review : matchSongs(songs, request.artistName(), accessToken)) {
            switch (review.status()) {
                case MATCHED -> trackUris.add(review.candidates().getFirst().uri());
                case NEEDS_REVIEW -> needsReviewSongs.add(new NeedsReviewSong(review.title(), review.candidates()));
                case UNMATCHED -> unmatchedSongs.add(review.title() == null ? "[missing song name]" : review.title());
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

    private List<SongReview> matchSongs(List<String> songs, String artistName, String accessToken) {
        String normalizedArtist = normalize(artistName);
        List<SongReview> reviews = new ArrayList<>(songs.size());
        int songId = 0;
        for (String song : songs) {
            reviews.add(classifySong(++songId, song, artistName, normalizedArtist, accessToken));
        }
        return List.copyOf(reviews);
    }

    private SongReview classifySong(int songId, String song, String artistName, String normalizedArtist,
                                    String accessToken) {
        String normalizedTitle = normalize(song);
        if (normalizedTitle.isEmpty()) {
            return new SongReview(songId, song, MatchStatus.UNMATCHED, MatchReason.MISSING_TITLE, List.of());
        }

        String query = String.format("track:%s artist:%s", song, artistName);
        List<TrackItem> candidates = searchCandidates(accessToken, query);
        SongReview review = classifyCandidates(songId, song, normalizedTitle, normalizedArtist, candidates);
        if (review.reason() != MatchReason.NO_EXACT_TITLE && review.reason() != MatchReason.ARTIST_MISMATCH) {
            return review;
        }

        List<TrackItem> combinedCandidates = new ArrayList<>(candidates);
        combinedCandidates.addAll(searchCandidates(accessToken, "track:" + song));
        return classifyCandidates(songId, song, normalizedTitle, normalizedArtist, combinedCandidates);
    }

    private List<TrackItem> searchCandidates(String accessToken, String query) {
        SearchResponse search = spotifyClient.searchTrack(accessToken, query);
        if (search == null || search.tracks() == null || search.tracks().items() == null) {
            throw new IllegalStateException("Spotify track search returned an incomplete response");
        }
        return search.tracks().items();
    }

    private static SongReview classifyCandidates(int songId, String song, String normalizedTitle,
                                                 String normalizedArtist, List<TrackItem> rawCandidates) {
        List<TrackItem> candidates = uniqueCandidates(rawCandidates);
        List<TrackItem> exactTitleMatches = candidates.stream()
                .filter(track -> normalize(track.name()).equals(normalizedTitle))
                .toList();
        List<TrackItem> exactArtistMatches = exactTitleMatches.stream()
                .filter(track -> hasArtist(track, normalizedArtist))
                .toList();
        if (exactArtistMatches.size() == 1) {
            return new SongReview(songId, song, MatchStatus.MATCHED, MatchReason.UNIQUE_EXACT_MATCH,
                    List.of(toCandidate(exactArtistMatches.getFirst())));
        }
        if (!exactArtistMatches.isEmpty()) {
            return needsReview(songId, song, MatchReason.MULTIPLE_RECORDINGS, exactArtistMatches);
        }

        List<TrackItem> creditedVariants = candidates.stream()
                .filter(track -> hasMatchingBaseTitle(track.name(), normalizedTitle))
                .filter(track -> hasArtist(track, normalizedArtist))
                .toList();
        if (!creditedVariants.isEmpty()) {
            return needsReview(songId, song, MatchReason.TITLE_VARIANT, creditedVariants);
        }
        if (!exactTitleMatches.isEmpty()) {
            return needsReview(songId, song, MatchReason.ARTIST_MISMATCH, exactTitleMatches);
        }
        return new SongReview(songId, song, MatchStatus.UNMATCHED, MatchReason.NO_EXACT_TITLE, List.of());
    }

    private static SongReview needsReview(int songId, String song, MatchReason reason, List<TrackItem> candidates) {
        return new SongReview(songId, song, MatchStatus.NEEDS_REVIEW, reason,
                candidates.stream().map(SpotifyPlaylistService::toCandidate).toList());
    }

    private static boolean hasMatchingBaseTitle(String trackName, String normalizedTitle) {
        if (trackName == null) {
            return false;
        }
        var suffix = VERSION_SUFFIX.matcher(trackName);
        return suffix.find() && normalize(trackName.substring(0, suffix.start())).equals(normalizedTitle);
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
        return new TrackCandidate(track.name(), artists, track.uri(), spotifyUrl, toCandidateAlbum(track.album()));
    }

    private static CandidateAlbum toCandidateAlbum(AlbumItem album) {
        if (album == null) {
            return null;
        }
        List<ArtworkImage> images = album.images() == null
                ? List.of()
                : album.images().stream()
                        .filter(image -> image != null && image.url() != null && !image.url().isBlank())
                        .map(image -> new ArtworkImage(image.url(), image.width(), image.height()))
                        .toList();
        String spotifyUrl = album.externalUrls() == null ? null : album.externalUrls().get("spotify");
        return new CandidateAlbum(album.name(), spotifyUrl, images);
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
