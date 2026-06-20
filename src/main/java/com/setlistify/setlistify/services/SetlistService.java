package com.setlistify.setlistify.services;

import com.setlistify.setlistify.client.SetlistFmClient;
import com.setlistify.setlistify.model.dto.ConcertRecord;
import com.setlistify.setlistify.model.dto.ConcertSummary;
import com.setlistify.setlistify.model.dto.SetlistResponse;
import com.setlistify.setlistify.model.dto.SongRecord;
import org.springframework.stereotype.Service;


import java.util.Collections;
import java.util.List;


@Service
public class SetlistService {
    private final SetlistFmClient setlistFmClient;

    public SetlistService(SetlistFmClient setlistFmClient) {
        this.setlistFmClient = setlistFmClient;
    }

    public List<ConcertSummary> searchConcertDirectory(String artistName) {
        SetlistResponse response = setlistFmClient.fetchRawSetlists(artistName);
        if (response == null || response.setlist() == null) {
            return Collections.emptyList();
        }
        return response.setlist().stream()
                       .map(ConcertSummary::from)
                       .toList();
    }

    public List<String> getTracksFromSetlist(String setlistId) {
        ConcertRecord concert = setlistFmClient.fetchSingleSetlist(setlistId);
        if (concert == null || concert.sets() == null || concert.sets().set() == null) {
            return Collections.emptyList();
        }
        return concert.sets()
                      .set()
                      .stream()
                      .filter(set -> set.song() != null)
                      .flatMap(set -> set.song().stream())
                      .map(SongRecord::name)
                      .toList();
    }
}
