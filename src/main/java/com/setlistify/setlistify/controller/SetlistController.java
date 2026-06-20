package com.setlistify.setlistify.controller;

import com.setlistify.setlistify.model.dto.ConcertSummary;
import com.setlistify.setlistify.services.SetlistService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/setlists")
public class SetlistController {
    private final SetlistService setlistService;

    public SetlistController(SetlistService setlistService) {
        this.setlistService = setlistService;
    }

    @GetMapping("/search")
    public List<ConcertSummary> searchSetlists(@RequestParam String artist) {
        return setlistService.searchConcertDirectory(artist);
    }
    
    @GetMapping("/{setlistId}/tracks")
    public List<String> getSetlistTracks(@PathVariable String setlistId) {
        return setlistService.getTracksFromSetlist(setlistId);
    }
}
