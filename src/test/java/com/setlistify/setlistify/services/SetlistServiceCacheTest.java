package com.setlistify.setlistify.services;

import com.setlistify.setlistify.client.SetlistFmClient;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.ArtistRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.ConcertRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SetRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SetsWrapper;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SongRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SetlistServiceCacheTest {
    private final SetlistFmClient setlistFm = mock(SetlistFmClient.class);
    private final SetlistService service = new SetlistService(setlistFm);

    @Test
    void previewReusesSetlistTheBrowserAlreadyLoaded() {
        when(setlistFm.fetchSingleSetlist("concert")).thenReturn(new ConcertRecord("concert", null,
                new ArtistRecord("Band"), null, new SetsWrapper(List.of(new SetRecord(List.of(new SongRecord("Song")))))));

        assertEquals(List.of("Song"), service.getTracksFromSetlist("concert"));
        assertEquals("Band", service.getSetlistTracks("concert").artistName());

        verify(setlistFm, times(1)).fetchSingleSetlist("concert");
    }

    @Test
    void doesNotCacheSetlistsWithoutSongs() {
        when(setlistFm.fetchSingleSetlist("concert")).thenReturn(null);

        service.getTracksFromSetlist("concert");
        service.getTracksFromSetlist("concert");

        verify(setlistFm, times(2)).fetchSingleSetlist("concert");
    }
}
