package com.setlistify.setlistify.services;

import com.setlistify.setlistify.client.SetlistFmClient;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.ArtistRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.ConcertRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SetRecord;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SetsWrapper;
import com.setlistify.setlistify.model.dto.SetlistFmDTOs.SongRecord;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void concurrentMissesShareOneSetlistFmRequest() throws Exception {
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(setlistFm.fetchSingleSetlist("concert")).thenAnswer(invocation -> {
            fetchStarted.countDown();
            release.await();
            return concert();
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<String>> first = executor.submit(() -> service.getTracksFromSetlist("concert"));
            fetchStarted.await();
            Future<List<String>> second = executor.submit(() -> service.getTracksFromSetlist("concert"));
            Thread.sleep(100); // Let the second caller find the in-flight fetch.
            release.countDown();

            assertEquals(List.of("Song"), first.get());
            assertEquals(List.of("Song"), second.get());
        }
        verify(setlistFm, times(1)).fetchSingleSetlist("concert");
    }

    @Test
    void sharedFetchFailureReachesEveryWaiterUnwrapped() throws Exception {
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(setlistFm.fetchSingleSetlist("concert")).thenAnswer(invocation -> {
            fetchStarted.countDown();
            release.await();
            throw new ResourceAccessException("setlist.fm unreachable");
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<String>> first = executor.submit(() -> service.getTracksFromSetlist("concert"));
            fetchStarted.await();
            Future<List<String>> second = executor.submit(() -> service.getTracksFromSetlist("concert"));
            Thread.sleep(100);
            release.countDown();

            assertInstanceOf(ResourceAccessException.class, assertThrows(ExecutionException.class, first::get).getCause());
            assertInstanceOf(ResourceAccessException.class, assertThrows(ExecutionException.class, second::get).getCause());
        }
        verify(setlistFm, times(1)).fetchSingleSetlist("concert");
    }

    private static ConcertRecord concert() {
        return new ConcertRecord("concert", null, new ArtistRecord("Band"), null,
                new SetsWrapper(List.of(new SetRecord(List.of(new SongRecord("Song"))))));
    }

    @Test
    void doesNotCacheSetlistsWithoutSongs() {
        when(setlistFm.fetchSingleSetlist("concert")).thenReturn(null);

        service.getTracksFromSetlist("concert");
        service.getTracksFromSetlist("concert");

        verify(setlistFm, times(2)).fetchSingleSetlist("concert");
    }
}
